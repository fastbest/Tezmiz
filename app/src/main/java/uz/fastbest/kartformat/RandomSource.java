package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Rastgele konumdan okunabilen veri (ISO dosyası ya da onun içindeki bir dosya). */
public interface RandomSource {
    long size() throws IOException;

    void readFully(long pos, byte[] b, int off, int len) throws IOException;

    /** Bir dosya kanalı üzerinden okuma. */
    final class Channel implements RandomSource {
        private final FileChannel ch;

        public Channel(FileChannel ch) {
            this.ch = ch;
        }

        @Override
        public long size() throws IOException {
            return ch.size();
        }

        @Override
        public void readFully(long pos, byte[] b, int off, int len) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            while (buf.hasRemaining()) {
                int r = ch.read(buf, pos + buf.position() - off);
                if (r < 0) throw new IOException("Dosyanın sonuna beklenmedik şekilde gelindi");
            }
        }
    }
}
