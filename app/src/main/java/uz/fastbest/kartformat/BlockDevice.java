package uz.fastbest.kartformat;

import java.io.IOException;

/** Sektör sektör okunup yazılabilen ham depolama aygıtı. */
public interface BlockDevice {
    int blockSize();

    long blockCount();

    void read(long lba, byte[] buf, int blocks) throws IOException;

    void write(long lba, byte[] buf, int blocks) throws IOException;

    void flush() throws IOException;
}
