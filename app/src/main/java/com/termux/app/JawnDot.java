package com.termux.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.termux.shared.logger.Logger;

/**
 * The JawnOS status dot. A 12 dp overlay circle, top-left (out of the way of the keyboard). Besides being a status
 * light, the window keeps the whole app (and therefore every process of its UID,
 * including the jawn Perl workers) out of the restricted background cpuset that
 * Samsung phones apply to apps that are not visible. It works even with the screen
 * off. Tapping it opens the dashboard.
 */
public class JawnDot {

    private static final String LOG_TAG = "JawnDot";

    public static final int GREEN = 0xFF00AA00;
    public static final int YELLOW = 0xFFFFAA00;
    public static final int RED = 0xFFCC0000;

    private static View view;
    private static WindowManager wm;

    public static void show(final Context context) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    if (view != null) return;
                    if (!Settings.canDrawOverlays(context)) {
                        Logger.logError(LOG_TAG, "Overlay permission not granted; dot not shown");
                        return;
                    }
                    wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                    float density = context.getResources().getDisplayMetrics().density;
                    int size = (int) (12 * density);
                    View v = new View(context);
                    GradientDrawable d = new GradientDrawable();
                    d.setShape(GradientDrawable.OVAL);
                    d.setColor(GREEN);
                    v.setBackground(d);
                    // Transparent: the app opens on its launcher dashboard now, so
                    // the dot is no longer needed as a visible affordance (or as a
                    // tap target). The window itself is kept -- it is what holds
                    // the app out of Samsung's restricted background cpuset even
                    // with the screen off -- so only its visibility changes.
                    v.setAlpha(0f);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        size, size,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.x = (int) (2 * density);
                    lp.y = (int) (2 * density);
                    Intent tap = new Intent(context, JawnDashboardActivity.class);
                    tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    final PendingIntent pi = PendingIntent.getActivity(context, 0, tap,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    v.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View clicked) {
                            try {
                                pi.send();
                            } catch (Throwable t) {
                                Logger.logError(LOG_TAG, "Failed to open dashboard: " + t);
                            }
                        }
                    });
                    wm.addView(v, lp);
                    view = v;
                    Logger.logInfo(LOG_TAG, "Dot shown");
                } catch (Throwable t) {
                    Logger.logError(LOG_TAG, "Failed to show dot: " + t);
                }
            }
        });
    }

    public static void hide(final Context context) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    if (view != null && wm != null) wm.removeView(view);
                } catch (Throwable t) {
                    // ignore
                }
                view = null;
                wm = null;
                Logger.logInfo(LOG_TAG, "Dot hidden");
            }
        });
    }

    public static void setColor(final int color) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    if (view != null && view.getBackground() instanceof GradientDrawable)
                        ((GradientDrawable) view.getBackground()).setColor(color);
                } catch (Throwable t) {
                    // ignore
                }
            }
        });
    }
}
