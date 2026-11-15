package uz.fastbest.kartformat;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF görüntüleyici: sayfalar alt alta, kaydırarak okunur. */
public class PdfActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PdfRenderer pdf;
    private ParcelFileDescriptor fd;
    private LruCache<Integer, Bitmap> cache;
    private int width;
    private TextView pageLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        String path = getIntent().getStringExtra("path");
        try {
            fd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            pdf = new PdfRenderer(fd);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, L.t("PDF açılamadı: " + e.getMessage()),
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getWindow().setStatusBarColor(0xFF151619);
        getWindow().setNavigationBarColor(0xFF151619);
        width = getResources().getDisplayMetrics().widthPixels;
        int mem = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 96L << 20);
        cache = new LruCache<Integer, Bitmap>(mem) {
            @Override
            protected int sizeOf(Integer k, Bitmap b) {
                return b.getByteCount();
            }
        };
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF3A3D44);
        RecyclerView list = new RecyclerView(this);
        final LinearLayoutManager lm = new LinearLayoutManager(this);
        list.setLayoutManager(lm);
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                ImageView iv = new ImageView(PdfActivity.this);
                iv.setAdjustViewBounds(true);
                iv.setBackgroundColor(Color.WHITE);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = 8;
                iv.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(iv) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                ImageView iv = (ImageView) h.itemView;
                iv.setTag(pos);
                Bitmap b = cache.get(pos);
                if (b != null) {
                    iv.setImageBitmap(b);
                    iv.setMinimumHeight(0);
                    return;
                }
                iv.setImageDrawable(null);
                iv.setMinimumHeight((int) (width * 1.41f));
                io.execute(() -> {
                    Bitmap bm = render(pos);
                    if (bm == null) return;
                    main.post(() -> {
                        cache.put(pos, bm);
                        if (Integer.valueOf(pos).equals(iv.getTag())) {
                            iv.setImageBitmap(bm);
                            iv.setMinimumHeight(0);
                        }
                    });
                });
            }

            @Override
            public int getItemCount() {
                return pdf.getPageCount();
            }
        });
        root.addView(list);
        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setTextSize(13);
        pageLabel.setBackgroundColor(0xAA000000);
        pageLabel.setPadding(20, 8, 20, 8);
        root.addView(pageLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                showPage(lm.findFirstVisibleItemPosition());
            }
        });
        showPage(0);
        setContentView(root);
        setTitle(new File(path).getName());
    }

    private void showPage(int p) {
        pageLabel.setText(String.format(Locale.ROOT, "%d / %d", Math.max(0, p) + 1, pdf.getPageCount()));
    }

    /** PdfRenderer aynı anda tek sayfa açabilir; iş parçacığı tek olduğundan güvenli. */
    private Bitmap render(int i) {
        try {
            synchronized (this) {
                if (pdf == null) return null;
                PdfRenderer.Page pg = pdf.openPage(i);
                try {
                    int w = Math.min(width, 1600);
                    int h = Math.max(1, Math.round(w * (float) pg.getHeight() / pg.getWidth()));
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return b;
                } finally {
                    pg.close();
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        synchronized (this) {
            try {
                if (pdf != null) pdf.close();
                if (fd != null) fd.close();
            } catch (Exception ignored) {
            }
            pdf = null;
        }
        super.onDestroy();
    }
}
