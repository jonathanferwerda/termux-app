package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

/**
 * Control panel: jawn and the BLE bridge, live status, grouped actions.
 *
 * Laid out as cards on a dark ground - a header, a live status card, then one
 * card per group - rather than the flat list of buttons it used to be. Colours
 * follow the house's manager: near-black ground, a subtle card above it, the
 * jawn yellow as the accent, and green/amber/red for the live dots.
 */
public class JawnDashboardActivity extends Activity {

    private static final int GROUND = 0xFF101010;
    private static final int CARD = 0xFF1B1B1B;
    private static final int LINE = 0xFF2C2C2C;
    private static final int TEXT = 0xFFEDEDED;
    private static final int DIM = 0xFF8E8E8E;
    private static final int ACCENT = 0xFFFFCC00;
    private static final int GREEN = 0xFF34C759;
    private static final int AMBER = 0xFFFFB020;
    private static final int RED = 0xFFFF453A;
    private static final int BUTTON = 0xFF262626;
    private static final int BUTTON_ON = 0xFF3A3A3A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView jawnDot;
    private TextView bleDot;
    private TextView jawnState;
    private TextView bleState;
    private TextView status;
    private EditText dirEdit;
    private CheckBox autostart;
    private CheckBox bleWithJawn;
    private CheckBox systemBars;
    private boolean resumed = false;
    private TextView tailnetStatus;
    private static final int REQ_VPN = 0x4A41;
    private long startRequestedAt = 0;
    private final JawnDashboardActivity self = this;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(GROUND);
        getWindow().setNavigationBarColor(GROUND);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(GROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        sv.addView(root);
        setContentView(sv);

        header(root);

        // ---- live status ----
        LinearLayout statusCard = card(root, "Status");
        LinearLayout jawnRow = statusLine(statusCard);
        jawnDot = (TextView) jawnRow.getChildAt(0);
        jawnState = (TextView) jawnRow.getChildAt(1);
        LinearLayout bleRow = statusLine(statusCard);
        bleDot = (TextView) bleRow.getChildAt(0);
        bleState = (TextView) bleRow.getChildAt(1);
        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(13);
        status.setPadding(dp(2), dp(10), 0, 0);
        statusCard.addView(status);

        // ---- jawn ----
        LinearLayout jawn = card(root, "Jawn");
        buttonRow(jawn, new String[] { "Start", "Restart", "Stop", "Self test" }, new Runnable[] {
            () -> { saveSettings(); startRequestedAt = System.currentTimeMillis(); JawnLauncher.startJawn(self); toast("Starting jawn..."); pollNow(); },
            () -> { saveSettings(); startRequestedAt = System.currentTimeMillis(); JawnLauncher.restartJawn(self); toast("Restarting jawn..."); pollNow(); },
            () -> { startRequestedAt = 0; JawnLauncher.stopJawn(self); toast("Stopping jawn"); pollNow(); },
            () -> { saveSettings(); toast(JawnLauncher.selfTest(self)); pollNow(); },
        }, new boolean[] { true, false, false, false });

        // ---- open ----
        LinearLayout open = card(root, "Open");
        fullButton(open, "JawnOS UI", true, () -> openNativeUi());
        buttonRow(open, new String[] { "In browser", "Log", "Console" }, new Runnable[] {
            () -> openUi(),
            () -> startActivity(new Intent(self, JawnLogActivity.class)),
            () -> startActivity(new Intent(self, TermuxActivity.class)),
        }, null);

        // ---- keyboard ----
        LinearLayout keyboard = card(root, "Keyboard");
        fullButton(keyboard, "Choose JawnOS keyboard", true, () -> openKeyboardSettings());
        fullButton(keyboard, "Show the keyboard picker", false, () -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            } catch (Throwable t) {
                toast("No keyboard picker on this device");
            }
        });
        fullButton(keyboard, "Choose JawnOS autofill", false, () -> openAutofillSettings());

        // ---- bluetooth ----
        LinearLayout ble = card(root, "Bluetooth");
        buttonRow(ble, new String[] { "Start bridge", "Stop bridge" }, new Runnable[] {
            () -> { JawnLauncher.startBle(self); toast("Starting BLE bridge"); pollNow(); },
            () -> { JawnLauncher.stopBle(self); toast("Stopping BLE bridge"); pollNow(); },
        }, null);

        // ---- permissions ----
        LinearLayout perms = card(root, "Permissions");
        buttonRow(perms, new String[] { "Overlay (dot)", "Device" }, new Runnable[] {
            () -> openOverlaySettings(),
            () -> grantDevicePermissions(),
        }, null);

        // ---- settings ----
        LinearLayout settings = card(root, "Settings");
        dirEdit = new EditText(this);
        dirEdit.setHint("jawn folder in home (default: jawnos)");
        dirEdit.setText(JawnLauncher.getJawnDirName(this));
        dirEdit.setHintTextColor(DIM);
        dirEdit.setTextColor(TEXT);
        dirEdit.setBackgroundTintList(ColorStateList.valueOf(LINE));
        settings.addView(dirEdit);
        autostart = new CheckBox(this);
        autostart.setText("Start jawn at boot");
        autostart.setTextColor(TEXT);
        autostart.setButtonTintList(ColorStateList.valueOf(ACCENT));
        autostart.setChecked(JawnLauncher.isAutostartEnabled(this));
        settings.addView(autostart);
        bleWithJawn = new CheckBox(this);
        bleWithJawn.setText("Start/stop the BLE bridge with jawn");
        bleWithJawn.setTextColor(TEXT);
        bleWithJawn.setButtonTintList(ColorStateList.valueOf(ACCENT));
        bleWithJawn.setChecked(JawnLauncher.isBleWithJawn(this));
        settings.addView(bleWithJawn);
        systemBars = new CheckBox(this);
        systemBars.setText("Show the status and navigation bars");
        systemBars.setTextColor(TEXT);
        systemBars.setButtonTintList(ColorStateList.valueOf(ACCENT));
        systemBars.setChecked(JawnLauncher.areSystemBarsShown(this));
        settings.addView(systemBars);
        fullButton(settings, "Save settings", false, () -> { saveSettings(); toast("Saved"); });

        // ---- app ----
        LinearLayout app = card(root, "App");
        fullButton(app, "Update app", false, () -> offerUpdate(JawnUpdater.PRODUCT_APP));
        fullButton(app, "Update API", false, () -> offerUpdate(JawnUpdater.PRODUCT_API));

        // ---- tailnet ----
        LinearLayout tailnet = card(root, "Tailnet");
        tailnetStatus = new TextView(this);
        tailnetStatus.setTextColor(DIM);
        tailnetStatus.setTextSize(13);
        tailnetStatus.setPadding(dp(2), 0, 0, dp(6));
        tailnetStatus.setText(JawnTailnetController.statusText(this));
        tailnet.addView(tailnetStatus);
        fullButton(tailnet, "Join / connect", true, () -> joinTailnet());
        fullButton(tailnet, "Approve device", false, () -> openTailnetLogin());
        fullButton(tailnet, "Stop tailnet", false, () -> stopTailnet());
    }

    private void joinTailnet() {
        android.content.SharedPreferences p = getSharedPreferences(JawnTailnetController.PREFS, MODE_PRIVATE);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("preauth key (blank = browser approval)");
        input.setText(p.getString("auth_key", ""));
        input.setTextSize(12);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Join the house tailnet")
            .setMessage("Node name: " + JawnTailnetController.nodeName(this))
            .setView(input)
            .setPositiveButton("Connect", (d, w) -> {
                p.edit().putString("auth_key", input.getText().toString().trim())
                 .putBoolean("enabled", true).apply();
                prepareTailnet();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void prepareTailnet() {
        android.content.Intent prep = android.net.VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, REQ_VPN);
        else startTailnet();
    }

    private void startTailnet() {
        tailnetStatus.setText("Starting tailnet...");
        JawnTailnetController.startService(this);
        toast("Starting tailnet");
    }

    private void stopTailnet() {
        JawnTailnetController.stopService(this);
        tailnetStatus.setText("Stopping tailnet...");
        toast("Stopping tailnet");
    }

    private void openTailnetLogin() {
        String url = JawnTailnetController.getLoginUrl();
        if (url == null || url.isEmpty()) {
            toast("No login link yet - start the tailnet first");
            return;
        }
        android.content.Intent i = new android.content.Intent(this, JawnWebViewActivity.class);
        i.putExtra(JawnWebViewActivity.EXTRA_URL, url);
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startTailnet();
            else toast("Tailnet permission was not granted");
        }
    }

    // ---- the look ----

    private void header(LinearLayout root) {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.jawn_logo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(64));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        logo.setLayoutParams(lp);
        logo.setAdjustViewBounds(true);
        root.addView(logo);

        TextView title = new TextView(this);
        title.setText("JawnOS");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, dp(10), 0, dp(18));
        root.addView(title);
    }

    /** A rounded card with an optional small caps title, added to the column. */
    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), LINE);
        card.setBackground(bg);
        card.setPadding(dp(14), dp(13), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title.toUpperCase());
            t.setTextColor(DIM);
            t.setTextSize(11);
            t.setLetterSpacing(0.12f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(2), 0, 0, dp(9));
            card.addView(t);
        }
        root.addView(card);
        return card;
    }

    /** A coloured dot + label, returning the row so the dot can be recoloured. */
    private LinearLayout statusLine(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(10);
        dot.setLayoutParams(dlp);
        dot.setBackground(dot(DIM));
        TextView label = new TextView(this);
        label.setTextColor(TEXT);
        label.setTextSize(16);
        row.addView(dot);
        row.addView(label);
        card.addView(row);
        return row;
    }

    private GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private void fullButton(LinearLayout card, String label, boolean primary, Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        View b = button(label, primary, r);
        b.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(b);
        card.addView(row);
    }

    private void buttonRow(LinearLayout card, String[] labels, Runnable[] actions, boolean[] primary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            boolean p = primary != null && i < primary.length && primary[i];
            View b = button(labels[i], p, actions[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < labels.length - 1) lp.rightMargin = dp(8);
            b.setLayoutParams(lp);
            row.addView(b);
        }
        card.addView(row);
    }

    private TextView button(String label, boolean primary, Runnable r) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(13), dp(12), dp(13));
        b.setTextColor(primary ? 0xFF0A0A0A : TEXT);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] { android.R.attr.state_pressed }, rounded(primary ? 0xE6B800 : BUTTON_ON, primary));
        bg.addState(new int[] {}, rounded(primary ? ACCENT : BUTTON, primary));
        RippleDrawable rip = new RippleDrawable(ColorStateList.valueOf(primary ? 0x40000000 : 0x33FFFFFF), bg, null);
        b.setBackground(rip);
        b.setClickable(true);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private GradientDrawable rounded(int color, boolean primary) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(13));
        if (!primary) d.setStroke(dp(1), LINE);
        return d;
    }

    // ---- lifecycle and status ----

    protected void onResume() {
        super.onResume();
        if (tailnetStatus != null) tailnetStatus.setText(JawnTailnetController.statusText(this));
        resumed = true;
        updateEnvStatus();
        pollNow();
        // An app update, an OS kill, or a process restart can take the Termux
        // session that runs jawn down with it. Self-test on open and bring it
        // back when the user wants it running, so "not running" never waits on
        // a manual Start.
        JawnLauncher.selfTest(this);
    }

    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(poll);
        super.onPause();
    }

    /** Poll jawn and the BLE bridge off the UI thread while the panel is visible. */
    private final Runnable poll = new Runnable() {
        public void run() {
            if (!resumed) return;
            new Thread(new Runnable() {
                public void run() {
                    final boolean jawn = JawnLauncher.jawnIsUp(self);
                    final boolean ble = JawnLauncher.bleIsUp();
                    final boolean master = JawnLauncher.jawnMasterAlive();
                    ui.post(new Runnable() { public void run() { render(jawn, ble, master); } });
                }
            }).start();
            ui.postDelayed(this, 2000);
        }
    };

    private void pollNow() {
        ui.removeCallbacks(poll);
        if (resumed) poll.run();
    }

    private void render(boolean jawnUp, boolean bleUp, boolean master) {
        String js;
        int colour;
        if (jawnUp) {
            startRequestedAt = 0;
            js = "running";
            colour = GREEN;
        } else if (master) {
            js = "restarting..."; colour = AMBER;
        } else if (startRequestedAt > 0) {
            long s = (System.currentTimeMillis() - startRequestedAt) / 1000;
            if (s < 150) { js = "starting... (" + s + "s)"; colour = AMBER; }
            else { js = "not running"; colour = RED; }
        } else {
            js = "not running";
            colour = RED;
        }
        jawnState.setText("jawn  —  " + js);
        jawnDot.setBackground(dot(colour));
        bleState.setText("BLE bridge  —  " + (bleUp ? "running" : "stopped"));
        bleDot.setBackground(dot(bleUp ? GREEN : DIM));
    }

    private void updateEnvStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        status.setText("overlay " + (overlay ? "granted" : "missing")
            + "   ·   port " + JawnLauncher.getJawnPort(this)
            + "   ·   v" + JawnLauncher.getJawnVersion(this));
        status.append("\n" + JawnLauncher.getJawnDirPath(this));
        JawnUpdater.Release up = JawnUpdater.updateAvailable(this, JawnUpdater.PRODUCT_APP);
        status.append("\napp " + JawnUpdater.installedVersionCode(this, JawnUpdater.PRODUCT_APP)
            + (up == null ? " (up to date)" : " -> " + up.versionCode + " available"));
        JawnUpdater.Release ua = JawnUpdater.updateAvailable(this, JawnUpdater.PRODUCT_API);
        status.append("\napi " + JawnUpdater.installedVersionCode(this, JawnUpdater.PRODUCT_API)
            + (ua == null ? " (up to date)" : " -> " + ua.versionCode + " available"));
    }

    /** Install the newest staged APK of a product - one tap, with a fresh pull first. */
    private void offerUpdate(final String product) {
        toast("checking for an update...");
        new Thread(new Runnable() {
            public void run() {
                // Pull the newest builds from the mesh now, then poll the local
                // releases/ dir while they land, so a stale scan cannot report
                // "up to date" the moment a newer build sits on a peer.
                JawnUpdater.sync(self);
                for (int i = 0; i < 30; i++) {
                    final JawnUpdater.Release r = JawnUpdater.updateAvailable(self, product);
                    if (r != null) {
                        ui.post(new Runnable() { public void run() { installUpdate(r); } });
                        return;
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
                ui.post(new Runnable() { public void run() {
                    toast(JawnUpdater.labelForProduct(product) + " is up to date");
                } });
            }
        }).start();
    }

    private void installUpdate(JawnUpdater.Release r) {
        if (!JawnUpdater.canInstall(self)) {
            JawnUpdater.openInstallSettings(self);
            toast("Allow installs, then tap Update again");
            return;
        }
        toast(JawnUpdater.install(self, r));
    }

    private void saveSettings() {
        String name = dirEdit.getText().toString().trim();
        if (name.isEmpty()) name = JawnLauncher.DEFAULT_DIR;
        JawnLauncher.prefs(this).edit()
            .putString(JawnLauncher.KEY_DIR, name)
            .putBoolean(JawnLauncher.KEY_AUTOSTART, autostart.isChecked())
            .putBoolean(JawnLauncher.KEY_BLE_WITH_JAWN, bleWithJawn.isChecked())
            .putBoolean(JawnLauncher.KEY_SYSTEM_BARS, systemBars.isChecked())
            .apply();
    }

    /** The full screen view in the app's own WebView - the home screen icon's
     *  target (see JawnWebViewActivity). */
    private void openNativeUi() {
        startActivity(new Intent(self, JawnWebViewActivity.class));
    }

    /** The browser path: hand the ticket to the installed PWA. */
    private void openUi() {
        int port = JawnLauncher.getJawnPort(this);
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(JawnLauncher.getUiUrl(this, port))));
    }

    /** Launch the JawnOS API app's grant-all screen (it owns the device permissions). */
    private void grantDevicePermissions() {
        try {
            Intent i = new Intent();
            i.setClassName("com.termux.api", "com.termux.api.JawnGrantAllActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("JawnOS API app is not installed");
        }
    }

    /** Ask Android to make JawnOS Autofill the screen reader of forms. */
    private void openAutofillSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            toast("Choose JawnOS Autofill");
        } catch (Throwable t) {
            toast("Autofill settings are unavailable");
        }
    }

    /** Open Android's keyboard settings; the person turns JawnOS on there. */
    private void openKeyboardSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            toast("Turn on JawnOS Keyboard, then pick it");
        } catch (Throwable t) {
            toast("Keyboard settings are unavailable");
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast("Could not open overlay settings");
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        android.util.Log.i("JawnToast", s);
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
