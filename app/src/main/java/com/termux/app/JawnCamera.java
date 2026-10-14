package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;

/**
 * The app's camera: the appointment's camera buttons open the OS camera and the
 * OS video recorder (the viewfinder the user actually sees, and the camera's own
 * processing) instead of the device blindly shelling it to termux-camera-photo.
 *
 * The page is exposed window.JawnCamera; a capture comes back as base64 over
 * window.__jawnCameraResult, which the web side posts to /manager/take_picture.
 * Reading and writing are the OS apps' business; this only hands over the file.
 */
public class JawnCamera {

    /** How the bridge pushes a result into the page (implemented by the WebView). */
    public interface Poster {
        void post(String script);
    }

    private static final int REQ_PHOTO = 7101;
    private static final int REQ_VIDEO = 7102;
    private static final String AUTHORITY_SUFFIX = ".jawnupdates";
    private static final long MAX_BYTES = 80L * 1024 * 1024;

    private final Activity activity;
    private final Poster poster;
    private File pendingFile;
    private String pendingKind;

    public JawnCamera(Activity activity, Poster poster) {
        this.activity = activity;
        this.poster = poster;
    }

    // ---- the window.JawnCamera surface (called on a binder thread) ----

    @JavascriptInterface
    public String available() {
        return "yes";
    }

    @JavascriptInterface
    public String photo(final String facing) {
        activity.runOnUiThread(() -> launchPhoto(facing));
        return "ok";
    }

    @JavascriptInterface
    public String video() {
        activity.runOnUiThread(this::launchVideo);
        return "ok";
    }

    // ---- the OS capture ----

    private File newCaptureFile(String ext) {
        File dir = new File(activity.getCacheDir(), "captures");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "jawn_" + System.currentTimeMillis() + "." + ext);
    }

    private Uri uriFor(File f) {
        return FileProvider.getUriForFile(activity, activity.getPackageName() + AUTHORITY_SUFFIX, f);
    }

    private void launchPhoto(String facing) {
        try {
            pendingFile = newCaptureFile("jpg");
            pendingKind = "photo";
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uriFor(pendingFile));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("1".equals(facing)) i.putExtra("android.intent.extras.CAMERA_FACING", 1);
            activity.startActivityForResult(i, REQ_PHOTO);
        } catch (Throwable t) {
            pendingFile = null;
            pendingKind = null;
            emitError("no camera app");
        }
    }

    private void launchVideo() {
        try {
            pendingFile = newCaptureFile("mp4");
            pendingKind = "video";
            Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uriFor(pendingFile));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(i, REQ_VIDEO);
        } catch (Throwable t) {
            pendingFile = null;
            pendingKind = null;
            emitError("no video app");
        }
    }

    /** The activity forwards its results here. True when the result was ours. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PHOTO && requestCode != REQ_VIDEO) return false;
        File f = pendingFile;
        String kind = pendingKind;
        pendingFile = null;
        pendingKind = null;
        if (f == null) return true;
        if (resultCode != Activity.RESULT_OK || !f.exists() || f.length() == 0) {
            if (f.exists()) f.delete();
            emit("{\"ok\":false,\"error\":\"cancelled\"}");
            return true;
        }
        String mime = "photo".equals(kind) ? "image/jpeg" : "video/mp4";
        String b64 = readBase64(f);
        if (b64 == null) {
            emit("{\"ok\":false,\"error\":\"capture too large\"}");
            return true;
        }
        emit("{\"ok\":true,\"kind\":" + js(kind) + ",\"mime\":" + js(mime)
            + ",\"name\":" + js(f.getName()) + ",\"data\":" + js(b64) + "}");
        return true;
    }

    /** The file as base64, or null when it is too big to hand over the bridge. */
    private String readBase64(File f) {
        try {
            long len = f.length();
            if (len <= 0 || len > MAX_BYTES) return null;
            byte[] buf = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return Base64.encodeToString(buf, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private void emit(String json) {
        poster.post("window.__jawnCameraResult && window.__jawnCameraResult(" + json + ");");
    }

    private void emitError(String msg) {
        emit("{\"ok\":false,\"error\":" + js(msg) + "}");
    }

    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
