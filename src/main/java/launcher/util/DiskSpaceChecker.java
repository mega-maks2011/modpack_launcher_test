package launcher.util;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks available disk space.
 */
public class DiskSpaceChecker {
    /**
     * Returns true if the path has at least requiredBytes of free space.
     */
    public static boolean check(Path path, long requiredBytes) {
        try {
            FileStore store = Files.getFileStore(path);
            return store.getUsableSpace() >= requiredBytes;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the free space (in bytes) on the file system containing the path.
     */
    public static long getFreeSpace(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            return store.getUsableSpace();
        } catch (IOException e) {
            return 0;
        }
    }
}