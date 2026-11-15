package uz.fastbest.kartformat;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;

/** Video oynatıcı: tam ekran, dokununca denetim çubuğu, klasördeki önceki/sonraki video. */
public class VideoActivity extends Activity {

    private ArrayList<String> paths;
    private int index;
    private VideoView video;
    private MediaController ctl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Theme.widgetTheme(this));
        super.onCreate(savedInstanceState);
        L.init(this);
        paths = getIntent().getStringArrayListExtra("paths");
        index = getIntent().getIntExtra("index", 0);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(Color.BLACK);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        ctl = new MediaController(this);
        ctl.setAnchorView(video);
        ctl.setPrevNextListeners(v -> step(1), v -> step(-1));
        video.setMediaController(ctl);
        video.setOnCompletionListener(mp -> {
            if (index + 1 < paths.size()) step(1);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, L.t("Bu video oynatılamıyor; başka uygulamayla açmayı deneyin"),
                    Toast.LENGTH_LONG).show();
            return true;
        });
        root.setOnClickListener(v -> ctl.show());
        play(savedInstanceState == null ? 0 : savedInstanceState.getInt("pos", 0));
    }

    private void play(int at) {
        File f = new File(paths.get(index));
        setTitle(f.getName());
        video.setVideoURI(Uri.fromFile(f));
        if (at > 0) video.seekTo(at);
        video.start();
        video.postDelayed(() -> {
            if (!isFinishing()) ctl.show(2500);
        }, 300);
        Toast.makeText(this, L.t(f.getName()), Toast.LENGTH_SHORT).show();
    }

    private void step(int d) {
        index = (index + d + paths.size()) % paths.size();
        play(0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (video != null) out.putInt("pos", video.getCurrentPosition());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null) video.pause();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @SuppressWarnings("unused")
    private void hideSystemUi(View v) {
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
