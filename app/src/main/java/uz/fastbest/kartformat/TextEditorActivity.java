package uz.fastbest.kartformat;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Basit metin görüntüleyici / düzenleyici (UTF-8; değilse Windows-1254). */
public class TextEditorActivity extends Activity {

    private static final int MAX = 4 * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private File file;
    private boolean readOnly;
    private boolean dirty;
    private Charset charset = StandardCharsets.UTF_8;
    private EditText edit;
    private TextView info;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra("path");
        if (path == null) {
            finish();
            return;
        }
        file = new File(path);
        readOnly = getIntent().getBooleanExtra("readOnly", false) || !file.canWrite();
        String title = getIntent().getStringExtra("title");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1F22);
        root.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF111214);
        bar.setPadding(32, 16, 16, 16);
        info = new TextView(this);
        info.setTextColor(0xFFECECEC);
        info.setTextSize(16);
        info.setSingleLine(true);
        info.setText(title != null ? title : file.getName());
        bar.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!readOnly) {
            TextView save = new TextView(this);
            save.setText("💾 Kaydet");
            save.setTextColor(0xFF4FC3F7);
            save.setTextSize(16);
            save.setPadding(24, 8, 24, 8);
            save.setOnClickListener(v -> save(false));
            bar.addView(save);
        }
        root.addView(bar);
        edit = new EditText(this);
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setTextSize(14);
        edit.setTextColor(0xFFE0E0E0);
        edit.setBackgroundColor(0xFF1E1F22);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setHorizontallyScrolling(false);
        edit.setPadding(24, 16, 24, 16);
        if (readOnly) {
            edit.setKeyListener(null);
            edit.setTextIsSelectable(true);
        }
        root.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        new Thread(() -> {
            String text;
            String err = null;
            boolean truncated = false;
            try (InputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, Math.min(n, MAX - bo.size()));
                    if (bo.size() >= MAX) {
                        truncated = true;
                        break;
                    }
                }
                byte[] data = bo.toByteArray();
                try {
                    text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data)).toString();
                } catch (CharacterCodingException e) {
                    charset = Charset.forName("windows-1254");
                    text = new String(data, charset);
                }
            } catch (IOException e) {
                text = "";
                err = e.getMessage();
            }
            final String t = text;
            final String fe = err;
            final boolean tr = truncated;
            main.post(() -> {
                if (fe != null) {
                    Toast.makeText(this, "Açılamadı: " + fe, Toast.LENGTH_LONG).show();
                    return;
                }
                edit.setText(t);
                if (tr) {
                    readOnly = true;
                    edit.setKeyListener(null);
                    info.setText(info.getText() + "  (ilk 4 MB, salt okunur)");
                } else if (readOnly) {
                    info.setText(info.getText() + "  (salt okunur)");
                }
                edit.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        dirty = true;
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                    }
                });
            });
        }).start();
    }

    private void save(boolean thenClose) {
        if (readOnly) return;
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(edit.getText().toString().getBytes(charset));
            dirty = false;
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show();
            if (thenClose) finish();
        } catch (IOException e) {
            Toast.makeText(this, "Kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (dirty && !readOnly) {
            new AlertDialog.Builder(this).setTitle("Değişiklikler kaydedilsin mi?")
                    .setPositiveButton("Kaydet", (d, w) -> save(true))
                    .setNegativeButton("Kaydetme", (d, w) -> finish())
                    .setNeutralButton("Vazgeç", null).show();
            return;
        }
        super.onBackPressed();
    }
}
