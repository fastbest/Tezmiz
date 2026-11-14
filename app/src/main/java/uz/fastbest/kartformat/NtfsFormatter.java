package uz.fastbest.kartformat;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * NTFS biçimlendirme. ntfs-3g projesinin mkntfs aracı telefonda seyrek (boş alanı yer
 * kaplamayan) bir geçici dosyaya NTFS yapısını yazar; yazılan bölgeler karta kopyalanır.
 */
final class NtfsFormatter implements DiskFormatter.VolumeWriter {

    /** MainActivity tarafından ayarlanır. */
    static File tool;
    static File workDir;

    private static final int SEEK_DATA = 3;
    private static final int SEEK_HOLE = 4;
    private static final int CHUNK = 1024 * 1024;
    private static final long ESTIMATE_BYTES = 80L * 1024 * 1024;

    private final String label;
    private final int clusterBytes;

    NtfsFormatter(String label, int clusterBytes) {
        this.label = label;
        this.clusterBytes = clusterBytes;
    }

    static boolean available() {
        return tool != null && tool.canExecute() && workDir != null;
    }

    @Override
    public long estimateBlocks(long sectors) throws IOException {
        if (!available()) {
            throw new IOException("NTFS aracı (mkntfs) bu cihazda çalıştırılamıyor");
        }
        if (sectors < 2048 * 8) throw new IOException("Bölüm NTFS için çok küçük");
        if (workDir.getUsableSpace() < 200L * 1024 * 1024) {
            throw new IOException("Telefonda en az 200 MB boş alan gerekiyor (geçici dosya için)");
        }
        return Math.min(sectors, ESTIMATE_BYTES / 512);
    }

    @Override
    public void write(BlockDevice dev, long start, long sectors, DiskFormatter.Tracker t)
            throws IOException {
        File img = new File(workDir, "ntfs-" + System.nanoTime() + ".img");
        try {
            try (RandomAccessFile f = new RandomAccessFile(img, "rw")) {
                f.setLength(sectors * 512);
            }
            runMkntfs(img, start, sectors);
            copyWritten(img, dev, start, sectors, t);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            img.delete();
        }
    }

    private void runMkntfs(File img, long start, long sectors) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(tool.getAbsolutePath());
        cmd.add("-F");                      // aygıt değil, dosya
        cmd.add("-Q");                      // hızlı (bölümü sıfırlamadan)
        cmd.add("-s");
        cmd.add("512");
        cmd.add("-p");
        cmd.add(String.valueOf(start));     // bölümün kart üzerindeki başlangıcı
        cmd.add("-H");
        cmd.add("255");
        cmd.add("-S");
        cmd.add("63");
        if (clusterBytes > 0) {
            cmd.add("-c");
            cmd.add(String.valueOf(clusterBytes));
        }
        String l = label == null ? "" : label.trim();
        if (!l.isEmpty()) {
            cmd.add("-L");
            cmd.add(l);
        }
        cmd.add(img.getAbsolutePath());
        cmd.add(String.valueOf(sectors));

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() < 64 * 1024) out.write(buf, 0, n);
            }
        }
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroy();
                throw new IOException("mkntfs yanıt vermedi");
            }
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("İptal edildi");
        }
        if (p.exitValue() != 0) {
            String msg = out.toString().trim();
            if (msg.length() > 400) msg = msg.substring(msg.length() - 400);
            throw new IOException("mkntfs hata verdi (" + p.exitValue() + "): " + msg);
        }
    }

    /** Geçici dosyada yazılmış (boşluk olmayan) bölgeleri karta kopyalar. */
    private static void copyWritten(File img, BlockDevice dev, long start, long sectors,
                                    DiskFormatter.Tracker t) throws IOException {
        long size = sectors * 512;
        byte[] buf = new byte[CHUNK];
        try (RandomAccessFile f = new RandomAccessFile(img, "r")) {
            FileDescriptor fd = f.getFD();
            List<long[]> ranges = dataRanges(fd, size);
            for (long[] r : ranges) {
                long pos = r[0] / 512 * 512;
                long end = Math.min(size, (r[1] + 511) / 512 * 512);
                while (pos < end) {
                    int n = (int) Math.min(buf.length, end - pos);
                    f.seek(pos);
                    f.readFully(buf, 0, n);
                    dev.write(start + pos / 512, buf, n / 512);
                    t.add(n / 512);
                    pos += n;
                }
            }
        }
    }

    /** SEEK_DATA/SEEK_HOLE ile yazılmış bölgeleri bulur; desteklenmezse tüm dosya. */
    private static List<long[]> dataRanges(FileDescriptor fd, long size) {
        List<long[]> out = new ArrayList<>();
        try {
            long pos = 0;
            while (pos < size) {
                long data;
                try {
                    data = Os.lseek(fd, pos, SEEK_DATA);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.ENXIO) break; // sonrası tamamen boş
                    throw e;
                }
                long hole = Os.lseek(fd, data, SEEK_HOLE);
                out.add(new long[]{data, Math.min(hole, size)});
                pos = hole;
            }
            return out;
        } catch (ErrnoException e) {
            out.clear();
            out.add(new long[]{0, size});
            return out;
        }
    }
}
