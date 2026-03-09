package launcher.download;

import java.nio.file.Path;

/**
 * Represents a single file download task.
 */
public class DownloadTask {
    public String url;
    public Path target;
    public long size;
    public String label;

    public DownloadTask(String url, Path target, long size, String label) {
        this.url = url;
        this.target = target;
        this.size = size;
        this.label = label;
    }
}