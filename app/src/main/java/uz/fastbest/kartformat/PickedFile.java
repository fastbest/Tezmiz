package uz.fastbest.kartformat;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** Telefonda seçilen bir dosya; rastgele okunabilir. Kapatılınca tanımlayıcı da kapanır. */
final class PickedFile implements Closeable {
    final ParcelFileDescriptor pfd;
    final FileInputStream in;
    final FileChannel channel;
    final RandomSource source;
    final String name;

    PickedFile(ContentResolver cr, Uri uri) throws IOException {
        String n = "dosya";
        try (Cursor cur = cr.query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && !cur.isNull(ni)) n = cur.getString(ni);
            }
        } catch (RuntimeException ignored) {
        }
        name = n;
        pfd = cr.openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("dosya açılamadı");
        in = new FileInputStream(pfd.getFileDescriptor());
        channel = in.getChannel();
        source = new RandomSource.Channel(channel);
        try {
            source.size();
        } catch (IOException e) {
            close();
            throw new IOException("Bu dosya doğrudan okunamıyor; önce telefon belleğine "
                    + "(İndirilenler) kopyalayın");
        }
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
    }
}
