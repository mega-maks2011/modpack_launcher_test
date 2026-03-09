package launcher.download;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a download stage with a name and a list of tasks.
 */
public class StageTasks {
    public String name;
    public List<DownloadTask> tasks = new ArrayList<>();

    public StageTasks(String name) {
        this.name = name;
    }
}