package com.posecam;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements CameraPreview.Listener {

    private static final int REQ_CAMERA = 1;
    private static final int REQ_PICK = 2;

    private static final int BG = 0xFF0E0E11;
    private static final int PANEL = 0xFF18181D;
    private static final int CHIP = 0xFF2A2A31;
    private static final int ACCENT = 0xFFFF6B57;
    private static final int TEXT = 0xFFF4F4F6;
    private static final int MUTED = 0xFF9A9AA6;

    private static final int[] TIMERS = {0, 3, 10};
    private static final String REF_FILE = "reference.jpg";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ToneGenerator tone;

    private CameraPreview camera;
    private OverlayView overlay;
    private AspectFrame stage;
    private View flash;
    private TextView countdown;
    private LinearLayout emptyHint;
    private LinearLayout permissionPanel;
    private TextView permissionText;

    private TextView photoChip, outlineChip, mirrorChip, flipChip, timerChip;
    private TextView opacityValue;
    private SeekBar opacitySlider;
    private ImageView thumb;
    private View shutter;

    private FrameLayout compareScreen;
    private ImageView compareRef, compareShot;
    private TextView compareRefLabel;

    private Bitmap reference;
    private Bitmap lastShot;
    private Uri lastShotUri;
    private int timerIndex;
    private boolean counting;
    private Runnable countdownTick;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(PANEL);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (RuntimeException ignored) {}

        setContentView(buildUi());
        restoreSettings();
        loadSavedReference();
        handleSharedImage(getIntent());

        if (!hasCameraPermission()) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedImage(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            permissionPanel.setVisibility(View.GONE);
            camera.start();
        } else {
            showPermissionPanel();
        }
    }

    @Override
    protected void onPause() {
        cancelCountdown();
        camera.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tone != null) tone.release();
        io.shutdown();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (compareScreen.getVisibility() == View.VISIBLE) {
            compareScreen.setVisibility(View.GONE);
            return;
        }
        if (counting) { cancelCountdown(); return; }
        super.onBackPressed();
    }

    /** Volume buttons take the picture, handy when the phone is propped up. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                && compareScreen.getVisibility() != View.VISIBLE && hasCameraPermission()) {
            if (event.getRepeatCount() == 0) onShutter();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- permissions

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_CAMERA) return;
        if (hasCameraPermission()) {
            permissionPanel.setVisibility(View.GONE);
            camera.start();
        } else {
            showPermissionPanel();
        }
    }

    private void showPermissionPanel() {
        boolean canAsk = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        permissionText.setText(canAsk || !prefs.getBoolean("askedCamera", false)
                ? "PoseCam needs the camera to show you next to your pose picture."
                : "Camera access is off. Turn it on in Settings › Apps › PoseCam › Permissions.");
        prefs.edit().putBoolean("askedCamera", true).apply();
        permissionPanel.setVisibility(View.VISIBLE);
    }

    private void onPermissionButton() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !prefs.getBoolean("askedCamera", false)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        }
    }

    // ---------------------------------------------------------------- camera callbacks

    @Override
    public void onPreviewSize(int width, int height) {
        stage.setRatio(width / (float) height);
    }

    @Override
    public void onCameraError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- reference picture

    private void pickReference() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Choose a pose to copy"), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_PICK && result == RESULT_OK && data != null && data.getData() != null) {
            importReference(data.getData());
        }
    }

    private void handleSharedImage(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) importReference(uri);
        intent.setAction(null);
    }

    /** Decodes the picture (EXIF rotation handled by ImageDecoder) and keeps a private copy so it's there next time. */
    private void importReference(Uri uri) {
        final ContentResolver cr = getContentResolver();
        io.execute(() -> {
            try {
                Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri), (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    float s = Math.min(1f, 2048f / Math.max(w, h));
                    if (s < 1f) decoder.setTargetSize(Math.round(w * s), Math.round(h * s));
                });
                try (OutputStream out = new FileOutputStream(new File(getFilesDir(), REF_FILE))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                main.post(() -> {
                    setReference(b);
                    overlay.resetPosition();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Couldn't open that picture. Try another one.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadSavedReference() {
        File f = new File(getFilesDir(), REF_FILE);
        if (!f.exists()) { setReference(null); return; }
        io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            main.post(() -> setReference(b));
        });
    }

    private void setReference(Bitmap b) {
        reference = b;
        overlay.setPhoto(b);
        emptyHint.setVisibility(b == null ? View.VISIBLE : View.GONE);
        photoChip.setText(b == null ? "Choose pose" : "Change pose");
    }

    // ---------------------------------------------------------------- taking pictures

    private void onShutter() {
        if (counting) { cancelCountdown(); return; }
        int secs = TIMERS[timerIndex];
        if (secs == 0) { capture(); return; }

        counting = true;
        shutter.invalidate();
        final int[] left = {secs};
        countdown.setVisibility(View.VISIBLE);
        countdownTick = new Runnable() {
            @Override
            public void run() {
                if (left[0] == 0) {
                    countdown.setVisibility(View.GONE);
                    counting = false;
                    shutter.invalidate();
                    capture();
                    return;
                }
                countdown.setText(String.valueOf(left[0]));
                countdown.setScaleX(1.3f);
                countdown.setScaleY(1.3f);
                countdown.animate().scaleX(1f).scaleY(1f).setDuration(300).start();
                if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, left[0] <= 1 ? 250 : 80);
                left[0]--;
                main.postDelayed(this, 1000);
            }
        };
        countdownTick.run();
    }

    private void cancelCountdown() {
        if (!counting) return;
        counting = false;
        main.removeCallbacks(countdownTick);
        countdown.setVisibility(View.GONE);
        shutter.invalidate();
    }

    private void capture() {
        Bitmap frame;
        try {
            frame = camera.grabFrame();
        } catch (RuntimeException e) {
            frame = null;
        }
        if (frame == null) {
            Toast.makeText(this, "The camera isn't ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        flash.setAlpha(0.85f);
        flash.setVisibility(View.VISIBLE);
        flash.animate().alpha(0f).setDuration(350).withEndAction(() -> flash.setVisibility(View.GONE)).start();

        lastShot = frame;
        thumb.setImageBitmap(frame);
        thumb.setVisibility(View.VISIBLE);

        final Bitmap toSave = frame;
        io.execute(() -> {
            Uri uri = saveToGallery(toSave, "PoseCam_");
            main.post(() -> {
                if (uri == null) {
                    Toast.makeText(this, "Couldn't save the photo. Is the phone storage full?", Toast.LENGTH_LONG).show();
                } else {
                    lastShotUri = uri;
                }
            });
        });
    }

    /** Saves a JPEG to Pictures/PoseCam. Runs on the background thread. */
    private Uri saveToGallery(Bitmap b, String prefix) {
        ContentResolver cr = getContentResolver();
        String name = prefix + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PoseCam");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return null;
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null || !b.compress(Bitmap.CompressFormat.JPEG, 94, out)) throw new Exception("write failed");
        } catch (Exception e) {
            cr.delete(uri, null, null);
            return null;
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    // ---------------------------------------------------------------- compare screen

    private Bitmap referenceAsShown() {
        if (reference == null) return null;
        if (!mirrorChip.isSelected()) return reference;
        Matrix m = new Matrix();
        m.preScale(-1, 1);
        return Bitmap.createBitmap(reference, 0, 0, reference.getWidth(), reference.getHeight(), m, true);
    }

    private void openCompare() {
        if (lastShot == null) return;
        Bitmap ref = referenceAsShown();
        compareShot.setImageBitmap(lastShot);
        compareRef.setImageBitmap(ref);
        compareRef.setVisibility(ref == null ? View.GONE : View.VISIBLE);
        compareRefLabel.setVisibility(ref == null ? View.GONE : View.VISIBLE);
        compareScreen.setVisibility(View.VISIBLE);
    }

    private void saveSideBySide() {
        Bitmap ref = referenceAsShown();
        Bitmap shot = lastShot;
        if (ref == null || shot == null) return;
        io.execute(() -> {
            int h = Math.min(1600, Math.max(ref.getHeight(), shot.getHeight()));
            int rw = Math.round(ref.getWidth() * h / (float) ref.getHeight());
            int sw = Math.round(shot.getWidth() * h / (float) shot.getHeight());
            int gap = Math.max(8, h / 100);
            Bitmap out = Bitmap.createBitmap(rw + sw + gap * 3, h + gap * 2, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(ref, null, new android.graphics.Rect(gap, gap, gap + rw, gap + h), p);
            c.drawBitmap(shot, null, new android.graphics.Rect(gap * 2 + rw, gap, gap * 2 + rw + sw, gap + h), p);
            Uri uri = saveToGallery(out, "PoseCam_compare_");
            main.post(() -> Toast.makeText(this,
                    uri == null ? "Couldn't save the side-by-side picture." : "Saved side-by-side to Pictures › PoseCam",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void openInGallery() {
        if (lastShotUri == null) {
            Toast.makeText(this, "Still saving, try again in a second.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastShotUri, "image/jpeg");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception e) {
            Toast.makeText(this, "No gallery app found.", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- settings

    private void restoreSettings() {
        int opacity = prefs.getInt("opacity", 45);
        overlay.setOpacity(opacity);
        opacityValue.setText(opacity + "%");
        opacitySlider.setProgress(opacity);

        setMode(prefs.getInt("mode", OverlayView.MODE_PHOTO));
        setMirror(prefs.getBoolean("mirror", false));
        camera.setFront(prefs.getBoolean("front", false));
        flipChip.setVisibility(camera.hasFrontAndBack() ? View.VISIBLE : View.GONE);
        timerIndex = Math.min(TIMERS.length - 1, prefs.getInt("timer", 0));
        updateTimerChip();
    }

    private void setMode(int mode) {
        overlay.setMode(mode);
        outlineChip.setSelected(mode == OverlayView.MODE_OUTLINE);
        styleChip(outlineChip);
        prefs.edit().putInt("mode", mode).apply();
    }

    private void setMirror(boolean on) {
        overlay.setMirrored(on);
        mirrorChip.setSelected(on);
        styleChip(mirrorChip);
        prefs.edit().putBoolean("mirror", on).apply();
    }

    private void updateTimerChip() {
        int s = TIMERS[timerIndex];
        timerChip.setText(s == 0 ? "Timer off" : "Timer " + s + "s");
        timerChip.setSelected(s != 0);
        styleChip(timerChip);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, match());

        // Top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        photoChip = chip("Choose pose");
        photoChip.setOnClickListener(v -> pickReference());
        outlineChip = chip("Outline");
        outlineChip.setOnClickListener(v -> setMode(outlineChip.isSelected() ? OverlayView.MODE_PHOTO : OverlayView.MODE_OUTLINE));
        mirrorChip = chip("Mirror");
        mirrorChip.setOnClickListener(v -> setMirror(!mirrorChip.isSelected()));
        flipChip = chip("Flip camera");
        flipChip.setOnClickListener(v -> {
            camera.setFront(!camera.isFront());
            prefs.edit().putBoolean("front", camera.isFront()).apply();
        });
        top.addView(photoChip, weighted(1.25f));
        top.addView(outlineChip, weighted(1f));
        top.addView(mirrorChip, weighted(1f));
        top.addView(flipChip, weighted(1.15f));
        column.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // Stage: camera + overlay, kept at the camera's aspect ratio so the overlay lines up with the photo
        FrameLayout stageHolder = new FrameLayout(this);
        column.addView(stageHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        stage = new AspectFrame(this);
        stage.setRatio(3f / 4f);
        stage.setClipChildren(true);
        stageHolder.addView(stage, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        camera = new CameraPreview(this);
        camera.setListener(this);
        stage.addView(camera, match());

        overlay = new OverlayView(this);
        stage.addView(overlay, match());

        emptyHint = new LinearLayout(this);
        emptyHint.setOrientation(LinearLayout.VERTICAL);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(dp(32), dp(24), dp(32), dp(24));
        emptyHint.setBackground(round(0xB3000000, 18));
        TextView hintTitle = text("Copy a pose", 20, TEXT, true);
        TextView hintBody = text("Pick a picture of the pose you want. It shows on top of the camera so you can line yourself up.", 14, 0xFFD8D8DE, false);
        hintBody.setGravity(Gravity.CENTER);
        hintBody.setPadding(0, dp(6), 0, dp(14));
        TextView hintButton = chip("Choose pose picture");
        hintButton.setSelected(true);
        styleChip(hintButton);
        hintButton.setOnClickListener(v -> pickReference());
        emptyHint.addView(hintTitle);
        emptyHint.addView(hintBody);
        emptyHint.addView(hintButton, new LinearLayout.LayoutParams(-2, dp(44)));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        hintLp.setMargins(dp(24), 0, dp(24), 0);
        stage.addView(emptyHint, hintLp);

        countdown = text("", 120, Color.WHITE, true);
        countdown.setShadowLayer(dp(8), 0, 0, 0xAA000000);
        countdown.setGravity(Gravity.CENTER);
        countdown.setVisibility(View.GONE);
        stage.addView(countdown, match());

        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setVisibility(View.GONE);
        stage.addView(flash, match());

        // Bottom panel
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(PANEL);
        bottom.setPadding(dp(20), dp(12), dp(20), dp(16));
        column.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout opacityRow = new LinearLayout(this);
        opacityRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView opacityLabel = text("See-through", 13, MUTED, false);
        SeekBar opacity = new SeekBar(this);
        opacitySlider = opacity;
        opacity.setMax(100);
        opacity.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        opacity.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        opacityValue = text("45%", 13, TEXT, false);
        opacityValue.setMinWidth(dp(40));
        opacityValue.setGravity(Gravity.END);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                overlay.setOpacity(p);
                opacityValue.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("opacity", s.getProgress()).apply();
            }
        });
        opacityRow.addView(opacityLabel);
        opacityRow.addView(opacity, new LinearLayout.LayoutParams(0, -2, 1f));
        opacityRow.addView(opacityValue);
        bottom.addView(opacityRow);

        TextView gestureHint = text("Drag the pose to move it · pinch to resize · twist to rotate · double-tap to reset", 11, MUTED, false);
        gestureHint.setGravity(Gravity.CENTER);
        gestureHint.setPadding(0, dp(2), 0, dp(10));
        bottom.addView(gestureHint);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(controls, new LinearLayout.LayoutParams(-1, dp(80)));

        FrameLayout left = new FrameLayout(this);
        timerChip = chip("Timer off");
        timerChip.setOnClickListener(v -> {
            timerIndex = (timerIndex + 1) % TIMERS.length;
            prefs.edit().putInt("timer", timerIndex).apply();
            updateTimerChip();
        });
        left.addView(timerChip, new FrameLayout.LayoutParams(dp(104), dp(40), Gravity.CENTER_VERTICAL | Gravity.START));
        controls.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        shutter = new ShutterButton(this);
        shutter.setContentDescription("Take picture");
        shutter.setOnClickListener(v -> onShutter());
        controls.addView(shutter, new LinearLayout.LayoutParams(dp(76), dp(76)));

        FrameLayout right = new FrameLayout(this);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(round(CHIP, 12));
        thumb.setClipToOutline(true);
        thumb.setVisibility(View.INVISIBLE);
        thumb.setContentDescription("Compare with the pose");
        thumb.setOnClickListener(v -> openCompare());
        right.addView(thumb, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL | Gravity.END));
        controls.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));

        root.addView(buildCompareScreen(), match());
        root.addView(buildPermissionPanel(), match());
        return root;
    }

    private View buildCompareScreen() {
        compareScreen = new FrameLayout(this);
        compareScreen.setBackgroundColor(BG);
        compareScreen.setVisibility(View.GONE);
        compareScreen.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        compareScreen.addView(col, match());

        TextView title = text("How close did you get?", 20, TEXT, true);
        title.setPadding(dp(4), 0, 0, dp(12));
        col.addView(title);

        LinearLayout pics = new LinearLayout(this);
        pics.setGravity(Gravity.CENTER);
        col.addView(pics, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout refCol = labeledImage();
        compareRef = (ImageView) refCol.getChildAt(0);
        compareRefLabel = (TextView) refCol.getChildAt(1);
        compareRefLabel.setText("Pose");
        LinearLayout shotCol = labeledImage();
        compareShot = (ImageView) shotCol.getChildAt(0);
        ((TextView) shotCol.getChildAt(1)).setText("You");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1f);
        half.setMargins(dp(4), 0, dp(4), 0);
        pics.addView(refCol, half);
        pics.addView(shotCol, new LinearLayout.LayoutParams(half));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(16), 0, 0);
        TextView back = chip("Back to camera");
        back.setOnClickListener(v -> compareScreen.setVisibility(View.GONE));
        TextView gallery = chip("Open photo");
        gallery.setOnClickListener(v -> openInGallery());
        TextView both = chip("Save side-by-side");
        both.setSelected(true);
        styleChip(both);
        both.setOnClickListener(v -> saveSideBySide());
        buttons.addView(back, weighted(1.1f, dp(46)));
        buttons.addView(gallery, weighted(0.9f, dp(46)));
        buttons.addView(both, weighted(1.2f, dp(46)));
        col.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        return compareScreen;
    }

    private LinearLayout labeledImage() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        c.addView(iv, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView label = text("", 13, MUTED, false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);
        c.addView(label);
        return c;
    }

    private View buildPermissionPanel() {
        permissionPanel = new LinearLayout(this);
        permissionPanel.setOrientation(LinearLayout.VERTICAL);
        permissionPanel.setGravity(Gravity.CENTER);
        permissionPanel.setBackgroundColor(BG);
        permissionPanel.setPadding(dp(32), dp(32), dp(32), dp(32));
        permissionPanel.setVisibility(View.GONE);
        permissionPanel.setClickable(true);
        permissionPanel.addView(text("PoseCam", 26, TEXT, true));
        permissionText = text("", 15, 0xFFD8D8DE, false);
        permissionText.setGravity(Gravity.CENTER);
        permissionText.setPadding(0, dp(10), 0, dp(20));
        permissionPanel.addView(permissionText);
        TextView allow = chip("Allow camera");
        allow.setSelected(true);
        styleChip(allow);
        allow.setOnClickListener(v -> onPermissionButton());
        permissionPanel.addView(allow, new LinearLayout.LayoutParams(dp(200), dp(48)));
        return permissionPanel;
    }

    // ---------------------------------------------------------------- small view helpers

    private TextView chip(String label) {
        TextView t = text(label, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setMinHeight(dp(38));
        styleChip(t);
        return t;
    }

    private void styleChip(TextView t) {
        boolean on = t.isSelected();
        t.setBackground(round(on ? ACCENT : CHIP, 20));
        t.setTextColor(on ? 0xFF1A0E0C : TEXT);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams weighted(float w) { return weighted(w, dp(38)); }

    private LinearLayout.LayoutParams weighted(float w, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, w);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Keeps its children at a fixed width:height ratio, as large as fits. */
    static final class AspectFrame extends FrameLayout {
        private float ratio = 0.75f;

        AspectFrame(Context c) { super(c); }

        void setRatio(float r) {
            if (Math.abs(r - ratio) < 0.001f) return;
            ratio = r;
            requestLayout();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            if (w == 0 || h == 0) { super.onMeasure(wSpec, hSpec); return; }
            int fw = w, fh = Math.round(w / ratio);
            if (fh > h) { fh = h; fw = Math.round(h * ratio); }
            super.onMeasure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY));
        }
    }

    /** Round white shutter; turns into a stop square during a countdown. */
    final class ShutterButton extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        ShutterButton(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(4));
            ring.setColor(Color.WHITE);
            fill.setColor(Color.WHITE);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(3);
            c.drawCircle(cx, cy, r, ring);
            if (counting) {
                fill.setColor(ACCENT);
                float s = r * 0.42f;
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, dp(4), dp(4), fill);
            } else {
                fill.setColor(isPressed() ? 0xFFCCCCCC : Color.WHITE);
                c.drawCircle(cx, cy, r - dp(7), fill);
            }
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }
}
