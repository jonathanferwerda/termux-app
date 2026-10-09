package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

/** Small control panel: start/stop jawn, open the UI and console, dot permission. */
public class JawnDashboardActivity extends Activity {

    private TextView status;
    private EditText dirEdit;
    private CheckBox autostart;

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

        final JawnDashboardActivity self = this;
        add(ll, "Start jawn", new Runnable() {
            public void run() {
                saveSettings();
                JawnLauncher.startJawn(self);
                toast("Starting jawn");
            }
        });
        add(ll, "Stop jawn", new Runnable() {
            public void run() {
                JawnLauncher.stopJawn(self);
                toast("Stopping jawn");
            }
        });
        add(ll, "Open JawnOS UI", new Runnable() {
            public void run() {
                openUi();
            }
        });
        add(ll, "Open console", new Runnable() {
            public void run() {
                startActivity(new Intent(self, TermuxActivity.class));
            }
        });
        add(ll, "Grant overlay permission (dot)", new Runnable() {
            public void run() {
                openOverlaySettings();
            }
        });
        add(ll, "Save settings", new Runnable() {
            public void run() {
                saveSettings();
                toast("Saved");
            }
        });

        setContentView(ll);
    }

    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
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
            .apply();
    }

    private void openUi() {
        int port = JawnLauncher.getJawnPort(this);
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://127.0.0.1:" + port + "/manager")));
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast("Could not open overlay settings");
        }
    }

    private void add(LinearLayout ll, String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
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
