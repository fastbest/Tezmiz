package uz.fastbest.kartformat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Resim görüntüleyici: kaydırarak önceki/sonraki, iki parmakla ve çift dokunuşla yakınlaştırma. */
public class ImageViewerActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ArrayList<String> paths;
    private int index;
    private int rotation;
    private ImageView image;
    private TextView caption;
    private float scale = 1f;
    private float tx;
    private float ty;
    private int token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        paths = getIntent().getStringArrayListExtra("paths");
        index = getIntent().getIntExtra("index", 0);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0x88000000);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(24, 16, 8, 16);
        caption = new TextView(this);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(14);
        caption.setSingleLine(true);
        bar.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(button("⟳", v -> {
            rotation = (rotation + 90) % 360;
            load();
        }));
        bar.addView(button("↗", v -> share()));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        root.addView(bar, lp);
        root.setFitsSystemWindows(true);
        setContentView(root);

        final ScaleGestureDetector sg = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        scale = Math.max(1f, Math.min(6f, scale * d.getScaleFactor()));
                        apply();
                        return true;
                    }
                });
        final GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        scale = scale > 1.01f ? 1f : 2.5f;
                        tx = 0;
                        ty = 0;
                        apply();
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                        if (scale <= 1.01f) return false;
                        tx -= dx;
                        ty -= dy;
                        apply();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (scale > 1.01f || e1 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        if (Math.abs(dx) < 120 || Math.abs(vx) < Math.abs(vy)) return false;
                        go(dx < 0 ? 1 : -1);
                        return true;
                    }
                });
        image.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            gd.onTouchEvent(e);
            return true;
        });
        load();
    }

    private TextView button(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setTextSize(22);
        b.setPadding(28, 4, 28, 4);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void go(int d) {
        int n = index + d;
        if (n < 0 || n >= paths.size()) return;
        index = n;
        rotation = 0;
        load();
    }

    private void apply() {
        image.setScaleX(scale);
        image.setScaleY(scale);
        if (scale <= 1.01f) {
            tx = 0;
            ty = 0;
        }
        image.setTranslationX(tx);
        image.setTranslationY(ty);
    }

    private void load() {
        final File f = new File(paths.get(index));
        caption.setText((index + 1) + "/" + paths.size() + "   " + f.getName());
        scale = 1f;
        apply();
        final int my = ++token;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int target = Math.max(dm.widthPixels, dm.heightPixels);
        final int rot = rotation;
        io.execute(() -> {
            Bitmap b = decode(f, target);
            if (b != null) {
                int exif = exifRotation(f);
                int total = (exif + rot) % 360;
                if (total != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(total);
                    try {
                        b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    } catch (OutOfMemoryError ignored) {
                    }
                }
            }
            final Bitmap fb = b;
            main.post(() -> {
                if (my != token) return;
                if (fb == null) {
                    caption.setText(f.getName() + "  (açılamadı)");
                    image.setImageDrawable(null);
                } else {
                    image.setImageBitmap(fb);
                }
            });
        });
    }

    private static Bitmap decode(File f, int target) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int s = 1;
            while (o.outWidth / s > target * 2 || o.outHeight / s > target * 2) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int exifRotation(File f) {
        try {
            int o = new ExifInterface(f.getAbsolutePath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void share() {
        try {
            File f = new File(paths.get(index));
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND).setType("image/*")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Paylaş"));
        } catch (RuntimeException ignored) {
        }
    }
}
