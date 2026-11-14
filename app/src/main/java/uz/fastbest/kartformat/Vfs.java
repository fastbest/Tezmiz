package uz.fastbest.kartformat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Ortak dosya sistemi arayüzü: telefon belleği, USB kart, ZIP, FTP, Kasa. */
public interface Vfs {

    /** Gösterilecek ad ("Dahili depolama", "USB kart" ...). */
    String title();

    VNode root();

    List<VNode> list(VNode dir) throws IOException;

    InputStream open(VNode file) throws IOException;

    /** Akıştaki veriden dosya oluşturur; aynı ad varsa "ad (2)" olur. */
    VNode create(VNode dir, String name, InputStream in, long size, Fat32Formatter.Progress p,
                 DiskOps.Cancel c) throws IOException;

    /** Yeni klasör; aynı ad varsa "ad (2)" olur. */
    VNode mkdir(VNode dir, String name) throws IOException;

    /** Dosya ya da klasörü (içiyle) siler. */
    void delete(VNode n, DiskOps.Cancel c) throws IOException;

    VNode rename(VNode n, String newName) throws IOException;

    /** Aynı dosya sistemi içinde taşır; desteklenmiyorsa null döner (kopyala+sil yapılır). */
    VNode move(VNode n, VNode dir) throws IOException;

    boolean writable();

    /** Bilinmiyorsa -1. */
    long freeBytes();

    long totalBytes();

    /** Telefon belleğindeki gerçek dosya; diğer dosya sistemlerinde null. */
    File localFile(VNode n);

    /** Kapatılırken (USB çıkarılınca, FTP bağlantısı kesilince). */
    void close();
}
