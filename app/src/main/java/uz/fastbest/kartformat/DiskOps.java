package uz.fastbest.kartformat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** İmaj yazma, doğrulama, silme, yedekleme ve hız testi işlemleri. */
public final class DiskOps {

    public interface Cancel {
        boolean isCancelled();
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("İptal edildi");
        }
    }

    private static final int CHUNK_BYTES = 64 * 1024;
    private static final long MB = 1024L * 1024L;

    private DiskOps() {
    }

    private static void check(Cancel c) throws CancelledException {
        if (c != null && c.isCancelled()) throw new CancelledException();
    }

    private static byte[] chunkBuffer(BlockDevice dev) {
        int bs = dev.blockSize();
        return new byte[Math.max(1, CHUNK_BYTES / bs) * bs];
    }

    public static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(buf, off + total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    /** Dosyanın ilk baytlarına bakarak sıkıştırılmış olup olmadığını söyler. */
    public static boolean isCompressed(byte[] m, int n) {
        return isGzip(m, n) || isZip(m, n) || isXz(m, n);
    }

    private static boolean isGzip(byte[] m, int n) {
        return n >= 2 && (m[0] & 0xFF) == 0x1F && (m[1] & 0xFF) == 0x8B;
    }

    private static boolean isZip(byte[] m, int n) {
        return n >= 4 && m[0] == 'P' && m[1] == 'K' && m[2] == 3 && m[3] == 4;
    }

    private static boolean isXz(byte[] m, int n) {
        return n >= 6 && (m[0] & 0xFF) == 0xFD && m[1] == '7' && m[2] == 'z'
                && m[3] == 'X' && m[4] == 'Z' && m[5] == 0;
    }

    /** Ham, .gz ya da .zip içindeki imajı okunabilir akışa çevirir. */
    public static InputStream openImage(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 65536);
        in.mark(16);
        byte[] m = new byte[6];
        int n = readFully(in, m, 0, 6);
        in.reset();
        if (isGzip(m, n)) {
            return new BufferedInputStream(new GZIPInputStream(in, 65536), 65536);
        }
        if (isZip(m, n)) {
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().toLowerCase(Locale.ROOT);
                if (!e.isDirectory() && (name.endsWith(".img") || name.endsWith(".iso")
                        || name.endsWith(".raw") || name.endsWith(".bin")
                        || name.endsWith(".dd"))) {
                    return new BufferedInputStream(z, 65536);
                }
            }
            throw new IOException("ZIP içinde .img ya da .iso dosyası bulunamadı");
        }
        if (isXz(m, n)) {
            throw new IOException(".xz sıkıştırması desteklenmiyor; dosyayı önce açın");
        }
        return in;
    }

    /** İmajın ilk sektöründe önyükleme imzası var mı (doğrudan yazmaya uygun mu)? */
    public static boolean looksBootable(byte[] head, int n) {
        return n >= 512 && (head[510] & 0xFF) == 0x55 && (head[511] & 0xFF) == 0xAA;
    }

    /** İmajı karta ham olarak yazar; yazılan bayt sayısını döndürür. */
    public static long writeImage(BlockDevice dev, InputStream in, long totalBytes,
                                  Fat32Formatter.Progress p, Cancel c) throws IOException {
        int bs = dev.blockSize();
        byte[] buf = chunkBuffer(dev);
        long capacity = dev.blockCount() * bs;
        if (totalBytes > capacity) throw new IOException("İmaj karttan büyük");
        long lba = 0;
        long written = 0;
        while (true) {
            check(c);
            int n = readFully(in, buf, 0, buf.length);
            if (n <= 0) break;
            int blocks = (n + bs - 1) / bs;
            if (n < blocks * bs) Arrays.fill(buf, n, blocks * bs, (byte) 0);
            if (lba + blocks > dev.blockCount()) throw new IOException("İmaj karttan büyük");
            dev.write(lba, buf, blocks);
            lba += blocks;
            written += n;
            if (p != null) p.onProgress(written, totalBytes);
            if (n < buf.length) break;
        }
        if (written == 0) throw new IOException("İmaj dosyası boş");
        dev.flush();
        return written;
    }

    /** Karta yazılanı imajla bayt bayt karşılaştırır. */
    public static void verifyImage(BlockDevice dev, InputStream in, long bytes,
                                   Fat32Formatter.Progress p, Cancel c) throws IOException {
        int bs = dev.blockSize();
        byte[] src = chunkBuffer(dev);
        byte[] got = new byte[src.length];
        long lba = 0;
        long done = 0;
        while (done < bytes) {
            check(c);
            int want = (int) Math.min(src.length, bytes - done);
            int n = readFully(in, src, 0, want);
            if (n != want) throw new IOException("Doğrulama sırasında imaj dosyası okunamadı");
            int blocks = (n + bs - 1) / bs;
            dev.read(lba, got, blocks);
            for (int i = 0; i < n; i++) {
                if (src[i] != got[i]) {
                    throw new IOException("Doğrulama başarısız: kart " + ((done + i) / MB)
                            + ". MB'ta farklı veri döndürdü. Kart bozuk ya da sahte olabilir");
                }
            }
            lba += blocks;
            done += n;
            if (p != null) p.onProgress(done, bytes);
        }
    }

    /** Kartı sıfırla doldurur. Hızlı modda yalnızca baştaki ve sondaki 16 MB silinir. */
    public static void wipe(BlockDevice dev, boolean full, Fat32Formatter.Progress p, Cancel c)
            throws IOException {
        int bs = dev.blockSize();
        byte[] zero = chunkBuffer(dev);
        int chunkBlocks = zero.length / bs;
        long count = dev.blockCount();
        if (full) {
            zeroRange(dev, 0, count, zero, chunkBlocks, 0, count * bs, p, c);
        } else {
            long edge = Math.min(count, 16 * MB / bs);
            long tailStart = Math.max(edge, count - edge);
            long total = (edge + (count - tailStart)) * bs;
            zeroRange(dev, 0, edge, zero, chunkBlocks, 0, total, p, c);
            zeroRange(dev, tailStart, count - tailStart, zero, chunkBlocks, edge * bs, total, p, c);
        }
        dev.flush();
    }

    private static void zeroRange(BlockDevice dev, long start, long blocks, byte[] zero,
                                  int chunkBlocks, long doneBefore, long total,
                                  Fat32Formatter.Progress p, Cancel c) throws IOException {
        int bs = dev.blockSize();
        long pos = 0;
        while (pos < blocks) {
            check(c);
            int n = (int) Math.min(chunkBlocks, blocks - pos);
            dev.write(start + pos, zero, n);
            pos += n;
            if (p != null) p.onProgress(doneBefore + pos * bs, total);
        }
    }

    /** Kartın tamamını akışa kopyalar; kopyalanan bayt sayısını döndürür. */
    public static long backup(BlockDevice dev, OutputStream out, Fat32Formatter.Progress p,
                              Cancel c) throws IOException {
        int bs = dev.blockSize();
        byte[] buf = chunkBuffer(dev);
        int chunkBlocks = buf.length / bs;
        long count = dev.blockCount();
        long total = count * bs;
        long pos = 0;
        while (pos < count) {
            check(c);
            int n = (int) Math.min(chunkBlocks, count - pos);
            dev.read(pos, buf, n);
            out.write(buf, 0, n * bs);
            pos += n;
            if (p != null) p.onProgress(pos * bs, total);
        }
        out.flush();
        return total;
    }

    /** Sıralı okuma, sıralı yazma ve rastgele okuma hızını ölçer; karttaki veriyi değiştirmez. */
    public static String benchmark(BlockDevice dev, Fat32Formatter.Progress p, Cancel c)
            throws IOException {
        int bs = dev.blockSize();
        byte[] buf = chunkBuffer(dev);
        int chunkBlocks = buf.length / bs;
        long count = dev.blockCount();

        long readBlocks = Math.min(count, 32 * MB / bs);
        long writeBlocks = Math.min(count, 16 * MB / bs);
        int randomReads = 200;
        long total = (readBlocks + 2 * writeBlocks) * bs + randomReads * 4096L;
        long done = 0;

        // Sıralı okuma
        long t0 = System.nanoTime();
        for (long pos = 0; pos < readBlocks; ) {
            check(c);
            int n = (int) Math.min(chunkBlocks, readBlocks - pos);
            dev.read(pos, buf, n);
            pos += n;
            done += (long) n * bs;
            if (p != null) p.onProgress(done, total);
        }
        double readSpeed = speed(readBlocks * bs, System.nanoTime() - t0);

        // Sıralı yazma: kartın ortasındaki bölgeyi okuyup aynı veriyi geri yazar
        long start = (count - writeBlocks) / 2;
        byte[] region = new byte[(int) (writeBlocks * bs)];
        for (long pos = 0; pos < writeBlocks; ) {
            check(c);
            int n = (int) Math.min(chunkBlocks, writeBlocks - pos);
            dev.read(start + pos, buf, n);
            System.arraycopy(buf, 0, region, (int) (pos * bs), n * bs);
            pos += n;
            done += (long) n * bs;
            if (p != null) p.onProgress(done, total);
        }
        t0 = System.nanoTime();
        for (long pos = 0; pos < writeBlocks; ) {
            // Yarım kalmasın diye bu aşamada iptal edilmez
            int n = (int) Math.min(chunkBlocks, writeBlocks - pos);
            System.arraycopy(region, (int) (pos * bs), buf, 0, n * bs);
            dev.write(start + pos, buf, n);
            pos += n;
            done += (long) n * bs;
            if (p != null) p.onProgress(done, total);
        }
        dev.flush();
        double writeSpeed = speed(writeBlocks * bs, System.nanoTime() - t0);

        // Rastgele 4 KB okuma
        int smallBlocks = Math.max(1, 4096 / bs);
        Random rnd = new Random(System.nanoTime());
        t0 = System.nanoTime();
        for (int i = 0; i < randomReads; i++) {
            check(c);
            long lba = (long) (rnd.nextDouble() * (count - smallBlocks));
            dev.read(lba, buf, smallBlocks);
            done += 4096;
            if (p != null) p.onProgress(done, total);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        double iops = seconds > 0 ? randomReads / seconds : 0;
        if (p != null) p.onProgress(total, total);

        return String.format(Locale.ROOT,
                "Sıralı okuma: %.1f MB/sn\nSıralı yazma: %.1f MB/sn\n"
                        + "Rastgele okuma (4 KB): %.0f işlem/sn\n\nKarttaki veriler değiştirilmedi.",
                readSpeed, writeSpeed, iops);
    }

    private static double speed(long bytes, long nanos) {
        return nanos <= 0 ? 0 : (bytes / (double) MB) / (nanos / 1e9);
    }

    public static String sizeText(long bytes) {
        if (bytes >= 1000000000L) return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        if (bytes >= 1000000L) return String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1e3);
    }
}
