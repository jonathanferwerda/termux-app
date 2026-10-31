package com.jawnos.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * The dialer role's face: a dial intent to a tel address lands here, and the
 * house's Phone app is the pad - the number rides along for it to read.
 */
public class JawnDialerActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent out = new Intent(this, JawnWebViewActivity.class);
        Uri data = getIntent().getData();
        if (data != null) {
            out.putExtra("jawn_dial_number", data.getSchemeSpecificPart());
        }
        startActivity(out);
        finish();
    }
}
