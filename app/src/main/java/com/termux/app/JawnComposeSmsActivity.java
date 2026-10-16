package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * The sms role's face: the system's compose intents (SEND/SENDTO to an sms
 * address) land here, and the house's chat is where the words go - the number
 * and any pre-filled body ride along for the web side to read.
 */
public class JawnComposeSmsActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        Intent out = new Intent(this, JawnWebViewActivity.class);
        Uri data = in.getData();
        String address = in.getStringExtra("address");
        if (data != null && address == null) {
            address = data.getSchemeSpecificPart();
        }
        if (address != null) { out.putExtra("jawn_sms_number", address); }
        String body = in.getStringExtra("sms_body");
        if (body == null) { body = in.getStringExtra(Intent.EXTRA_TEXT); }
        if (body != null) { out.putExtra("jawn_sms_body", body); }
        startActivity(out);
        finish();
    }
}
