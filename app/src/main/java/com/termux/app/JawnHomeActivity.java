package com.termux.app;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * JawnOS Home: a native HOME screen, with no WebView in it anywhere.
 *
 * <p>The app grid is read from PackageManager (MAIN/LAUNCHER), the dock
 * resolves Phone, Messages, Browser and Camera through their default handlers
 * (a dock slot disappears when nothing on the device takes the intent), and
 * search filters the grid by label or package.
 *
 * <p>Two pieces of data come from outside the activity, both off the main
 * thread: package labels/icons, and the clothesline and desk colour from the
 * house's own database. When the house is down the home still draws its grid.
 *
 * <p>Home is deliberately hard to strand: back does nothing, and an uncaught
 * main-thread crash schedules a restart (at most a handful in a minute) instead
 * of leaving a blank task.
 */
public class JawnHomeActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final String TAG = "JawnHome";

    private static final String PREFS = "jawn_home";
    private static final String KEY_ROLE_DISMISSED = "role_prompt_dismissed";
    private static final String KEY_CRASH_COUNT = "crash_count";
    private static final String KEY_CRASH_AT = "crash_at";

    private static final int REQ_ROLE_HOME = 0x484f;
    private static final int CRASH_ALARM_REQUEST = 0x4a17;
    private static final long CRASH_WINDOW_MS = 60L * 1000L;
    private static final int CRASH_LIMIT = 3;
    private static final long HEALTHY_MS = 60L * 1000L;
    private static final long JAWN_APPS_REFRESH_MS = 60L * 1000L;
    private static final int JAWN_APPS_LIMIT = 40;

    private static final int GROUND = 0xFF101010;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int DIM = 0xFF8E8E8E;

    /**
     * The house serves a self-signed wildcard certificate and this client has
     * no other way to learn its chain. Only the manager URL built by
     * JawnLauncher is ever opened with this context; the browse surface keeps
     * its own strict TLS (docs/internet-contract.md, security invariants).
     */
    private static final TrustManager[] MANAGER_TRUST = new TrustManager[] {
        new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }
    };

    private static volatile boolean sAlive;
    private static boolean sCrashHandlerInstalled;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JawnHomeApps.AppEntry> allApps = new ArrayList<>();
    private final List<JawnHomeApps.AppEntry> shownApps = new ArrayList<>();

    private GridView appsGrid;
    private EditText search;
    private LinearLayout dock;
    private TextView emptyView;
    private TextView clockView;
    private TextView dateView;
    private LinearLayout roleBanner;
    private TextView roleText;
    private TextView roleAction;
    private LinearLayout jawnosSection;
    private LinearLayout jawnosRow;
    private LinearLayout runningSection;
    private LinearLayout runningRow;
    private TextView runningLabel;
    private LinearLayout categoriesRow;

    private static final String CATEGORY_PREFS = "jawn_home_categories";
    private static final String[] CATEGORIES =
        { "All", "Communication", "Games", "System", "Accessories", "Other" };
    private String selectedCategory = "All";
    private final Map<String, TextView> categoryChips = new LinkedHashMap<>();

    private int themeInk = TEXT;
    private int themeMuted = DIM;
    private int themeCard = 0xFF1B1B1B;
    private String themeApplied = "";
    private NfcAdapter nfcAdapter;
    private boolean statusInFlight;
    private static final long STATUS_REFRESH_MS = 10000L;
    private List<JawnAppEntry> lastStartApps;
    private List<RunningEntry> lastRunning;
    // The clothesline is polled every ten seconds but its picture changes
    // only when the icon set or a main image does, so keep each for a minute.
    private static final long RUNNING_ICON_REFRESH_MS = 60L * 1000L;
    private final Map<String, Bitmap> runningIcons = new HashMap<>();
    private final Map<String, Long> runningIconAt = new HashMap<>();

    private HomeAdapter adapter;

    private boolean appsLoaded;
    private boolean roleBannerUsesRoleManager;
    private boolean jawnAppsInFlight;
    private static final int WIDGET_HOST_ID = 0x4a57;
    private static final int REQ_BIND_WIDGET = 7311;
    private static final String WIDGET_PREFS = "jawn_home_widgets";
    private static final String WIDGET_KEY = "clockface_id";

    private AppWidgetHost widgetHost;
    private int clockWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    private long jawnAppsFetchedAt;
    private int jawnAppsRetries;
    private boolean packageReceiverRegistered;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            refreshClock();
        }
    };

    /** The clothesline and the theme are live: poll them while Home is on screen. */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            loadHomeStatus();
            ui.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    private final Runnable clearCrashCount = new Runnable() {
        @Override
        public void run() {
            prefs().edit().remove(KEY_CRASH_COUNT).remove(KEY_CRASH_AT).apply();
        }
    };

    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An install, uninstall or update while Home is in front.
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        sAlive = true;
        super.onCreate(state);
        installCrashHandler(getApplicationContext());

        setTheme(R.style.Theme_Termux_JawnDashboard);
        Window window = getWindow();
        window.setStatusBarColor(GROUND);
        window.setNavigationBarColor(GROUND);

        setContentView(R.layout.jawn_home);

        clockView = (TextView) findViewById(R.id.home_clock);
        dateView = (TextView) findViewById(R.id.home_date);
        roleBanner = (LinearLayout) findViewById(R.id.home_role_banner);
        roleText = (TextView) findViewById(R.id.home_role_text);
        roleAction = (TextView) findViewById(R.id.home_role_action);
        emptyView = (TextView) findViewById(R.id.home_empty);
        appsGrid = (GridView) findViewById(R.id.home_apps);
        search = (EditText) findViewById(R.id.home_search);
        dock = (LinearLayout) findViewById(R.id.home_dock);
        jawnosSection = (LinearLayout) findViewById(R.id.home_jawnos_section);
        jawnosRow = (LinearLayout) findViewById(R.id.home_jawnos_row);
        runningSection = (LinearLayout) findViewById(R.id.home_running_section);
        runningRow = (LinearLayout) findViewById(R.id.home_running_row);
        runningLabel = (TextView) findViewById(R.id.home_running_label);
        categoriesRow = (LinearLayout) findViewById(R.id.home_categories_row);
        buildCategories();

        setupPrinterWidget();

        findViewById(R.id.home_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHomeSettings();
            }
        });

        adapter = new HomeAdapter();
        appsGrid.setAdapter(adapter);
        appsGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shownApps.size()) return;
                JawnHomeApps.AppEntry entry = shownApps.get(position);
                if (!JawnHomeApps.launch(JawnHomeActivity.this, entry)) {
                    Toast.makeText(JawnHomeActivity.this,
                        "Could not open " + entry.label, Toast.LENGTH_SHORT).show();
                }
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                String query = v == null || v.getText() == null ? "" : v.getText().toString().trim();
                hideKeyboard();
                if (query.length() > 0) { openJawnSearch(query); }
                return true;
            }
        });
        // The search field must not steal focus (and the keyboard) on launch.
        search.clearFocus();

        roleAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestHomeRole();
            }
        });
        findViewById(R.id.home_role_dismiss).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs().edit().putBoolean(KEY_ROLE_DISMISSED, true).apply();
                roleBanner.setVisibility(View.GONE);
            }
        });

        buildDock();
        applyThemeDefaults();
        loadApps();
        loadJawnApps(false);
        loadHomeStatus();
        refreshClock();
        updateRoleBanner();

        // A run that survives this long is healthy; let the next crash be treated
        // as the first one again (a crash loop inside the window stays guarded).
        ui.postDelayed(clearCrashCount, HEALTHY_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sAlive = true;
        // Coming back from Recents or another app restores the search field's
        // focus and with it the keyboard. Home is a launcher: keep both down
        // until the person taps the field themselves.
        if (search != null) { search.clearFocus(); }
        hideKeyboard();
        nfcResume();
        registerPackageReceiver();
        refreshClock();
        updateRoleBanner();
        // Jawn may have been started (or finished booting) while Home was
        // behind another app; the throttle keeps this cheap.
        loadJawnApps(false);
        loadHomeStatus();
        ui.removeCallbacks(statusTick);
        ui.postDelayed(statusTick, STATUS_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfcPause();
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(statusTick);
        unregisterPackageReceiver();
    }

    @Override
    protected void onDestroy() {
        if (widgetHost != null) {
            try { widgetHost.stopListening(); } catch (Throwable ignored) { }
        }
        sAlive = false;
        ui.removeCallbacksAndMessages(null);
        unregisterPackageReceiver();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateRoleBanner();
    }

    /**
     * HOME has nowhere to go back to. Finishing here would hand the person a
     * blank task, so back is swallowed; Home settings and the app grid are the
     * ways out.
     */
    @Override
    public void onBackPressed() {
        // deliberately empty
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE_HOME) updateRoleBanner();
        if (requestCode == REQ_BIND_WIDGET && resultCode == RESULT_OK) {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            manager.bindAppWidgetIdIfAllowed(clockWidgetId,
                new ComponentName(this, JawnPrinterWidget.class));
            getSharedPreferences(WIDGET_PREFS, MODE_PRIVATE)
                .edit().putInt(WIDGET_KEY, clockWidgetId).apply();
            FrameLayout slot = (FrameLayout) findViewById(R.id.home_widget_slot);
            if (slot != null) showPrinterWidget(slot, manager);
        }
    }

    // ---------------------------------------------------------------- clock

    private void refreshClock() {
        long now = System.currentTimeMillis();
        Locale locale = Locale.getDefault();
        String timePattern = DateFormat.getBestDateTimePattern(locale, "hm");
        String datePattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd");
        clockView.setText(DateFormat.format(timePattern, now));
        dateView.setText(DateFormat.format(datePattern, now));

        // Wake on the next minute boundary, not on a free-running second timer.
        ui.removeCallbacks(clockTick);
        ui.postDelayed(clockTick, 60000L - (now % 60000L) + 250L);
    }

    // ----------------------------------------------------------------- apps

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<JawnHomeApps.AppEntry> loaded = JawnHomeApps.load(pm);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        allApps.clear();
                        allApps.addAll(loaded);
                        appsLoaded = true;
                        applyFilter(search.getText() == null ? "" : search.getText().toString());
                    }
                });
            }
        }, "jawn-home-apps").start();
    }

    // ------------------------------------------------------- printer widget

    /**
     * The clockface card, hosted here rather than left to the system
     * launcher: JawnOS Home is a plain activity, and Android widgets only
     * appear inside an AppWidgetHost. The provider is JawnOS's own, bound
     * without a picker (the home role holds the bind permission); if the
     * system insists on consent, its own binder screen is shown once.
     */
    private void setupPrinterWidget() {
        FrameLayout slot = (FrameLayout) findViewById(R.id.home_widget_slot);
        if (slot == null) return;
        // The card is a tablet's luxury: on a phone the header clock already
        // tells the time, and the 210dp card left the app grid one icon high.
        // The tablet keeps the card exactly as it was.
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            slot.setVisibility(View.GONE);
            return;
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, WIDGET_HOST_ID);
        widgetHost.startListening();
        SharedPreferences prefs = getSharedPreferences(WIDGET_PREFS, MODE_PRIVATE);
        clockWidgetId = prefs.getInt(WIDGET_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (clockWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
                || manager.getAppWidgetInfo(clockWidgetId) == null) {
            clockWidgetId = widgetHost.allocateAppWidgetId();
            if (!manager.bindAppWidgetIdIfAllowed(clockWidgetId,
                    new ComponentName(this, JawnPrinterWidget.class))) {
                try {
                    Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
                    bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, clockWidgetId);
                    bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
                        new ComponentName(this, JawnPrinterWidget.class));
                    startActivityForResult(bind, REQ_BIND_WIDGET);
                } catch (Throwable ignored) {
                    // no binder screen on this device: no card
                }
                return;
            }
            prefs.edit().putInt(WIDGET_KEY, clockWidgetId).apply();
        }
        showPrinterWidget(slot, manager);
    }

    private void showPrinterWidget(FrameLayout slot, AppWidgetManager manager) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(clockWidgetId);
        if (info == null) return;
        AppWidgetHostView view = widgetHost.createView(this, clockWidgetId, info);
        view.setAppWidget(clockWidgetId, info);
        slot.removeAllViews();
        slot.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        slot.setVisibility(View.VISIBLE);
        Intent update = new Intent(this, JawnPrinterWidget.class);
        update.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        update.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { clockWidgetId });
        sendBroadcast(update);
    }

    // ------------------------------------------- clothesline and live theme

    private void applyThemeDefaults() {
        applyTheme("#101010", "#f2f2f2", "#8e8e8e", true);
    }

    private int parseColour(String value, int fallback) {
        if (value == null) return fallback;
        String s = value.trim();
        if (s.isEmpty()) return fallback;
        if (!s.startsWith("#")) s = "#" + s;
        try {
            return Color.parseColor(s);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private boolean isDarkColour(int colour) {
        double lum = (0.2126 * Color.red(colour) + 0.7152 * Color.green(colour)
            + 0.0722 * Color.blue(colour)) / 255.0;
        return lum < 0.5;
    }

    private void tintBackground(View view, int colour) {
        if (view == null) return;
        Drawable background = view.getBackground();
        if (background == null) return;
        background = background.mutate();
        background.setTint(colour);
        view.setBackground(background);
    }

    /**
     * The launcher wears the manager's theme: the same background, black ink on
     * a light desk and white/light-grey on a dark one. The manager writes its
     * colour to a setting; home_status reports it, and the poll re-applies it
     * when an app changes the theme.
     */
    private void applyTheme(String bgHex, String inkHex, String mutedHex, boolean dark) {
        int bg = parseColour(bgHex, dark ? GROUND : 0xFFF7F7F7);
        int ink = parseColour(inkHex, dark ? TEXT : 0xFF111111);
        int muted = parseColour(mutedHex, dark ? DIM : 0xFF4A4A4A);
        int card = dark ? 0xFF1B1B1B : 0xFFFFFFFF;
        String key = bgHex + "|" + inkHex + "|" + mutedHex + "|" + dark;
        if (key.equals(themeApplied)) return;
        themeApplied = key;
        themeInk = ink;
        themeMuted = muted;
        themeCard = card;

        View root = findViewById(R.id.home_root);
        if (root != null) root.setBackgroundColor(bg);
        if (clockView != null) clockView.setTextColor(ink);
        if (dateView != null) dateView.setTextColor(muted);
        if (search != null) {
            search.setTextColor(ink);
            search.setHintTextColor(muted);
            tintBackground(search, dark ? 0xFF1B1B1B : 0xFFE9E9E9);
        }
        TextView jawnosLabel = (TextView) findViewById(R.id.home_jawnos_label);
        if (jawnosLabel != null) jawnosLabel.setTextColor(muted);
        if (runningLabel != null) runningLabel.setTextColor(muted);
        if (roleText != null) roleText.setTextColor(ink);
        tintBackground(findViewById(R.id.home_dock), dark ? 0xFF101010 : 0xFFE8E8E8);
        Window window = getWindow();
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);
        // Dark system-bar icons on a light desk, light icons on a dark one, so
        // the clock and the navigation buttons stay readable either way.
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);

        if (adapter != null) adapter.notifyDataSetChanged();
        buildCategories();
        buildDock();
        if (lastStartApps != null) renderJawnApps(lastStartApps);
        if (lastRunning != null) renderRunning(lastRunning);
    }

    // ------------------------------------------------- the system app shelves

    private void buildCategories() {
        if (categoriesRow == null) return;
        categoriesRow.removeAllViews();
        categoryChips.clear();
        int stroke = isDarkColour(themeCard) ? 0x33FFFFFF : 0x33000000;
        for (final String name : CATEGORIES) {
            TextView chip = new TextView(this);
            chip.setText(name);
            chip.setTextSize(12);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            boolean selected = name.equals(selectedCategory);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(15));
            shape.setColor(selected ? themeInk : themeCard);
            if (!selected) shape.setStroke(dp(1), stroke);
            chip.setBackground(shape);
            chip.setTextColor(selected
                ? (isDarkColour(themeInk) ? 0xFFFFFFFF : 0xFF111111)
                : themeMuted);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // The System shelf is a person's own grouping, so its chip
                    // would otherwise filter on a choice rather than on what IS
                    // a system app. It opens the box of every ROM app instead.
                    if ("System".equals(name)) {
                        showSystemAppsDialog();
                        return;
                    }
                    selectedCategory = name;
                    buildCategories();
                    applyFilter(search == null ? "" : search.getText().toString());
                }
            });
            chip.setOnDragListener(categoryDragListener(name, chip));
            categoriesRow.addView(chip);
            categoryChips.put(name, chip);
        }
    }

    /** A shelf accepts a dragged app and remembers which one landed on it. */
    private View.OnDragListener categoryDragListener(final String name, final TextView chip) {
        return new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent event) {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return event.getClipDescription() != null
                            && event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
                    case DragEvent.ACTION_DRAG_ENTERED:
                        chip.setAlpha(0.55f);
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                        chip.setAlpha(1f);
                        return true;
                    case DragEvent.ACTION_DROP:
                        chip.setAlpha(1f);
                        if (event.getClipData() != null && event.getClipData().getItemCount() > 0) {
                            CharSequence pkg = event.getClipData().getItemAt(0).getText();
                            if (pkg != null && pkg.length() > 0) {
                                assignCategory(pkg.toString(), name);
                                Toast.makeText(JawnHomeActivity.this,
                                    "Moved to " + name, Toast.LENGTH_SHORT).show();
                            }
                        }
                        return true;
                    case DragEvent.ACTION_DRAG_ENDED:
                        chip.setAlpha(1f);
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

    /**
     * The System apps box. The System chip used to filter the grid by a shelf a
     * person may have emptied by moving every tile elsewhere, which read as "no
     * apps". The box lists every ROM package with a launcher icon, whatever
     * shelf it starts on.
     */
    private void showSystemAppsDialog() {
        final List<JawnHomeApps.AppEntry> system = new ArrayList<>();
        for (JawnHomeApps.AppEntry entry : allApps) {
            if (entry.system) system.add(entry);
        }
        if (system.isEmpty()) {
            Toast.makeText(this, "No system apps on this device.", Toast.LENGTH_SHORT).show();
            return;
        }
        final SystemAppAdapter adapter = new SystemAppAdapter(system);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("System apps")
            .setAdapter(adapter, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    JawnHomeApps.AppEntry entry = system.get(which);
                    if (!JawnHomeApps.launch(JawnHomeActivity.this, entry)) {
                        Toast.makeText(JawnHomeActivity.this,
                            "Could not open " + entry.label, Toast.LENGTH_SHORT).show();
                    }
                    d.dismiss();
                }
            })
            .setNegativeButton("Close", null)
            .create();
        dialog.show();
    }

    /** One row in the System apps box: the app's icon and its name. */
    private final class SystemAppAdapter extends BaseAdapter {
        private final List<JawnHomeApps.AppEntry> entries;

        SystemAppAdapter(List<JawnHomeApps.AppEntry> entries) {
            this.entries = entries;
        }

        @Override
        public int getCount() { return entries.size(); }

        @Override
        public Object getItem(int position) { return entries.get(position); }

        @Override
        public long getItemId(int position) { return entries.get(position).packageName.hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof View[]) {
                row = (LinearLayout) convertView;
                View[] parts = (View[]) convertView.getTag();
                icon = (ImageView) parts[0];
                label = (TextView) parts[1];
            } else {
                row = new LinearLayout(JawnHomeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(9), dp(20), dp(9));

                icon = new ImageView(JawnHomeActivity.this);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(icon);

                label = new TextView(JawnHomeActivity.this);
                label.setTextColor(themeInk);
                label.setTextSize(16);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                lp.leftMargin = dp(16);
                row.addView(label, lp);

                row.setTag(new View[] { icon, label });
            }
            JawnHomeApps.AppEntry entry = entries.get(position);
            if (entry.icon != null) icon.setImageDrawable(entry.icon);
            else icon.setImageResource(android.R.drawable.sym_def_app_icon);
            label.setText(entry.label);
            return row;
        }
    }

    private SharedPreferences categoryPrefs() {
        return getSharedPreferences(CATEGORY_PREFS, MODE_PRIVATE);
    }

    private String categoryFor(String packageName, String fallback) {
        String chosen = categoryPrefs().getString(packageName, "");
        return (chosen == null || chosen.isEmpty()) ? fallback : chosen;
    }

    private void assignCategory(String packageName, String category) {
        if (category == null || category.equals("All")) return;
        categoryPrefs().edit().putString(packageName, category).apply();
        applyFilter(search == null ? "" : search.getText().toString());
    }

    // ------------------------------------------- the clothesline (running apps)

    private static final class RunningEntry {
        final String app;
        final String title;
        final String colour;
        final Bitmap icon;

        RunningEntry(String app, String title, String colour, Bitmap icon) {
            this.app = app;
            this.title = title;
            this.colour = colour;
            this.icon = icon;
        }
    }

    /**
     * The clothesline and the theme, read straight from the house's database.
     *
     * <p>Both are the person's own view of this machine, and the manager's
     * session cookie is HttpOnly, so an HTTP poll from here cannot see the app
     * gate. The app shares Termux's UID, so the clothesline and the desk colour
     * are read straight from the house's settings table. Only the pictures come
     * over HTTP, from the public /apps/icon route.
     */
    private void loadHomeStatus() {
        if (statusInFlight) return;
        statusInFlight = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<RunningEntry> running = new ArrayList<>();
                final String base = managerOrigin();
                String clothesline = "";
                String background = "";
                java.io.File dbFile = new java.io.File(
                    JawnLauncher.getJawnDirPath(JawnHomeActivity.this), "database/jawnos.db");
                SQLiteDatabase db = null;
                Cursor cursor = null;
                try {
                    if (dbFile.isFile()) {
                        db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                            SQLiteDatabase.OPEN_READONLY);
                        cursor = db.rawQuery(
                            "select value from settings where app = '__president'"
                                + " and setting = 'clothesline' order by server_time desc limit 1", null);
                        if (cursor.moveToFirst()) clothesline = cursor.getString(0);
                        cursor.close();
                        cursor = db.rawQuery(
                            "select value from settings where app = 'misc'"
                                + " and setting = 'manager_background_colour'"
                                + " order by server_time desc limit 1", null);
                        if (cursor.moveToFirst()) background = cursor.getString(0);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "home status read failed", t);
                } finally {
                    if (cursor != null) { try { cursor.close(); } catch (Throwable ignored) { } }
                    if (db != null) { try { db.close(); } catch (Throwable ignored) { } }
                }

                try {
                    if (clothesline != null && !clothesline.trim().isEmpty()) {
                        JSONArray arr = new JSONArray(clothesline);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.optJSONObject(i);
                            if (item == null) continue;
                            String app = item.optString("app", "").trim();
                            if (app.isEmpty()) app = item.optString("name", "").trim();
                            if (app.isEmpty()) continue;
                            String title = item.optString("formatted_name", app);
                            Bitmap icon = base.isEmpty() ? null : runningIcon(base, app);
                            running.add(new RunningEntry(app, title,
                                item.optString("colour", ""), icon));
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "clothesline parse failed", t);
                }

                final String bg = background;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        statusInFlight = false;
                        if (isFinishing() || isDestroyed()) return;
                        int colour = parseColour(bg, -1);
                        if (colour != -1) {
                            int ink = contrastInk(colour);
                            applyTheme(String.format("#%06X", colour & 0xFFFFFF),
                                ink == 0xFFFFFFFF ? "#ffffff" : "#000000",
                                ink == 0xFFFFFFFF ? "#c9c9c9" : "#3a3a3a",
                                ink == 0xFFFFFFFF);
                        }
                        renderRunning(running);
                    }
                });
            }
        }, "jawn-home-status").start();
    }

    /** Perl's subs::contrast_ink, so the two surfaces agree on light vs dark. */
    private int contrastInk(int colour) {
        double[] channel = { Color.red(colour) / 255.0, Color.green(colour) / 255.0,
            Color.blue(colour) / 255.0 };
        double[] weight = { 0.2126, 0.7152, 0.0722 };
        double lum = 0;
        for (int i = 0; i < 3; i++) {
            double c = channel[i];
            c = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
            lum += c * weight[i];
        }
        return lum < 0.179 ? 0xFFFFFFFF : 0xFF000000;
    }

    private void renderRunning(List<RunningEntry> items) {
        if (runningSection == null || runningRow == null) return;
        lastRunning = items;
        runningRow.removeAllViews();
        if (items == null || items.isEmpty()) {
            runningSection.setVisibility(View.GONE);
            return;
        }
        for (final RunningEntry entry : items) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(4), dp(6), dp(4), dp(6));
            item.setContentDescription(entry.title + ", running");
            int selector = selectableBackground();
            if (selector != 0) item.setBackgroundResource(selector);

            if (entry.icon != null) {
                ImageView icon = new ImageView(this);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageDrawable(new BitmapDrawable(getResources(), entry.icon));
                item.addView(icon);
            } else {
                // No icon could be fetched (the house is down, or a person's
                // own app has no main image): its clothesline colour is the
                // tile, with the app's initial in contrasting ink.
                TextView tile = new TextView(this);
                tile.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
                tile.setGravity(Gravity.CENTER);
                int colour = parseColour(entry.colour, themeCard);
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.OVAL);
                shape.setColor(colour);
                tile.setBackground(shape);
                tile.setText(entry.title.isEmpty() ? "?"
                    : entry.title.substring(0, 1).toUpperCase());
                tile.setTextSize(18);
                tile.setTextColor(isDarkColour(colour) ? 0xFFFFFFFF : 0xFF111111);
                item.addView(tile);
            }

            TextView label = new TextView(this);
            label.setText(entry.title);
            label.setTextColor(themeInk);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68),
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            label.setLayoutParams(lp);
            item.addView(label);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent open = new Intent(JawnHomeActivity.this, JawnAppActivity.class);
                        open.putExtra(JawnAppActivity.EXTRA_APP, entry.app);
                        // One task per app: the Recents card then belongs to the
                        // app, not to Home, and two open apps are two cards.
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(open);
                    } catch (Throwable t) {
                        Toast.makeText(JawnHomeActivity.this,
                            "Could not open " + entry.title, Toast.LENGTH_SHORT).show();
                    }
                }
            });

            runningRow.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        runningSection.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------------------- JawnOS row

    /**
     * The JawnOS app row, above the installed-app grid: the manager's own
     * apps, so Marker, Music, Gallery and the rest are one tap from Home.
     *
     * <p>{@code /apps/list} is public and carries only app names, titles and
     * icon paths, so this works before any session exists. The whole thing is
     * best-effort: on any failure the section simply stays hidden, and it
     * never delays or blocks {@link #loadApps()}.
     */
    private void loadJawnApps(boolean force) {
        if (jawnAppsInFlight) return;
        long now = System.currentTimeMillis();
        if (!force && now - jawnAppsFetchedAt < JAWN_APPS_REFRESH_MS) return;
        jawnAppsInFlight = true;
        jawnAppsFetchedAt = now;

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<JawnAppEntry> loaded = fetchJawnApps();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        jawnAppsInFlight = false;
                        if (isFinishing() || isDestroyed()) return;
                        // A failed fetch (the tree still booting, a refused
                        // connection) used to count as fresh for a minute and
                        // leave the row hidden. Try once more soon instead.
                        if ((loaded == null || loaded.isEmpty()) && jawnAppsRetries < 3) {
                            jawnAppsRetries++;
                            jawnAppsFetchedAt = 0;
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() { loadJawnApps(true); }
                            }, 3000L);
                        }
                        renderJawnApps(loaded);
                    }
                });
            }
        }, "jawn-home-jawnos-apps").start();
    }

    /** One JawnOS app: the app id, its title and (when it downloaded) its icon. */
    private static final class JawnAppEntry {
        final String app;
        final String title;
        final Bitmap icon;

        JawnAppEntry(String app, String title, Bitmap icon) {
            this.app = app;
            this.title = title;
            this.icon = icon;
        }
    }

    private List<JawnAppEntry> fetchJawnApps() {
        List<JawnAppEntry> entries = new ArrayList<>();
        String base = managerOrigin();
        if (base.isEmpty()) return entries;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/apps/start").openConnection();
            trustHouse(conn, base);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("Accept", "application/json");
            // The route is public, but a browser that has not signed in is
            // still met by the login gate in this build. Pass the manager's
            // cookie along when the WebView already holds it; with no cookie
            // the public answer still works.
            try {
                String cookie = CookieManager.getInstance().getCookie(base);
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
            } catch (Throwable ignored) {
                // no cookie store: the public read is still attempted
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return entries;

            StringBuilder body = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            } finally {
                reader.close();
            }

            JSONArray apps = new JSONObject(body.toString()).optJSONArray("apps");
            if (apps == null) return entries;
            for (int i = 0; i < apps.length() && entries.size() < JAWN_APPS_LIMIT; i++) {
                JSONObject item = apps.optJSONObject(i);
                if (item == null) continue;
                String app = item.optString("app", "").trim();
                if (app.isEmpty()) continue;
                String title = item.optString("title", "").trim();
                if (title.isEmpty()) title = app;
                String iconPath = item.optString("icon", "").trim();
                Bitmap icon = iconPath.isEmpty() ? null : fetchJawnIcon(base, iconPath);
                entries.add(new JawnAppEntry(app, title, icon));
            }
        } catch (Throwable t) {
            // Manager down or a bad answer: hide the row rather than show an
            // error where apps should be.
            return new ArrayList<>();
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
        return entries;
    }

    /** The server origin, with the manager path removed. */
    private String managerOrigin() {
        String manager = JawnLauncher.getManagerUrl(this, JawnLauncher.getJawnPort(this));
        if (manager.endsWith("/manager")) {
            manager = manager.substring(0, manager.length() - "/manager".length());
        }
        return manager;
    }

    /**
     * Trust the house's own certificate for this request only. An absolute
     * external icon URL is left on the platform's normal TLS (the row only
     * carries the manager's icon paths in practice).
     */
    private void trustHouse(HttpURLConnection conn, String urlText) {
        if (!(conn instanceof HttpsURLConnection)) return;
        String host;
        try {
            host = new URI(urlText).getHost();
        } catch (Throwable t) {
            return;
        }
        if (!isHouseHost(host)) return;
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, MANAGER_TRUST, new SecureRandom());
            ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
            ((HttpsURLConnection) conn).setHostnameVerifier((h, session) -> true);
        } catch (Throwable ignored) {
            // the platform chain is still the fallback
        }
    }

    private boolean isHouseHost(String host) {
        if (host == null) return false;
        if (host.equals("127.0.0.1") || host.equals("localhost")) return true;
        try {
            String managerHost = new URI(managerOrigin()).getHost();
            return managerHost != null && managerHost.equals(host);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The clothesline's icon through the JawnOS row's route: main_icon_maker's
     *  choice (a main image, else the active icon set), rasterised by the
     *  manager because BitmapFactory cannot decode the sets' SVG. */
    private Bitmap runningIcon(String base, String app) {
        long now = System.currentTimeMillis();
        Long at = runningIconAt.get(app);
        if (at != null && now - at < RUNNING_ICON_REFRESH_MS) {
            return runningIcons.get(app);
        }
        Bitmap icon = fetchJawnIcon(base, "/apps/icon?app=" + Uri.encode(app));
        runningIconAt.put(app, now);
        if (icon != null) runningIcons.put(app, icon);
        else runningIcons.remove(app);
        return icon;
    }

    private Bitmap fetchJawnIcon(String base, String iconPath) {
        HttpURLConnection conn = null;
        try {
            String urlText;
            if (iconPath.startsWith("http://") || iconPath.startsWith("https://")) {
                urlText = iconPath;
            } else {
                // Icon paths can contain spaces (e.g. "/images/make believe/…");
                // escape the bytes a URL cannot carry, keeping the separators.
                // A query (the /apps/icon?app=… route) is already escaped and
                // must not have its ? and = turned into path bytes.
                int q = iconPath.indexOf('?');
                String pathPart = q >= 0 ? iconPath.substring(0, q) : iconPath;
                String queryPart = q >= 0 ? iconPath.substring(q) : "";
                urlText = base + (!pathPart.startsWith("/") ? "/" : "")
                    + Uri.encode(Uri.decode(pathPart), "/") + queryPart;
            }
            conn = (HttpURLConnection) new URL(urlText).openConnection();
            trustHouse(conn, urlText);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2500);
            // An icon route is behind the manager's app gate until a session
            // exists; carry the WebView's cookie the same way /apps/list does.
            try {
                String cookie = CookieManager.getInstance().getCookie(base);
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
            } catch (Throwable ignored) {
                // no cookie store: the placeholder tile is the fallback
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            return uprightIcon(buf.toByteArray());
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /** A photo is stored in its own frame and carries an EXIF orientation tag
     *  instead; BitmapFactory ignores that, so a portrait picture showed up on
     *  its side in the row. Rotate (and mirror) it, then the tile is upright.
     *  Decoding is sampled down first: a launcher tile cannot show more than a
     *  couple of hundred pixels and a camera picture is several megapixels. */
    private Bitmap uprightIcon(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            ExifInterface exif = new ExifInterface(new BufferedInputStream(
                new ByteArrayInputStream(bytes)));
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            // no EXIF (a PNG, or a rasterised set glyph): the pixels are it
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 256
                && bounds.outHeight / (sample * 2) >= 256) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bitmap == null) return null;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90); break;
            default:
                return bitmap;
        }
        try {
            Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (upright != bitmap) bitmap.recycle();
            return upright;
        } catch (Throwable t) {
            return bitmap;
        }
    }

    private void renderJawnApps(List<JawnAppEntry> apps) {
        if (jawnosSection == null || jawnosRow == null) return;
        lastStartApps = apps;
        jawnosRow.removeAllViews();
        if (apps == null || apps.isEmpty()) {
            jawnosSection.setVisibility(View.GONE);
            return;
        }

        Drawable placeholder = null;
        try {
            placeholder = getPackageManager().getApplicationIcon(getPackageName());
        } catch (Throwable ignored) {
            // fall through to the generic tile
        }
        if (placeholder == null) {
            placeholder = getResources().getDrawable(android.R.drawable.sym_def_app_icon, getTheme());
        }

        for (final JawnAppEntry entry : apps) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(4), dp(6), dp(4), dp(6));
            item.setContentDescription(entry.title);
            int selector = selectableBackground();
            if (selector != 0) item.setBackgroundResource(selector);

            ImageView icon = new ImageView(this);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (entry.icon != null) {
                icon.setImageDrawable(new BitmapDrawable(getResources(), entry.icon));
            } else {
                icon.setImageDrawable(placeholder);
            }
            item.addView(icon);

            TextView label = new TextView(this);
            label.setText(entry.title);
            label.setTextColor(themeInk);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68),
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            label.setLayoutParams(lp);
            item.addView(label);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent open = new Intent(JawnHomeActivity.this, JawnAppActivity.class);
                        open.putExtra(JawnAppActivity.EXTRA_APP, entry.app);
                        // One task per app: the Recents card then belongs to the
                        // app, not to Home, and two open apps are two cards.
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(open);
                    } catch (Throwable t) {
                        Toast.makeText(JawnHomeActivity.this,
                            "Could not open " + entry.title, Toast.LENGTH_SHORT).show();
                    }
                }
            });

            jawnosRow.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        jawnosSection.setVisibility(View.VISIBLE);
    }

    private void applyFilter(String query) {
        shownApps.clear();
        String needle = query == null ? "" : query.trim();
        for (JawnHomeApps.AppEntry entry : allApps) {
            if (!"All".equals(selectedCategory)
                && !selectedCategory.equals(categoryFor(entry.packageName, entry.category))) {
                continue;
            }
            if (entry.matches(needle)) shownApps.add(entry);
        }
        if (adapter != null) adapter.notifyDataSetChanged();

        // Stay quiet while the first query is still running, then explain an
        // empty grid (no apps installed vs. no search match).
        if (appsLoaded && shownApps.isEmpty()) {
            emptyView.setText(allApps.isEmpty()
                ? "No apps found on this device."
                : (!"All".equals(selectedCategory)
                    ? "No apps on this shelf yet. Long-press an app and drop it here."
                    : (needle.length() > 0
                        ? "No system apps match. Press Enter to search JawnOS."
                        : "No apps match that search.")));
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void openAppInfo(JawnHomeApps.AppEntry entry) {
        try {
            Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + entry.packageName));
            startActivity(info);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app details for " + entry.label, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "app info failed", t);
        }
    }

    /** One grid cell: icon over a one-line label. */
    private final class HomeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownApps.size();
        }

        @Override
        public Object getItem(int position) {
            return shownApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shownApps.get(position).packageName.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout item;
            ImageView icon;
            TextView label;

            if (convertView instanceof LinearLayout
                && convertView.getTag() instanceof View[]) {
                item = (LinearLayout) convertView;
                View[] parts = (View[]) convertView.getTag();
                icon = (ImageView) parts[0];
                label = (TextView) parts[1];
            } else {
                item = new LinearLayout(JawnHomeActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setPadding(dp(4), dp(8), dp(4), dp(8));
                int selector = selectableBackground();
                if (selector != 0) item.setBackgroundResource(selector);

                icon = new ImageView(JawnHomeActivity.this);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                item.addView(icon);

                label = new TextView(JawnHomeActivity.this);
                label.setTextColor(themeInk);
                label.setTextSize(11);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(5);
                label.setLayoutParams(lp);
                item.addView(label);

                item.setTag(new View[] { icon, label });
            }

            final JawnHomeApps.AppEntry entry = shownApps.get(position);
            item.setLongClickable(true);
            // A long-clickable tile consumes a plain tap, so the GridView's
            // OnItemClickListener never saw one: every installed app was dead.
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!JawnHomeApps.launch(JawnHomeActivity.this, entry)) {
                        Toast.makeText(JawnHomeActivity.this,
                            "Could not open " + entry.label, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    ClipData data = ClipData.newPlainText("JawnOS app", entry.packageName);
                    View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                    try {
                        v.startDragAndDrop(data, shadow, entry.packageName, 0);
                    } catch (Throwable t) {
                        return false;
                    }
                    return true;
                }
            });
            if (entry.icon != null) {
                icon.setImageDrawable(entry.icon);
            } else {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            label.setText(entry.label);
            item.setContentDescription(entry.label);
            return item;
        }
    }

    // ----------------------------------------------------------------- dock

    private void buildDock() {
        dock.removeAllViews();
        PackageManager pm = getPackageManager();

        addDockItem(pm, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")), "Phone");
        addDockItem(pm, new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), "Messages");
        addDockItem(pm, new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), "Browser");
        addDockItem(pm, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera");
    }

    /**
     * Add one dock slot, or nothing when the device has no handler. Nothing is
     * named by package: the default handler the system reports is the one shown
     * (its own icon and label) and the one launched.
     */
    private void addDockItem(PackageManager pm, Intent intent, String fallbackLabel) {
        ResolveInfo ri = JawnHomeApps.resolve(pm, intent);
        if (ri == null) return;

        String label = fallbackLabel;
        Drawable icon = null;
        try {
            CharSequence resolved = ri.loadLabel(pm);
            if (resolved != null && resolved.toString().trim().length() > 0) {
                label = resolved.toString().trim();
            }
        } catch (Throwable ignored) {
            // keep the caller's fallback label
        }
        try {
            icon = ri.loadIcon(pm);
        } catch (Throwable ignored) {
            // no icon is better than no dock item
        }

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(4), dp(8), dp(4), dp(6));
        item.setContentDescription(label);
        int selector = selectableBackground();
        if (selector != 0) item.setBackgroundResource(selector);

        ImageView iconView = new ImageView(this);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (icon != null) iconView.setImageDrawable(icon);
        else iconView.setImageResource(android.R.drawable.sym_def_app_icon);
        item.addView(iconView);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(themeMuted);
        text.setTextSize(10);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        text.setLayoutParams(lp);
        item.addView(text);

        final Intent launch = new Intent(intent);
        if (ri.activityInfo != null) {
            launch.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        }
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchIntent(launch);
            }
        });

        dock.addView(item, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
    }

    private void launchIntent(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nothing on this device can open that.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "dock launch failed", t);
            Toast.makeText(this, "Could not open that app.", Toast.LENGTH_SHORT).show();
        }
    }

    // --------------------------------------------------------------------- nfc

    /**
     * A tag tapped on Home reaches the same route the manager's Web NFC shim
     * posts to, so a start/stop/command tag works from the launcher too. The
     * launcher holds no WebView of its own; the session cookie the manager view
     * minted is process-wide, so the tap still travels as a signed-in person.
     */
    private void nfcResume() {
        if (nfcAdapter == null) {
            try { nfcAdapter = NfcAdapter.getDefaultAdapter(this); } catch (Throwable ignored) { }
        }
        if (nfcAdapter == null) return;
        try {
            Bundle opts = new Bundle();
            opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
            nfcAdapter.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_NFC_BARCODE, opts);
        } catch (Throwable ignored) {
            // no NFC radio, or another app holds it
        }
    }

    private void nfcPause() {
        if (nfcAdapter == null) return;
        try { nfcAdapter.disableReaderMode(this); } catch (Throwable ignored) { }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        final JSONObject tap = JawnNfcBridge.launcherTap(tag);
        if (tap == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendNfcTap(tap);
            }
        }, "jawn-home-nfc").start();
    }

    private void sendNfcTap(JSONObject tap) {
        String base = managerOrigin();
        String manager = JawnLauncher.getManagerUrl(this, JawnLauncher.getJawnPort(this));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/manager/nfc/read").openConnection();
            trustHouse(conn, base);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            String cookie = CookieManager.getInstance().getCookie(manager);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            String body = "message=" + URLEncoder.encode(tap.optString("message", ""), "UTF-8")
                + "&serial=" + URLEncoder.encode(tap.optString("serial", ""), "UTF-8")
                + "&last_write=0&timestamp=" + System.currentTimeMillis() + "&benign=";
            OutputStream out = conn.getOutputStream();
            try { out.write(body.getBytes("UTF-8")); } finally { out.close(); }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder answer = new StringBuilder();
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) { answer.append(line); }
                } finally { reader.close(); }
            }
            String status = "";
            try { status = new JSONObject(answer.toString()).optString("status", ""); } catch (Throwable ignored) { }
            final String show = status.isEmpty() ? ("NFC: no answer (" + code + ")") : ("NFC: " + status);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(JawnHomeActivity.this, show, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "nfc tap failed", t);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(JawnHomeActivity.this,
                        "NFC could not reach the house.", Toast.LENGTH_SHORT).show();
                }
            });
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    // ----------------------------------------------------------------- role

    /**
     * API 29+ role flow, kept in its own class so the outer activity never
     * resolves RoleManager on API 26-28.
     */
    private static final class Role {
        static void update(JawnHomeActivity activity) {
            RoleManager rm = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
            boolean available = rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME);
            boolean held = available && rm.isRoleHeld(RoleManager.ROLE_HOME);
            activity.roleBannerUsesRoleManager = true;
            activity.showRoleBanner(available && !held);
        }

        static void request(JawnHomeActivity activity) {
            RoleManager rm = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
            if (rm == null) {
                activity.openHomeSettings();
                return;
            }
            Intent intent = rm.createRequestRoleIntent(RoleManager.ROLE_HOME);
            activity.startActivityForResult(intent, REQ_ROLE_HOME);
        }
    }

    private void updateRoleBanner() {
        roleBannerUsesRoleManager = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Role.update(this);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "home role check failed", t);
            }
        }
        // Pre-29 there is no role request; the Home settings screen is the only
        // door, so the banner points there.
        showRoleBanner(true);
    }

    private void showRoleBanner(boolean canAsk) {
        if (!canAsk || prefs().getBoolean(KEY_ROLE_DISMISSED, false)) {
            roleBanner.setVisibility(View.GONE);
            return;
        }
        roleText.setText(roleBannerUsesRoleManager
            ? "JawnOS is not your Home screen yet."
            : "Choose JawnOS in Home settings to use it as your Home screen.");
        roleAction.setText(roleBannerUsesRoleManager ? "Set as Home" : "Home settings");
        roleBanner.setVisibility(View.VISIBLE);
    }

    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29 && roleBannerUsesRoleManager) {
            try {
                Role.request(this);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "home role request failed", t);
            }
        }
        openHomeSettings();
    }

    /**
     * The way back to the stock launcher (or to any other Home app). Nothing is
     * forced: this only opens the system's Home settings screen.
     */
    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Home settings are not available here.", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "home settings failed", t);
        }
    }

    // ----------------------------------------------------------- crash safety

    /**
     * If the main thread crashes while Home is alive, ask AlarmManager to start
     * Home again. The alarm is held by the system, so it still fires after the
     * crashing process dies; the direct start is the fallback for a device that
     * will not schedule it. More than {@link #CRASH_LIMIT} crashes in a minute
     * means something deterministic is broken, so the restart is skipped and the
     * previous (system) handler sees the error instead.
     */
    private static synchronized void installCrashHandler(final Context app) {
        if (sCrashHandlerInstalled) return;
        sCrashHandlerInstalled = true;

        final Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                if (sAlive && thread == Looper.getMainLooper().getThread()) {
                    Log.e(TAG, "home crashed; asking for a restart", error);
                    scheduleRestart(app);
                }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    private static void scheduleRestart(Context app) {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = p.getLong(KEY_CRASH_AT, 0);
        int count = (now - last > CRASH_WINDOW_MS) ? 0 : p.getInt(KEY_CRASH_COUNT, 0);
        count++;
        p.edit().putInt(KEY_CRASH_COUNT, count).putLong(KEY_CRASH_AT, now).apply();
        if (count > CRASH_LIMIT) {
            Log.w(TAG, "home crashed " + count + " times in a minute; leaving it to the system");
            return;
        }

        Intent home = new Intent(app, JawnHomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            PendingIntent pi = PendingIntent.getActivity(app, CRASH_ALARM_REQUEST, home,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                    | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC, now + 700L, pi);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "crash restart alarm failed", t);
        }
        try {
            app.startActivity(home);
        } catch (Throwable t) {
            Log.w(TAG, "crash restart start failed", t);
        }
    }

    // --------------------------------------------------------------- helpers

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void registerPackageReceiver() {
        if (packageReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            registerReceiver(packageChanges, filter);
            packageReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "package receiver unavailable", t);
        }
    }

    private void unregisterPackageReceiver() {
        if (!packageReceiverRegistered) return;
        packageReceiverRegistered = false;
        try {
            unregisterReceiver(packageChanges);
        } catch (Throwable ignored) {
            // already gone
        }
    }

    /** Hand the words to the manager's Search app: its Apps tab finds the
     *  house's apps and appointments, which the installed-package grid cannot. */
    private void openJawnSearch(String query) {
        try {
            Intent open = new Intent(this, JawnAppActivity.class);
            open.putExtra(JawnAppActivity.EXTRA_APP, "search");
            open.putExtra(JawnAppActivity.EXTRA_QUERY, query);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(open);
        } catch (Throwable t) {
            Log.w(TAG, "search app failed", t);
            Toast.makeText(this, "Could not open Search", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View focused = getCurrentFocus();
            if (imm != null && focused != null) {
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
            // no keyboard was up
        }
    }

    private int selectableBackground() {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
            && value.resourceId != 0) {
            return value.resourceId;
        }
        value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            && value.resourceId != 0) {
            return value.resourceId;
        }
        return 0;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
