package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.termux.R;

/**
 * Renders /manager/printer in a headless WebView and pushes the capture into a
 * home-screen widget.
 *
 * The renderer lives on its own HandlerThread: the WebView is created, loads,
 * polls and is captured on that thread, so the main thread (and the rest of the
 * app) is never blocked. The work is capped at ~15 s; on any failure the widget
 * is handed the native face from {@link JawnPrinterWidget}, so it is never blank.
 *
 * The manager's own session is reused: when the president cookie is already in
 * the WebView's cookie jar the printer URL is loaded directly; otherwise the
 * ticket URL (JawnLauncher.getUiUrl) is loaded first, and its redirect to the
 * manager is intercepted and replaced with the printer URL so the heavy manager
 * page is never rendered. TLS is trusted only for the house host (the same rule
 * JawnWebViewActivity applies), never for a stranger.
 */
public class JawnPrinterService extends Service {

    private static final String TAG = "JawnPrinterService";
    private static final String CHANNEL = "jawn_printer";
    private static final int NOTIFICATION_ID = 7311;
    private static final long BUDGET_MS = 15000;
    private static final long TICKET_STAGE_MS = 6000;
    private static final int MAX_CAPTURE = 512;

    private HandlerThread thread;
    private volatile Handler handler;
    private volatile WebView web;
    private volatile boolean running = false;
    private volatile boolean finished = false;
    private volatile int stage = 0; // 0 = minting a session from the ticket, 1 = the printer page
    private volatile boolean mintedTicket = false;
    private boolean foreground = false;
    private final java.util.concurrent.ConcurrentLinkedQueue<Request> queue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private String kind = JawnPrinterWidget.KIND_CLOCKFACE;
    private int widgetW = 320;
    private int widgetH = 320;
    private int renderW = 320;
    private int renderH = 320;
    private String managerUrl = "";
    private String printerUrl = "";
    private String houseHost = "";
    private long deadline = 0;
    private int polls = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // A system-initiated stop may arrive while a render is in flight; take
        // the WebView down on its own thread rather than leaking it.
        finished = true;
        Handler h = handler;
        if (h != null) {
            h.post(() -> {
                try {
                    if (web != null) {
                        web.stopLoading();
                        web.destroy();
                        web = null;
                    }
                } catch (Throwable ignored) {
                }
                if (thread != null) thread.quitSafely();
            });
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int id = intent.getIntExtra(JawnPrinterWidget.EXTRA_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return START_NOT_STICKY;
        }
        String wanted = intent.getStringExtra(JawnPrinterWidget.EXTRA_KIND);
        if (wanted == null) wanted = JawnPrinterWidget.KIND_CLOCKFACE;
        int w = Math.max(96, intent.getIntExtra(JawnPrinterWidget.EXTRA_WIDTH, 320));
        int h = Math.max(96, intent.getIntExtra(JawnPrinterWidget.EXTRA_HEIGHT, 320));

        Request request = new Request(id, wanted, w, h);
        if (running) {
            // a second widget's update waits its turn rather than being dropped
            queue.add(request);
            return START_NOT_STICKY;
        }
        startInForeground();
        startRender(request);
        return START_NOT_STICKY;
    }

    private void startRender(Request request) {
        running = true;
        finished = false;
        stage = 0;
        mintedTicket = false;
        polls = 0;

        appWidgetId = request.id;
        kind = request.kind;
        widgetW = request.w;
        widgetH = request.h;

        // The capture travels through RemoteViews, which is a Binder payload:
        // keep the longer side small enough for an RGB_565 bitmap to fit.
        double scale = Math.min(1.0, (double) MAX_CAPTURE / (double) Math.max(widgetW, widgetH));
        renderW = Math.max(64, (int) Math.round(widgetW * scale));
        renderH = Math.max(64, (int) Math.round(widgetH * scale));

        // A WebView may only be touched on the UI thread: creating it on a
        // HandlerThread throws "Calling methods on another thread than the UI
        // thread". The render is small (a few hundred ms) and offscreen, so the
        // main looper is the right home for it.
        handler = new Handler(Looper.getMainLooper());
        handler.post(this::begin);
    }

    private void startInForeground() {
        if (foreground) return;
        foreground = true;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Printer widget", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle("JawnOS")
                .setContentText("Drawing the " + JawnPrinterWidget.labelFor(kind).toLowerCase())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void begin() {
        int port = JawnLauncher.getJawnPort(this);
        houseHost = JawnLauncher.managerHost(this);
        managerUrl = JawnLauncher.getManagerUrl(this, port);
        printerUrl = managerUrl + "/printer"
                + "?kind=" + Uri.encode(kind)
                + "&w=" + renderW
                + "&h=" + renderH
                + "&scope=day"
                + "&layout=" + Uri.encode(kind);

        deadline = System.currentTimeMillis() + BUDGET_MS;
        handler.postDelayed(this::onDeadline, BUDGET_MS);

        CookieManager cookies = CookieManager.getInstance();
        try {
            cookies.setAcceptCookie(true);
        } catch (Throwable ignored) {
        }

        try {
            web = new WebView(getApplicationContext());
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setLoadsImagesAutomatically(true);
            settings.setBlockNetworkImage(false);
            settings.setAllowFileAccess(false);
            settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.setBackgroundColor(Color.TRANSPARENT);
            // An offscreen WebView is not composited by a window, so software
            // drawing is what makes WebView.draw(canvas) produce the page.
            web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            try {
                cookies.setAcceptThirdPartyCookies(web, true);
            } catch (Throwable ignored) {
            }
            // pauseTimers() is process-wide; the manager view pauses it when
            // it leaves the screen, and this offscreen renderer would then sit
            // forever at the page's first script. Resume before loading.
            web.resumeTimers();
            web.onResume();
            web.setWebViewClient(new HouseClient());
            web.measure(View.MeasureSpec.makeMeasureSpec(renderW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(renderH, View.MeasureSpec.EXACTLY));
            web.layout(0, 0, renderW, renderH);
        } catch (Throwable t) {
            fail("webview: " + t);
            return;
        }

        if (hasSession(managerUrl)) {
            stage = 1;
            web.loadUrl(printerUrl);
        } else {
            mintedTicket = true;
            stage = 0;
            web.loadUrl(JawnLauncher.getUiUrl(this, port));
            // a ticket page that never settles still gives way to the printer
            // page, which may find the session the check could not see
            handler.postDelayed(this::advanceFromTicket, TICKET_STAGE_MS);
        }
        poll();
    }

    private boolean hasSession(String url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            return cookie != null && cookie.indexOf("president=") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The ticket has had its chance: move on to the printer page. */
    private void advanceFromTicket() {
        if (finished || web == null) return;
        if (stage == 0) {
            stage = 1;
            try {
                web.loadUrl(printerUrl);
            } catch (Throwable t) {
                fail("printer load: " + t);
            }
        }
    }

    /** The cookie we trusted was stale (the Gate page came back): mint one. */
    private void retryWithTicket() {
        if (finished || web == null || mintedTicket) return;
        mintedTicket = true;
        stage = 0;
        try {
            web.loadUrl(JawnLauncher.getUiUrl(this, JawnLauncher.getJawnPort(this)));
        } catch (Throwable t) {
            fail("ticket load: " + t);
            return;
        }
        Handler h = handler;
        if (h != null) h.postDelayed(this::advanceFromTicket, 4000);
    }

    /** Poll document.title until the page says it has drawn. */
    private void poll() {
        if (finished || web == null) return;
        if (System.currentTimeMillis() > deadline) {
            fail("timeout");
            return;
        }
        polls++;
        try {
            web.evaluateJavascript("(document.getElementById('app_required') ? 'gated' : (document.title||''))", value -> {
                Handler h = handler;
                if (h != null) h.post(() -> onTitle(value));
            });
        } catch (Throwable t) {
            fail("poll: " + t);
            return;
        }
        Handler h = handler;
        if (h != null && polls < 120) {
            h.postDelayed(this::poll, 250);
        }
    }

    private void onTitle(String value) {
        if (finished) return;
        String title = unquote(value);
        if ("ready".equals(title)) {
            capture();
        } else if ("error".equals(title)) {
            fail("page reported an error");
        } else if ("gated".equals(title)) {
            // The app gate (or a stale session) answered instead of the printer.
            // Mint a session once; a second Gate page means the house will not
            // admit this WebView, and the native face is the honest result.
            if (!mintedTicket) {
                retryWithTicket();
            } else {
                fail("gated");
            }
        }
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
            // the title is plain ASCII from our own page
            v = v.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }

    private void onDeadline() {
        if (!finished) fail("timeout");
    }

    /** Capture the drawn page, or hand over the native face when there is none. */
    private void capture() {
        if (finished || web == null) return;
        finished = true;
        Bitmap raw = null;
        try {
            web.measure(View.MeasureSpec.makeMeasureSpec(renderW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(renderH, View.MeasureSpec.EXACTLY));
            web.layout(0, 0, renderW, renderH);
            raw = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(raw);
            canvas.drawColor(Color.TRANSPARENT);
            web.draw(canvas);
            if (!hasInk(raw)) {
                throw new IllegalStateException("blank capture");
            }
            Bitmap safe = raw.copy(Bitmap.Config.RGB_565, false);
            Bitmap push = safe != null ? safe : raw;
            push(push);
            if (push != raw && !raw.isRecycled()) raw.recycle();
            Log.i(TAG, "captured " + renderW + "x" + renderH + " " + kind);
        } catch (Throwable t) {
            Log.w(TAG, "JS capture failed: " + t);
            if (raw != null && !raw.isRecycled()) raw.recycle();
            nativeFallback();
        } finally {
            cleanup();
        }
    }

    /** Enough distinct samples to know the page drew rather than staying empty. */
    private static boolean hasInk(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return false;
        int corner = bitmap.getPixel(0, 0);
        int distinct = 0;
        for (int gx = 1; gx <= 12; gx++) {
            for (int gy = 1; gy <= 12; gy++) {
                int px = Math.min(w - 1, w * gx / 13);
                int py = Math.min(h - 1, h * gy / 13);
                if (bitmap.getPixel(px, py) != corner) {
                    distinct++;
                    if (distinct >= 2) return true;
                }
            }
        }
        return false;
    }

    private void nativeFallback() {
        try {
            Bitmap face = JawnPrinterWidget.fallbackBitmap(this, kind, widgetW, widgetH);
            if (face != null) push(face);
        } catch (Throwable t) {
            Log.w(TAG, "native fallback: " + t);
        }
    }

    private void push(Bitmap bitmap) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            manager.updateAppWidget(appWidgetId,
                    JawnPrinterWidget.views(this, appWidgetId, kind, bitmap));
        } catch (Throwable t) {
            Log.w(TAG, "push: " + t);
        }
    }

    private void fail(String why) {
        if (finished) return;
        finished = true;
        Log.w(TAG, why);
        nativeFallback();
        cleanup();
    }

    private void cleanup() {
        Handler h = handler;
        if (h != null) h.removeCallbacksAndMessages(null);
        try {
            WebView view = web;
            if (view != null) {
                web = null;
                view.stopLoading();
                view.destroy();
            }
        } catch (Throwable ignored) {
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
        running = false;

        Request next = queue.poll();
        if (next != null) {
            // keep the foreground service (and this Service instance) alive for
            // the next widget's turn
            startRender(next);
            return;
        }
        if (foreground) {
            foreground = false;
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
        }
        stopSelf();
    }

    private static final class Request {
        final int id;
        final String kind;
        final int w;
        final int h;

        Request(int id, String kind, int w, int h) {
            this.id = id;
            this.kind = kind;
            this.w = w;
            this.h = h;
        }
    }

    private class HouseClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (stage == 0 && request != null && request.getUrl() != null) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                String path = uri.getPath();
                // The ticket's own redirect to the manager is replaced with the
                // printer page: the cookie is already set, and rendering the
                // whole desktop would only cost the widget its time budget.
                if (host != null && host.equals(houseHost)
                        && ("/manager".equals(path) || "/manager/".equals(path))) {
                    stage = 1;
                    view.post(() -> {
                        try {
                            view.loadUrl(printerUrl);
                        } catch (Throwable t) {
                            fail("printer load: " + t);
                        }
                    });
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            try {
                CookieManager.getInstance().flush();
            } catch (Throwable ignored) {
            }
            if (stage == 0) {
                Handler h = handler;
                if (h != null) h.post(JawnPrinterService.this::advanceFromTicket);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request == null || !request.isForMainFrame()) return;
            if (stage == 0) {
                Handler h = handler;
                if (h != null) h.post(JawnPrinterService.this::advanceFromTicket);
            } else {
                Handler h = handler;
                if (h != null) h.post(() -> fail("page error"));
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request == null || !request.isForMainFrame()) return;
            if (stage == 1) {
                Handler h = handler;
                if (h != null) h.post(() -> fail("http " + (response == null ? "error" : response.getStatusCode())));
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // The house serves its own certificate; trust only that origin.
            String host = error == null ? null : Uri.parse(error.getUrl()).getHost();
            if (host != null && (host.equals(houseHost)
                    || host.equals("127.0.0.1") || host.equals("localhost"))) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }
    }
}
