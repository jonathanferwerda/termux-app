package com.termux.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The house's only source of place: a foreground service that reads the
 * <b>GPS provider</b> and nothing else.
 *
 * The web view's geolocation (and termux-location's network provider) can answer
 * from Wi-Fi or a cell tower while still reporting a plausible accuracy, which
 * put the person in places they had never been. This service exists so the
 * manager can say "this machine's place is the GPS fix in this file" and mean
 * it: every record it writes names {@code provider: "gps"}.
 *
 * The latest fix is written to {@code ~/.president/location.json} for the Perl
 * tree to read without shelling out, and handed to the page through
 * {@link JawnLocationBridge} as {@code window.JawnLocation}.
 *
 * Battery: the service is a location foreground service, so Android keeps the
 * process and wakes it when a fix arrives. It holds no permanent wake lock: a
 * scheduled receiver ({@link JawnScheduledWakeReceiver}) takes a short CPU-only lock once
 * per navigation interval so the Perl tree can run its due utilities, then lets
 * the device sleep again. The interval is the navigation pace (four minutes by
 * default); the caller may shorten it while an appointment asks for faster fixes.
 */
public class JawnLocationService extends Service {
    public static final String ACTION_START = "com.termux.app.LOCATION_START";
    public static final String ACTION_STOP = "com.termux.app.LOCATION_STOP";
    public static final String ACTION_SAMPLE = "com.termux.app.LOCATION_SAMPLE";
    public static final String EXTRA_MIN_TIME_MS = "min_time_ms";
    public static final String EXTRA_MIN_DISTANCE_M = "min_distance_m";

    public static final String PREFS = "jawnos";
    public static final String KEY_LOCATION_ON = "location_on";
    public static final String KEY_LOCATION_MIN_TIME = "location_min_time_ms";
    public static final String KEY_LOCATION_MIN_DISTANCE = "location_min_distance_m";
    public static final long DEFAULT_MIN_TIME_MS = 4 * 60 * 1000L;
    public static final float DEFAULT_MIN_DISTANCE_M = 0f;

    private static final String CHANNEL = "jawn_location";
    private static final int NOTIF_ID = 0x4A4C;

    /** The last JSON written, so the page and the file agree without a read. */
    private static volatile String latestJson = "";
    private static volatile boolean running = false;

    private LocationManager locationManager;
    private boolean updatesOn = false;
    private int satellitesUsed = 0;
    private int satellitesInView = 0;

    // ---------------------------------------------------------------- public

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LOCATION_ON, false);
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Start (or nudge) the service at the stored pace. */
    public static void start(Context context, long minTimeMs, float minDistanceM) {
        Intent intent = new Intent(context, JawnLocationService.class).setAction(ACTION_START);
        if (minTimeMs > 0) intent.putExtra(EXTRA_MIN_TIME_MS, minTimeMs);
        if (minDistanceM >= 0) intent.putExtra(EXTRA_MIN_DISTANCE_M, minDistanceM);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Start only when the person has already turned the service on. */
    public static void ensureStarted(Context context) {
        if (!isEnabled(context)) return;
        start(context,
                prefs(context).getLong(KEY_LOCATION_MIN_TIME, DEFAULT_MIN_TIME_MS),
                prefs(context).getFloat(KEY_LOCATION_MIN_DISTANCE, DEFAULT_MIN_DISTANCE_M));
    }

    public static void stop(Context context) {
        prefs(context).edit().putBoolean(KEY_LOCATION_ON, false).apply();
        cancelWake(context);
        clearReady(context);
        context.stopService(new Intent(context, JawnLocationService.class));
    }

    /** Ask for one fresh GPS fix now. The periodic listener keeps its own pace;
     *  this is the on-demand sample an appointment start/stop makes. */
    public static void sample(Context context) {
        Intent intent = new Intent(context, JawnLocationService.class).setAction(ACTION_SAMPLE);
        ContextCompat.startForegroundService(context, intent);
    }

    // ------------------------------------------------------- scheduled wake

    /**
     * Arm the next short wake. It is one-shot and re-armed by the receiver each
     * time, so the interval can change without a stale alarm. The interval is the
     * GPS pace, clamped to a sane range. An exempt app may use an exact alarm;
     * otherwise an allow-while-idle alarm still gets through Doze.
     */
    public static void scheduleWake(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pending = alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT);
        if (pending == null) return;
        long interval = prefs(context).getLong(KEY_LOCATION_MIN_TIME, DEFAULT_MIN_TIME_MS);
        if (interval < 60_000L) interval = 60_000L;
        if (interval > 30 * 60_000L) interval = 30 * 60_000L;
        long at = System.currentTimeMillis() + interval;
        try {
            if (Build.VERSION.SDK_INT >= 23 && wakeIsExempt(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pending);
            }
        } catch (Exception e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } catch (Exception ignored) {
            }
        }
    }

    public static void cancelWake(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pending = alarmIntent(context, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) am.cancel(pending);
    }

    /** The app side of the handshake: a fresh wake_ready tells the Perl tree it
     *  may release the permanent Termux wake lock. */
    public static void touchReady(Context context) {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president");
        if (!dir.exists() && !dir.mkdirs()) return;
        File file = new File(dir, "wake_ready");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public static void clearReady(Context context) {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president");
        File file = new File(dir, "wake_ready");
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static PendingIntent alarmIntent(Context context, int flags) {
        Intent intent = new Intent(context, JawnScheduledWakeReceiver.class)
                .setAction(JawnScheduledWakeReceiver.ACTION_WAKE);
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    private static boolean wakeIsExempt(Context context) {
        if (Build.VERSION.SDK_INT < 23) return false;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** The latest fix as JSON, or "" when the service has never had one. */
    public static String latest() {
        String memory = latestJson;
        if (memory != null && !memory.isEmpty()) return memory;
        File file = fixFile();
        if (file == null || !file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 65536)];
            int read = in.read(buffer);
            if (read <= 0) return "";
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** A small JSON status for the page and for troubleshooting. */
    public static String status(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("running", running);
            out.put("enabled", isEnabled(context));
            out.put("permission", hasPermission(context));
            out.put("provider", "gps");
            LocationManager lm = (LocationManager) context.getSystemService(LOCATION_SERVICE);
            out.put("gps_enabled", lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER));
            out.put("min_time_ms", prefs(context).getLong(KEY_LOCATION_MIN_TIME, DEFAULT_MIN_TIME_MS));
            out.put("min_distance_m", prefs(context).getFloat(KEY_LOCATION_MIN_DISTANCE, DEFAULT_MIN_DISTANCE_M));
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    // --------------------------------------------------------------- service

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            prefs(this).edit().putBoolean(KEY_LOCATION_ON, false).apply();
            stopUpdates();
            cancelWake(this);
            clearReady(this);
            publish("stopped", null, 0, 0);
            running = false;
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SAMPLE.equals(action)) {
            // A start/stop in the manager wants a place now, not the one from
            // the last beat. The periodic listener stays; this asks once more.
            prefs(this).edit().putBoolean(KEY_LOCATION_ON, true).apply();
            running = true;
            startForegroundCompat("GPS sample");
            startUpdates();
            requestFreshFix();
            scheduleWake(this);
            touchReady(this);
            return START_STICKY;
        }

        // A null intent is Android restarting the service; the stored choice is
        // the authority, exactly like the tailnet service.
        boolean wanted = ACTION_START.equals(action)
                || (intent == null && isEnabled(this));
        if (!wanted) {
            stopSelf();
            return START_NOT_STICKY;
        }

        prefs(this).edit().putBoolean(KEY_LOCATION_ON, true).apply();
        if (intent != null) {
            long minTime = intent.getLongExtra(EXTRA_MIN_TIME_MS, -1);
            float minDistance = intent.getFloatExtra(EXTRA_MIN_DISTANCE_M, -1);
            SharedPreferences.Editor edit = prefs(this).edit();
            if (minTime > 0) edit.putLong(KEY_LOCATION_MIN_TIME, minTime);
            if (minDistance >= 0) edit.putFloat(KEY_LOCATION_MIN_DISTANCE, minDistance);
            edit.apply();
        }

        running = true;
        startForegroundCompat("GPS starting");
        startUpdates();
        scheduleWake(this);
        touchReady(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopUpdates();
        publish("stopped", null, 0, 0);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- updates

    private void startUpdates() {
        if (locationManager == null) {
            publish("unavailable", null, 0, 0);
            return;
        }
        if (!hasPermission(this)) {
            publish("permission", null, 0, 0);
            updateNotification("Location permission needed");
            return;
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            publish("disabled", null, 0, 0);
            updateNotification("GPS is off");
            return;
        }
        if (updatesOn) return;

        long minTime = prefs(this).getLong(KEY_LOCATION_MIN_TIME, DEFAULT_MIN_TIME_MS);
        float minDistance = prefs(this).getFloat(KEY_LOCATION_MIN_DISTANCE, DEFAULT_MIN_DISTANCE_M);
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) publish("fix", last, satellitesUsed, satellitesInView);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, minDistance,
                    locationListener, Looper.getMainLooper());
            updatesOn = true;
            if (last == null) {
                publish("searching", null, 0, 0);
                updateNotification("Waiting for a GPS fix");
            }
        } catch (SecurityException e) {
            publish("permission", null, 0, 0);
            updateNotification("Location permission needed");
            return;
        } catch (Exception e) {
            publish("error", null, 0, 0);
            updateNotification("GPS error");
            return;
        }

        		try {
        			if (Build.VERSION.SDK_INT >= 24) {
        				locationManager.registerGnssStatusCallback(gnssCallback,
        						new Handler(Looper.getMainLooper()));
        			}
        		} catch (Exception ignored) {
        			// satellites are a nicety; the fix itself is what matters
        		}
        		// A cold start otherwise waits for the first periodic slot, which can be
        		// the full navigation interval. Ask once, now, so a fix is ready before
        		// the first appointment start needs it.
        		requestFreshFix();
        	}

    private void stopUpdates() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {
            }
            try {
                if (Build.VERSION.SDK_INT >= 24) locationManager.unregisterGnssStatusCallback(gnssCallback);
            } catch (Exception ignored) {
            }
        }
        updatesOn = false;
    }

    /** One fresh fix for an on-demand sample. On API 30+ this is a real current
     *  location request; older builds use a single update, which may hand back
     *  the provider's last known fix until a new one arrives. The page checks the
     *  fix's timestamp, so a cached answer cannot be mistaken for a fresh one. */
    private void requestFreshFix() {
        if (locationManager == null || !hasPermission(this)) return;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, getMainExecutor(),
                        new java.util.function.Consumer<Location>() {
                            @Override
                            public void accept(Location location) {
                                if (location != null
                                        && LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                                    publish("fix", location, satellitesUsed, satellitesInView);
                                }
                            }
                        });
            } else {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener,
                        Looper.getMainLooper());
            }
        } catch (Exception ignored) {
            // no fix is a normal answer indoors; the page falls back or records none
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;
            // A provider other than gps must never be published as the place.
            if (!LocationManager.GPS_PROVIDER.equals(location.getProvider())) return;
            publish("fix", location, satellitesUsed, satellitesInView);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) startUpdates();
        }

        @Override
        public void onProviderDisabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) publish("disabled", null, 0, 0);
        }
    };

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            if (status == null) return;
            int inView = status.getSatelliteCount();
            int used = 0;
            for (int i = 0; i < inView; i++) {
                if (status.usedInFix(i)) used++;
            }
            satellitesInView = inView;
            satellitesUsed = used;
        }
    };

    // ----------------------------------------------------------------- output

    private void publish(String status, Location location, int satsUsed, int satsInView) {
        JSONObject out = new JSONObject();
        try {
            out.put("provider", "gps");
            out.put("status", status);
            out.put("sampled", System.currentTimeMillis());
            out.put("source", "jawn-location-service");
            if (location != null) {
                long age = 0;
                try {
                    age = Math.max(0L,
                            (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L);
                } catch (Exception ignored) {
                }
                out.put("latitude", location.getLatitude());
                out.put("longitude", location.getLongitude());
                if (location.hasAltitude()) out.put("altitude", location.getAltitude());
                if (location.hasAccuracy() && !Float.isNaN(location.getAccuracy())) {
                    out.put("accuracy", round(location.getAccuracy(), 2));
                }
                if (location.hasSpeed() && !Float.isNaN(location.getSpeed())) {
                    out.put("speed", round(location.getSpeed(), 2));
                }
                if (location.hasBearing() && !Float.isNaN(location.getBearing())) {
                    out.put("bearing", round(location.getBearing(), 1));
                }
                out.put("time", location.getTime());
                out.put("elapsedMs", age);
            }
            if (satsUsed > 0) out.put("satellites", satsUsed);
            if (satsInView > 0) out.put("satellites_in_view", satsInView);
        } catch (Exception ignored) {
            return;
        }
        write(out.toString());
        if ("fix".equals(status)) {
            String accuracy = out.optDouble("accuracy", -1) >= 0
                    ? Math.round(out.optDouble("accuracy")) + " m" : "a fix";
            updateNotification("GPS " + accuracy);
        }
    }

    private static double round(float value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private void write(String json) {
        latestJson = json;
        File file = fixFile();
        if (file == null) return;
        File temp = new File(file.getParentFile(), "location.json.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            if (!temp.renameTo(file)) {
                try (FileOutputStream direct = new FileOutputStream(file)) {
                    direct.write(json.getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (Exception ignored) {
            // A full disk must not take the service down.
        }
    }

    private static File fixFile() {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, "location.json");
    }

    // ---------------------------------------------------------- notification

    	private void startForegroundCompat(String text) {
    		Notification notification = notification(text);
    		if (Build.VERSION.SDK_INT >= 29 && hasPermission(this)) {
    			startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    		} else {
    			startForeground(NOTIF_ID, notification);
    		}
    	}

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, JawnDashboardActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, JawnLocationService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.jawn_logo)
                .setContentTitle("JawnOS GPS")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(0, "Stop", stopIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, notification(text));
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "JawnOS GPS", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
