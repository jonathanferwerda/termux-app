package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

/** Control panel: jawn and the BLE bridge, live status, grouped actions. */
public class JawnDashboardActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView live;
    private TextView status;
    private EditText dirEdit;
    private CheckBox autostart;
    private CheckBox bleWithJawn;
    private boolean resumed = false;
    private long startRequestedAt = 0;
    private final JawnDashboardActivity self = this;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(30, 30, 30, 30);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.jawn_logo);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(150));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logo.setLayoutParams(logoLp);
        logo.setAdjustViewBounds(true);
        ll.addView(logo);

        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setText("JawnOS");
        ll.addView(title);

        live = new TextView(this);
        live.setTextSize(16);
        live.setPadding(0, dp(8), 0, dp(8));
        ll.addView(live);

        status = new TextView(this);
        ll.addView(status);

        dirEdit = new EditText(this);
        dirEdit.setHint("jawn folder in home (default: jawnos)");
        dirEdit.setText(JawnLauncher.getJawnDirName(this));
        ll.addView(dirEdit);

        autostart = new CheckBox(this);
        autostart.setText("Start jawn at boot");
        autostart.setChecked(JawnLauncher.isAutostartEnabled(this));
        ll.addView(autostart);

        bleWithJawn = new CheckBox(this);
        bleWithJawn.setText("Start/stop the BLE bridge with jawn");
        bleWithJawn.setChecked(JawnLauncher.isBleWithJawn(this));
        ll.addView(bleWithJawn);

        header(ll, "Jawn");
        add(ll, "Start jawn", new Runnable() {
            public void run() {
                saveSettings();
                startRequestedAt = System.currentTimeMillis();
                JawnLauncher.startJawn(self);
                toast("Starting jawn...");
                pollNow();
            }
        });
        add(ll, "Stop jawn", new Runnable() {
            public void run() {
                startRequestedAt = 0;
                JawnLauncher.stopJawn(self);
                toast("Stopping jawn");
                pollNow();
            }
        });
        add(ll, "Restart jawn", new Runnable() {
            public void run() {
                saveSettings();
                startRequestedAt = System.currentTimeMillis();
                JawnLauncher.restartJawn(self);
                toast("Restarting jawn...");
                pollNow();
            }
        });

        header(ll, "Open");
        add(ll, "Open JawnOS UI", new Runnable() { public void run() { openUi(); } });
        add(ll, "Open console", new Runnable() {
            public void run() { startActivity(new Intent(self, TermuxActivity.class)); }
        });

        header(ll, "BLE bridge");
        add(ll, "Start BLE bridge", new Runnable() {
            public void run() { JawnLauncher.startBle(self); toast("Starting BLE bridge"); pollNow(); }
        });
        add(ll, "Stop BLE bridge", new Runnable() {
            public void run() { JawnLauncher.stopBle(self); toast("Stopping BLE bridge"); pollNow(); }
        });

        header(ll, "Permissions");
        add(ll, "Grant overlay permission (dot)", new Runnable() { public void run() { openOverlaySettings(); } });
        add(ll, "Grant device permissions", new Runnable() { public void run() { grantDevicePermissions(); } });

        add(ll, "Save settings", new Runnable() {
            public void run() { saveSettings(); toast("Saved"); }
        });

        // The panel is taller than a phone screen; scroll it so the lower
        // groups (BLE bridge, Permissions) stay reachable.
        ScrollView sv = new ScrollView(this);
        sv.addView(ll);
        setContentView(sv);
    }

    protected void onResume() {
        super.onResume();
        resumed = true;
        updateEnvStatus();
        pollNow();
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
                    ui.post(new Runnable() { public void run() { render(jawn, ble); } });
                }
            }).start();
            ui.postDelayed(this, 2000);
        }
    };

    private void pollNow() {
        ui.removeCallbacks(poll);
        if (resumed) poll.run();
    }

    private void render(boolean jawnUp, boolean bleUp) {
        String js;
        if (jawnUp) {
            startRequestedAt = 0;
            js = "running";
        } else if (startRequestedAt > 0) {
            long s = (System.currentTimeMillis() - startRequestedAt) / 1000;
            js = (s < 150) ? ("starting... (" + s + "s)") : "not running";
        } else {
            js = "not running";
        }
        live.setText("jawn: " + js + "\nBLE bridge: " + (bleUp ? "running" : "stopped"));
    }

    private void updateEnvStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        status.setText("overlay permission: " + (overlay ? "granted" : "missing")
            + "\nport: " + JawnLauncher.getJawnPort(this)
            + "\nfolder: " + JawnLauncher.getJawnDirPath(this));
    }

    private void saveSettings() {
        String name = dirEdit.getText().toString().trim();
        if (name.isEmpty()) name = JawnLauncher.DEFAULT_DIR;
        JawnLauncher.prefs(this).edit()
            .putString(JawnLauncher.KEY_DIR, name)
            .putBoolean(JawnLauncher.KEY_AUTOSTART, autostart.isChecked())
            .putBoolean(JawnLauncher.KEY_BLE_WITH_JAWN, bleWithJawn.isChecked())
            .apply();
    }

    private void openUi() {
        int port = JawnLauncher.getJawnPort(this);
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://127.0.0.1:" + port + "/manager")));
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

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast("Could not open overlay settings");
        }
    }

    private void header(LinearLayout ll, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, dp(14), 0, dp(2));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(t);
    }

    private void add(LinearLayout ll, String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
            }
        });
        ll.addView(b);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
