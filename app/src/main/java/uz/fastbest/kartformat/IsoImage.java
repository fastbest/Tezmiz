package uz.fastbest.kartformat;

import java.io.IOException;
import java.io.InputStream;

/** ISO dosyasını önce UDF, olmazsa ISO 9660 olarak açar. */
final class IsoImage {

    private IsoImage() {
    }

    static UdfReader.Volume open(RandomSource src) throws IOException {
        IOException udfError;
        try {
            UdfReader.Volume v = UdfReader.read(src);
            if (!v.root.kids.isEmpty()) return v;
            udfError = null;
        } catch (IOException e) {
            udfError = e;
        }
        try {
            return Iso9660Reader.read(src);
        } catch (IOException e) {
            throw udfError != null && e.getMessage().contains("ISO görüntüsü değil")
                    ? new IOException("Bu dosya tanınan bir ISO görüntüsü değil") : e;
        }
    }

    /** Rastgele okunabilir bir kaynağın bir aralığını akış olarak verir. */
    static final class Stream extends InputStream {
        private final RandomSource src;
        private long pos;
        private final long end;

        Stream(RandomSource src, long offset, long length) {
            this.src = src;
            this.pos = offset;
            this.end = offset + length;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= end) return -1;
            int n = (int) Math.min(len, end - pos);
            src.readFully(pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
