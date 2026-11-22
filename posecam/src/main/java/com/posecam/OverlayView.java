package com.posecam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Draws the reference pose picture on top of the camera preview.
 * Drag to move, pinch to resize, two-finger twist to rotate, double-tap to reset.
 */
public class OverlayView extends View {

    public static final int MODE_PHOTO = 0;
    public static final int MODE_OUTLINE = 1;

    private Bitmap photo;
    private Bitmap outline;          // white edges on transparent, built lazily
    private int mode = MODE_PHOTO;
    private boolean mirrored;
    private int opacity = 50;        // 0..100

    private final Matrix user = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private boolean needsFit = true;

    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint lineShadow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private float lastX, lastY;
    private int activePointer = -1;
    private float lastAngle = Float.NaN;

    public OverlayView(Context c) {
        super(c);
        lineShadow.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));
        linePaint.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                user.postScale(f, f, d.getFocusX(), d.getFocusY());
                invalidate();
                return true;
            }
        });
        tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetPosition();
                return true;
            }
        });
    }

    public void setPhoto(Bitmap b) {
        photo = b;
        outline = null;
        needsFit = true;
        if (mode == MODE_OUTLINE) buildOutline();
        invalidate();
    }

    public boolean hasPhoto() { return photo != null; }

    public void setMode(int m) {
        mode = m;
        if (mode == MODE_OUTLINE) buildOutline();
        invalidate();
    }

    public void setMirrored(boolean m) { mirrored = m; invalidate(); }

    public void setOpacity(int percent) { opacity = percent; invalidate(); }

    public void resetPosition() { needsFit = true; invalidate(); }

    private void buildOutline() {
        if (photo != null && outline == null) outline = EdgeTracer.trace(photo);
    }

    private void fitCenter() {
        user.reset();
        if (photo == null || getWidth() == 0) return;
        RectF src = new RectF(0, 0, photo.getWidth(), photo.getHeight());
        RectF dst = new RectF(0, 0, getWidth(), getHeight());
        user.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        needsFit = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        needsFit = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (photo == null) return;
        if (needsFit) fitCenter();

        drawMatrix.set(user);
        if (mirrored) drawMatrix.preScale(-1, 1, photo.getWidth() / 2f, photo.getHeight() / 2f);

        int alpha = Math.round(opacity * 2.55f);
        if (mode == MODE_OUTLINE && outline != null) {
            // Outline was traced on a smaller copy; scale it back up to photo size.
            float s = photo.getWidth() / (float) outline.getWidth();
            Matrix m = new Matrix(drawMatrix);
            m.preScale(s, s);
            float shadowAlpha = Math.min(255, alpha * 1.3f);
            lineShadow.setAlpha((int) (shadowAlpha * 0.6f));
            linePaint.setAlpha(Math.min(255, alpha * 2));
            canvas.save();
            canvas.translate(dp(1.2f), dp(1.2f));
            canvas.drawBitmap(outline, m, lineShadow);
            canvas.restore();
            canvas.drawBitmap(outline, m, linePaint);
        } else {
            photoPaint.setAlpha(alpha);
            canvas.drawBitmap(photo, drawMatrix, photoPaint);
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (photo == null) return false;
        tapDetector.onTouchEvent(e);
        scaleDetector.onTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = e.getPointerId(0);
                lastX = e.getX();
                lastY = e.getY();
                lastAngle = Float.NaN;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastAngle = angle(e);
                break;
            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() >= 2) {
                    float a = angle(e);
                    if (!Float.isNaN(lastAngle)) {
                        float cx = (e.getX(0) + e.getX(1)) / 2f, cy = (e.getY(0) + e.getY(1)) / 2f;
                        user.postRotate(a - lastAngle, cx, cy);
                    }
                    lastAngle = a;
                }
                int idx = e.findPointerIndex(activePointer);
                if (idx >= 0) {
                    float x = e.getX(idx), y = e.getY(idx);
                    if (!scaleDetector.isInProgress() || e.getPointerCount() >= 2) {
                        user.postTranslate(x - lastX, y - lastY);
                    }
                    lastX = x;
                    lastY = y;
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int upIdx = e.getActionIndex();
                if (e.getPointerId(upIdx) == activePointer) {
                    int newIdx = upIdx == 0 ? 1 : 0;
                    activePointer = e.getPointerId(newIdx);
                    lastX = e.getX(newIdx);
                    lastY = e.getY(newIdx);
                }
                lastAngle = Float.NaN;
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointer = -1;
                lastAngle = Float.NaN;
                break;
        }
        return true;
    }

    private static float angle(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }
}
