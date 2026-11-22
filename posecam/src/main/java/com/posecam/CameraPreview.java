package com.posecam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.Collections;

/**
 * Live camera preview on a TextureView (Camera2, portrait only).
 * Photos are grabbed from the preview at full preview resolution, so what you see is what you get.
 */
@SuppressLint("MissingPermission")
public class CameraPreview extends TextureView implements TextureView.SurfaceTextureListener {

    public interface Listener {
        /** Preview size, already rotated to portrait (width < height). */
        void onPreviewSize(int width, int height);
        void onCameraError(String message);
    }

    private static final int MAX_LONG_SIDE = 1920;

    private final CameraManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private HandlerThread thread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Size bufferSize;

    private boolean front;
    private boolean wantOpen;

    public CameraPreview(Context c) {
        super(c);
        manager = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        setSurfaceTextureListener(this);
    }

    public void setListener(Listener l) { listener = l; }

    public boolean isFront() { return front; }

    public boolean hasFrontAndBack() {
        return findCamera(true) != null && findCamera(false) != null;
    }

    public void setFront(boolean f) {
        if (front == f) return;
        front = f;
        if (wantOpen) { close(); open(); }
    }

    /** Call from onResume once the camera permission is granted. */
    public void start() {
        wantOpen = true;
        if (isAvailable()) open();
    }

    /** Call from onPause. */
    public void stop() {
        wantOpen = false;
        close();
    }

    /** Current frame, sized to the preview buffer (portrait). */
    public Bitmap grabFrame() {
        if (bufferSize == null) return getBitmap();
        // Buffer is landscape; on a portrait screen the frame is shown rotated.
        return getBitmap(bufferSize.getHeight(), bufferSize.getWidth());
    }

    private String findCamera(boolean wantFront) {
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (wantFront && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
                if (!wantFront && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (CameraAccessException ignored) {
        }
        return null;
    }

    private void open() {
        if (device != null) return;
        String id = findCamera(front);
        if (id == null) id = findCamera(!front);
        if (id == null) { fail("No camera found on this phone."); return; }

        try {
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            bufferSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));
            getSurfaceTexture().setDefaultBufferSize(bufferSize.getWidth(), bufferSize.getHeight());
            if (listener != null) listener.onPreviewSize(bufferSize.getHeight(), bufferSize.getWidth());

            thread = new HandlerThread("camera");
            thread.start();
            bg = new Handler(thread.getLooper());
            manager.openCamera(id, deviceCallback, bg);
        } catch (CameraAccessException | SecurityException e) {
            fail("Couldn't open the camera. Is another app using it?");
        }
    }

    /** Prefer 4:3 (the sensor's full view), then the largest that fits MAX_LONG_SIDE. */
    private static Size choosePreviewSize(Size[] sizes) {
        Size best43 = null, bestAny = null;
        for (Size s : sizes) {
            int lw = Math.max(s.getWidth(), s.getHeight());
            if (lw > MAX_LONG_SIDE) continue;
            long area = (long) s.getWidth() * s.getHeight();
            if (bestAny == null || area > (long) bestAny.getWidth() * bestAny.getHeight()) bestAny = s;
            if (s.getWidth() * 3 == s.getHeight() * 4
                    && (best43 == null || area > (long) best43.getWidth() * best43.getHeight())) best43 = s;
        }
        if (best43 != null) return best43;
        if (bestAny != null) return bestAny;
        return sizes[0];
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice d) {
            device = d;
            startSession();
        }

        @Override
        public void onDisconnected(CameraDevice d) {
            d.close();
            device = null;
        }

        @Override
        public void onError(CameraDevice d, int error) {
            d.close();
            device = null;
            fail("The camera stopped working (error " + error + "). Close other camera apps and try again.");
        }
    };

    @SuppressWarnings("deprecation")
    private void startSession() {
        SurfaceTexture st = getSurfaceTexture();
        if (st == null || device == null) return;
        Surface surface = new Surface(st);
        try {
            final CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(surface);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            device.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device == null) return;
                    session = s;
                    try {
                        s.setRepeatingRequest(req.build(), null, bg);
                    } catch (CameraAccessException | IllegalStateException e) {
                        fail("Couldn't start the camera preview.");
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    fail("Couldn't start the camera preview.");
                }
            }, bg);
        } catch (CameraAccessException | IllegalStateException e) {
            fail("Couldn't start the camera preview.");
        }
    }

    private void close() {
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (thread != null) { thread.quitSafely(); thread = null; bg = null; }
    }

    private void fail(String msg) {
        main.post(() -> { if (listener != null) listener.onCameraError(msg); });
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
        if (wantOpen) open();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
        close();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture s) {}
}
