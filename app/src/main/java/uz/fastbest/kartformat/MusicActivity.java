package uz.fastbest.kartformat;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/** Basit müzik çalar: klasördeki şarkılar arasında önceki/sonraki, ilerleme çubuğu, kapak resmi. */
public class MusicActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private ArrayList<String> paths;
    private int index;
    private MediaPlayer player;
    private TextView title, artist, pos, dur, playBtn;
    private ImageView cover;
    private SeekBar seek;
    private boolean dragging;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String s, float size, View.OnClickListener l) {
        TextView b = text(s, size, Theme.TXT);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Theme.FIELD);
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Theme.widgetTheme(this));
        super.onCreate(savedInstanceState);
        L.init(this);
        Theme.apply(this);
        paths = getIntent().getStringArrayListExtra("paths");
        index = getIntent().getIntExtra("index", 0);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackground(Theme.pageBg());
        root.setPadding(dp(24), dp(28), dp(24), dp(28));

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Theme.GLASS ? Theme.ROW : Theme.SURF);
        cb.setCornerRadius(dp(14));
        cover.setBackground(cb);
        cover.setClipToOutline(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, 0, 1);
        clp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        root.addView(cover, clp);

        title = text("", 20, Theme.TXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setSelected(true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(4));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        artist = text("", 14, Theme.SUB);
        artist.setGravity(Gravity.CENTER);
        artist.setSingleLine(true);
        root.addView(artist, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        seek = new SeekBar(this);
        seek.setPadding(dp(4), dp(18), dp(4), dp(4));
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Theme.TRACK));
        root.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout times = new LinearLayout(this);
        pos = text("0:00", 12, Theme.SUB);
        dur = text("0:00", 12, Theme.SUB);
        dur.setGravity(Gravity.END);
        times.addView(pos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(dur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(0, dp(16), 0, 0);
        TextView prev = button("⏮", 22, v -> step(-1));
        playBtn = button("⏸", 30, v -> toggle());
        GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.OVAL);
        pg.setColor(Theme.ACCENT);
        playBtn.setBackground(pg);
        playBtn.setTextColor(Color.WHITE);
        TextView next = button("⏭", 22, v -> step(1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(60), dp(60));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(80), dp(80));
        bp.leftMargin = dp(24);
        bp.rightMargin = dp(24);
        ctl.addView(prev, sp);
        ctl.addView(playBtn, bp);
        ctl.addView(next, new LinearLayout.LayoutParams(dp(60), dp(60)));
        root.addView(ctl);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) pos.setText(time(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (player != null) player.seekTo(s.getProgress());
            }
        });
        setContentView(root);
        play();
    }

    private static String time(int ms) {
        int s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && !dragging) {
                try {
                    int p = player.getCurrentPosition();
                    seek.setProgress(p);
                    pos.setText(time(p));
                } catch (IllegalStateException ignored) {
                }
            }
            main.postDelayed(this, 500);
        }
    };

    private void play() {
        release();
        String path = paths.get(index);
        File f = new File(path);
        title.setText(f.getName().replaceFirst("\\.[^.]+$", ""));
        artist.setText((index + 1) + " / " + paths.size());
        cover.setImageDrawable(null);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (t != null && !t.trim().isEmpty()) title.setText(t);
            if (a != null && !a.trim().isEmpty()) artist.setText(a + "   •   " + (index + 1) + " / " + paths.size());
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                cover.setImageBitmap(b);
            }
        } catch (RuntimeException ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(path);
            player.setOnCompletionListener(mp -> {
                if (index + 1 < paths.size()) step(1);
                else playBtn.setText("▶");
            });
            player.prepare();
            seek.setMax(player.getDuration());
            dur.setText(time(player.getDuration()));
            player.start();
            playBtn.setText("⏸");
            main.removeCallbacks(tick);
            main.post(tick);
        } catch (Exception e) {
            artist.setText("Çalınamadı: " + e.getMessage());
            release();
        }
    }

    private void toggle() {
        if (player == null) {
            play();
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            playBtn.setText("▶");
        } else {
            player.start();
            playBtn.setText("⏸");
        }
    }

    private void step(int d) {
        index = (index + d + paths.size()) % paths.size();
        play();
    }

    private void release() {
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(tick);
        release();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
