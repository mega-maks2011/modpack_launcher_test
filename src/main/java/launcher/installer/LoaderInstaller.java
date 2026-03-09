package launcher.installer;

import launcher.download.DownloadTask;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for mod loader installers.
 */
public interface LoaderInstaller {
    void collectTasks(String mcVersion, String loaderVersion, Path instanceDir, List<DownloadTask> tasks) throws Exception;

    static LoaderInstaller create(String loader) {
        switch (loader) {
            case "fabric": return new FabricInstaller();
            case "quilt": return new QuiltInstaller();
            default: throw new IllegalArgumentException("Unsupported loader: " + loader + ". Supported: fabric, quilt, vanilla");
        }
    }
}