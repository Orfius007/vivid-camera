package com.vividgrade.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int CAMERA_REQUEST = 10;

    private ImageView preview;
    private TextView[] chips;
    private View shutter;
    private ProcessCameraProvider provider;
    private ImageCapture capture;
    private boolean front = false;
    private volatile Grade grade;
    private int presetIndex = 2;
    private float strength = 1f;

    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        grade = new Grade(presetIndex, strength);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == CAMERA_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission is needed to use this app.", Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(8), dp(12), dp(20));
        bottom.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottom, blp);

        // Look chips
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(chipRow);
        chips = new TextView[Grade.NAMES.length];
        for (int i = 0; i < Grade.NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(Grade.NAMES[i]);
            t.setTextSize(14);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            t.setOnClickListener(v -> selectPreset(idx));
            chipRow.addView(t, lp);
            chips[i] = t;
        }
        bottom.addView(scroll);

        // Strength
        LinearLayout strengthRow = new LinearLayout(this);
        strengthRow.setOrientation(LinearLayout.HORIZONTAL);
        strengthRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sl = new TextView(this);
        sl.setText("Strength");
        sl.setTextColor(Color.WHITE);
        sl.setTextSize(13);
        strengthRow.addView(sl);
        SeekBar bar = new SeekBar(this);
        bar.setMax(130);
        bar.setProgress(100);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                strength = p / 100f;
                grade = new Grade(presetIndex, strength);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp2.setMargins(dp(10), 0, 0, 0);
        strengthRow.addView(bar, blp2);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.setMargins(0, dp(8), 0, dp(8));
        bottom.addView(strengthRow, srp);

        // Shutter row
        FrameLayout shutterRow = new FrameLayout(this);
        shutter = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(4), 0xFF9AA5A5);
        shutter.setBackground(ring);
        shutter.setOnClickListener(v -> takePhoto());
        FrameLayout.LayoutParams shp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER);
        shutterRow.addView(shutter, shp);

        TextView flip = new TextView(this);
        flip.setText("Flip");
        flip.setTextColor(Color.WHITE);
        flip.setTextSize(15);
        flip.setGravity(Gravity.CENTER);
        flip.setPadding(dp(16), dp(10), dp(16), dp(10));
        flip.setOnClickListener(v -> { front = !front; bindCamera(); });
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        shutterRow.addView(flip, fp);
        bottom.addView(shutterRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));

        setContentView(root);
        refreshChips();
    }

    private void refreshChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == presetIndex;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(on ? Color.WHITE : 0x55FFFFFF);
            chips[i].setBackground(bg);
            chips[i].setTextColor(on ? Color.BLACK : Color.WHITE);
        }
    }

    private void selectPreset(int idx) {
        presetIndex = idx;
        grade = new Grade(presetIndex, strength);
        refreshChips();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                bindCamera();
            } catch (Exception e) {
                Toast.makeText(this, "Camera could not start.", Toast.LENGTH_LONG).show();
            }
        }, getMainExecutor());
    }

    private void bindCamera() {
        if (provider == null) return;
        provider.unbindAll();
        ResolutionSelector analysisSel = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(analysisSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(analysisExec, this::onFrame);

        ResolutionSelector captureSel = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();
        capture = new ImageCapture.Builder()
                .setResolutionSelector(captureSel)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();

        CameraSelector sel = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            provider.bindToLifecycle(this, sel, analysis, capture);
        } catch (Exception e) {
            Toast.makeText(this, "This camera could not be opened.", Toast.LENGTH_LONG).show();
        }
    }

    /** Live preview: grade each analysis frame and show it. */
    private void onFrame(ImageProxy image) {
        try {
            int w = image.getWidth(), h = image.getHeight();
            int rot = image.getImageInfo().getRotationDegrees();
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int stride = plane.getRowStride() / 4;
            Bitmap bmp = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            if (stride != w) bmp = Bitmap.createBitmap(bmp, 0, 0, w, h);
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            grade.apply(px, w, h);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            Matrix m = new Matrix();
            m.postRotate(rot);
            if (front) m.postScale(-1f, 1f);
            final Bitmap out = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false);
            runOnUiThread(() -> preview.setImageBitmap(out));
        } catch (Exception ignored) {
        } finally {
            image.close();
        }
    }

    private void takePhoto() {
        if (capture == null) return;
        shutter.setEnabled(false);
        final Grade g = grade;
        capture.takePicture(ioExec, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                byte[] bytes;
                int rot;
                try {
                    rot = image.getImageInfo().getRotationDegrees();
                    ByteBuffer b = image.getPlanes()[0].getBuffer();
                    bytes = new byte[b.remaining()];
                    b.get(bytes);
                } finally {
                    image.close();
                }
                boolean ok = processAndSave(bytes, rot, g);
                runOnUiThread(() -> {
                    shutter.setEnabled(true);
                    Toast.makeText(MainActivity.this,
                            ok ? "Saved to Pictures/VividCamera" : "The photo could not be saved.",
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(ImageCaptureException e) {
                runOnUiThread(() -> {
                    shutter.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Capture failed.", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private boolean processAndSave(byte[] jpeg, int rot, Grade g) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            if ((long) bounds.outWidth * bounds.outHeight > 16_000_000L) o.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            if (bmp == null) return false;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            g.apply(px, w, h);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            px = null;
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false);
            }
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "VividCamera_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VividCamera");
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
