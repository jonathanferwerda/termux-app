package com.jawnos.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import com.jawnos.R;

import java.util.Calendar;

/**
 * The home-screen printer card.
 *
 * The card is drawn by the manager's real JavaScript printers: the provider
 * paints a native face at once (so the card is never blank), then asks
 * {@link JawnPrinterService} to render /manager/printer in a headless WebView
 * and push the captured bitmap through RemoteViews. A tap opens the manager.
 *
 * The native face is also the fallback the service uses when the WebView
 * cannot load, the bundle cannot draw, or the capture is blank.
 */
public class JawnPrinterWidget extends AppWidgetProvider {

    private static final String TAG = "JawnPrinterWidget";

    public static final String EXTRA_ID = "com.jawnos.app.PRINTER_WIDGET_ID";
    public static final String EXTRA_KIND = "com.jawnos.app.PRINTER_KIND";
    public static final String EXTRA_WIDTH = "com.jawnos.app.PRINTER_WIDTH";
    public static final String EXTRA_HEIGHT = "com.jawnos.app.PRINTER_HEIGHT";

    public static final String KIND_CLOCKFACE = "clockface";
    public static final String KIND_TIMELINE = "timeline";

    // A RemoteViews payload rides a Binder transaction (~1 MB), so the captured
    // face is capped and pushed as RGB_565 by the service. The native face keeps
    // the same cap for consistency.
    static final int MAX_FALLBACK = 512;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, manager, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        updateWidget(context, manager, appWidgetId);
    }

    /** Paint the native face immediately, then let the WebView replace it. */
    static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        int[] size = widgetSize(context, manager, appWidgetId);
        String kind = KIND_CLOCKFACE;
        Bitmap first = fallbackBitmap(context, kind, size[0], size[1]);
        try {
            manager.updateAppWidget(appWidgetId, views(context, appWidgetId, kind, first));
        } catch (Throwable t) {
            Log.w(TAG, "initial face: " + t);
        }
        if (first != null && !first.isRecycled()) first.recycle();

        try {
            Intent intent = new Intent(context, JawnPrinterService.class);
            intent.putExtra(EXTRA_ID, appWidgetId);
            intent.putExtra(EXTRA_KIND, kind);
            intent.putExtra(EXTRA_WIDTH, size[0]);
            intent.putExtra(EXTRA_HEIGHT, size[1]);
            context.startForegroundService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "could not start the printer renderer: " + t);
        }
    }

    /** The widget's own size in pixels, from the host's dp options. */
    static int[] widgetSize(Context context, AppWidgetManager manager, int appWidgetId) {
        int dpW = 250, dpH = 250;
        try {
            Bundle options = manager.getAppWidgetOptions(appWidgetId);
            int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
            int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250);
            int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
            int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
            dpW = Math.max(minW, maxW);
            dpH = Math.max(minH, maxH);
        } catch (Throwable ignored) {
            // an options bundle the host did not fill: the defaults are fine
        }
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round(dpW * density);
        int h = Math.round(dpH * density);
        return new int[] {
            Math.max(96, Math.min(1024, w)),
            Math.max(96, Math.min(1024, h))
        };
    }

    /** Build the RemoteViews the service and the provider both push. */
    static RemoteViews views(Context context, int appWidgetId, String kind, Bitmap bitmap) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.jawn_printer_widget);
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.jawn_printer_image, bitmap);
        }
        views.setTextViewText(R.id.jawn_printer_title, labelFor(kind));

        Intent open = new Intent(context, JawnWebViewActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, appWidgetId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.jawn_printer_image, tap);
        views.setOnClickPendingIntent(R.id.jawn_printer_title, tap);
        return views;
    }

    static String labelFor(String kind) {
        if (KIND_TIMELINE.equals(kind)) return "Timeline";
        return "Clockface";
    }

    /**
     * The native face: five scope rings with ticks, twelve hour dots and hands
     * reading the phone's own clock. It is deliberately close to the real
     * clockface so the widget never shows a placeholder.
     */
    static Bitmap fallbackBitmap(Context context, String kind, int width, int height) {
        int w = Math.max(64, width);
        int h = Math.max(64, height);
        double scale = Math.min(1.0, (double) MAX_FALLBACK / (double) Math.max(w, h));
        int bw = Math.max(64, (int) Math.round(w * scale));
        int bh = Math.max(64, (int) Math.round(h * scale));
        Bitmap argb = null;
        try {
            argb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(argb);
            if (KIND_TIMELINE.equals(kind)) {
                timelineFace(canvas, bw, bh);
            } else {
                clockFace(canvas, bw, bh);
            }
            Bitmap safe = argb.copy(Bitmap.Config.RGB_565, false);
            if (safe != null) {
                argb.recycle();
                return safe;
            }
            return argb;
        } catch (Throwable t) {
            Log.w(TAG, "native face: " + t);
            if (argb != null && !argb.isRecycled()) argb.recycle();
            return null;
        }
    }

    private static void clockFace(Canvas canvas, int w, int h) {
        canvas.drawColor(0xFF10151B);
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) * 0.42f;
        float unit = Math.max(1f, radius / 100f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        int[] ringPct = { 100, 80, 63, 48, 34 };
        for (int ring = 0; ring < ringPct.length; ring++) {
            float r = radius * ringPct[ring] / 100f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0x55E6EDF5);
            paint.setStrokeWidth(Math.max(1f, 1.4f * unit));
            canvas.drawCircle(cx, cy, r, paint);

            for (int tick = 0; tick < 60; tick++) {
                boolean major = (tick % 5) == 0;
                double angle = (tick / 60.0) * Math.PI * 2;
                float inner = r - (major ? 11f * unit : 6f * unit);
                paint.setColor(major ? 0x99E6EDF5 : 0x44E6EDF5);
                paint.setStrokeWidth(major ? Math.max(1.4f, 1.8f * unit) : Math.max(1f, unit));
                canvas.drawLine(
                        cx + (float) Math.sin(angle) * inner,
                        cy - (float) Math.cos(angle) * inner,
                        cx + (float) Math.sin(angle) * r,
                        cy - (float) Math.cos(angle) * r,
                        paint);
            }
        }

        // twelve hour dots just inside the outer ring
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF7FB6E8);
        for (int hour = 1; hour <= 12; hour++) {
            double angle = (hour / 12.0) * Math.PI * 2;
            float x = cx + (float) Math.sin(angle) * (radius * 0.88f);
            float y = cy - (float) Math.cos(angle) * (radius * 0.88f);
            canvas.drawCircle(x, y, Math.max(1.6f, 2.4f * unit), paint);
        }

        Calendar now = Calendar.getInstance();
        double hourAngle = ((now.get(Calendar.HOUR_OF_DAY) % 12)
                + now.get(Calendar.MINUTE) / 60.0) / 12.0 * Math.PI * 2;
        double minuteAngle = (now.get(Calendar.MINUTE) / 60.0) * Math.PI * 2;

        paint.setColor(0xFFE6EDF5);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, 4.6f * unit));
        canvas.drawLine(cx, cy,
                cx + (float) Math.sin(hourAngle) * (radius * 0.34f),
                cy - (float) Math.cos(hourAngle) * (radius * 0.34f), paint);
        paint.setStrokeWidth(Math.max(2f, 2.8f * unit));
        canvas.drawLine(cx, cy,
                cx + (float) Math.sin(minuteAngle) * (radius * 0.52f),
                cy - (float) Math.cos(minuteAngle) * (radius * 0.52f), paint);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, Math.max(2f, 4.5f * unit), paint);

        // the outermost ring's name, where the real face writes it
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(Math.max(9f, 11f * unit));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0x88E6EDF5);
        canvas.drawText("clockface", cx, cy - radius + 12f * unit, paint);
    }

    private static void timelineFace(Canvas canvas, int w, int h) {
        canvas.drawColor(0xFF10151B);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = w / 2f;
        float pad = Math.min(w, h) * 0.10f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(4f, h * 0.018f));
        paint.setColor(0x884A5868);
        canvas.drawLine(cx, pad, cx, h - pad, paint);
        paint.setStrokeWidth(Math.max(2f, h * 0.008f));
        paint.setColor(0xFF7FB6E8);
        canvas.drawLine(cx, pad, cx, h - pad, paint);

        int rows = 5;
        int[] colours = { 0xFF7FB6E8, 0xFFE0A458, 0xFF8FCB8F, 0xFFC38CD6, 0xFFE07A7A };
        for (int i = 0; i < rows; i++) {
            float y = pad + (h - pad * 2f) * (i + 1f) / (rows + 1f);
            float x = pad + (w - pad * 2f) * (0.18f + 0.16f * i);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF10151B);
            canvas.drawCircle(x, y, Math.max(5f, h * 0.035f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(colours[i % colours.length]);
            paint.setStrokeWidth(Math.max(2f, h * 0.010f));
            canvas.drawCircle(x, y, Math.max(5f, h * 0.035f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFCFD8E3);
            canvas.drawRect(x + h * 0.06f, y - h * 0.008f, x + h * 0.20f, y + h * 0.008f, paint);
        }

        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(Math.max(9f, h * 0.028f));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0x88CFD8E3);
        canvas.drawText("timeline", cx, pad * 0.6f, paint);
    }
}
