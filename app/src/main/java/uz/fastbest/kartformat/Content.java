package uz.fastbest.kartformat;

import java.io.IOException;

/** Karta yazılacak bir dosyanın içeriği. */
interface Content {
    long size();

    void writeTo(Sink out) throws IOException;

    interface Sink {
        void write(byte[] b, int off, int len) throws IOException;
    }

    /** Bellekteki bayt dizisi. */
    final class Bytes implements Content {
        private final byte[] data;

        Bytes(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public void writeTo(Sink out) throws IOException {
            out.write(data, 0, data.length);
        }
    }

    /** Bir kaynağın belirli bir aralığı. */
    final class Range implements Content {
        private final RandomSource src;
        private final long offset;
        private final long length;

        Range(RandomSource src, long offset, long length) {
            this.src = src;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public long size() {
            return length;
        }

        @Override
        public void writeTo(Sink out) throws IOException {
            copy(src, offset, length, out);
        }
    }

    int COPY_CHUNK = 1024 * 1024;

    static void copy(RandomSource src, long offset, long length, Sink out) throws IOException {
        byte[] buf = new byte[(int) Math.min(COPY_CHUNK, Math.max(1, length))];
        long done = 0;
        while (done < length) {
            int n = (int) Math.min(buf.length, length - done);
            src.readFully(offset + done, buf, 0, n);
            out.write(buf, 0, n);
            done += n;
        }
    }
}
