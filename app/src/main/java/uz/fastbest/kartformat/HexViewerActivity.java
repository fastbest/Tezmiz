package uz.fastbest.kartformat;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/** Onaltılık (hex) görüntüleyici; büyük dosyaları sayfa sayfa okur. */
public class HexViewerActivity extends Activity {

    private RandomAccessFile raf;
    private long length;
    private final byte[] page = new byte[64 * 1024];
    private long pageStart = -1;
    private int pageLen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        String path = getIntent().getStringExtra("path");
        try {
            raf = new RandomAccessFile(new File(path), "r");
            length = raf.length();
        } catch (IOException | RuntimeException e) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1F22);
        root.setFitsSystemWindows(true);
        TextView head = new TextView(this);
        head.setText(new File(path).getName() + "   " + DiskOps.sizeText(length));
        head.setTextColor(0xFFECECEC);
        head.setPadding(24, 16, 24, 16);
        head.setBackgroundColor(0xFF111214);
        root.addView(head);
        ListView list = new ListView(this);
        list.setFastScrollEnabled(true);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return (int) Math.min(Integer.MAX_VALUE, (length + 15) / 16);
            }

            @Override
            public Object getItem(int position) {
                return position;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = convertView instanceof TextView ? (TextView) convertView
                        : new TextView(HexViewerActivity.this);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(11.5f);
                t.setTextColor(0xFFDADADA);
                t.setPadding(12, 2, 12, 2);
                t.setText(line(position * 16L));
                return t;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private String line(long off) {
        StringBuilder hex = new StringBuilder(String.format(Locale.ROOT, "%08X  ", off));
        StringBuilder asc = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int b = byteAt(off + i);
            if (b < 0) {
                hex.append("   ");
            } else {
                hex.append(String.format(Locale.ROOT, "%02X ", b));
                asc.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            if (i == 7) hex.append(' ');
        }
        return hex + " " + asc;
    }

    private int byteAt(long pos) {
        if (pos >= length) return -1;
        if (pos < pageStart || pos >= pageStart + pageLen) {
            try {
                pageStart = pos / page.length * page.length;
                raf.seek(pageStart);
                pageLen = raf.read(page, 0, (int) Math.min(page.length, length - pageStart));
            } catch (IOException e) {
                return -1;
            }
        }
        return page[(int) (pos - pageStart)] & 0xFF;
    }

    @Override
    protected void onDestroy() {
        try {
            if (raf != null) raf.close();
        } catch (IOException ignored) {
        }
        super.onDestroy();
    }
}
