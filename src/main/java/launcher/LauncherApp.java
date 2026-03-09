package launcher;

import launcher.config.Config;
import launcher.ui.DownloadDialog;
import launcher.download.DownloadManager;
import launcher.download.DownloadTask;
import launcher.download.StageTasks;
import launcher.game.GameLauncher;
import launcher.github.CurrentModpackInfo;
import launcher.github.GitHubRepo;
import launcher.github.ModpackInfo;
import launcher.installer.LoaderInstaller;
import launcher.installer.MinecraftInstaller;
import launcher.installer.ModpackUpdater;
import launcher.ui.DialogUtils;
import launcher.ui.SettingsDialog;
import launcher.util.DiskSpaceChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Main application class for the modpack launcher.
 * Initializes the UI, loads configuration, checks disk space,
 * fetches pack information from GitHub, prepares download stages,
 * and launches the game.
 */
public class LauncherApp {
    private static final Logger log = LoggerFactory.getLogger(LauncherApp.class);

    // GitHub repository base URL and local directories
    public static final String REPO_BASE_URL = "<Paste GitHub url>";
    public static final Path LAUNCHER_DIR = Paths.get(System.getProperty("user.dir"));
    public static final Path INSTANCES_DIR = LAUNCHER_DIR.resolve("instances");
    public static final Path META_DIR = LAUNCHER_DIR.resolve("meta");
    public static final Path CONFIG_FILE = LAUNCHER_DIR.resolve("config.json");
    public static final long REQUIRED_SPACE = 5L * 1024 * 1024 * 1024; // 5 GB
    public static final String GITHUB_TOKEN = "<Paste GitHub Token>";

    private static Config config;

    public static void main(String[] args) {
        // Set FlatLaf dark theme
        try {
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf");
        } catch (Exception e) {
            log.error("Failed to load FlatLaf", e);
            JOptionPane.showMessageDialog(null,
                    "Failed to load dark theme.\nMake sure flatlaf.jar is in classpath.",
                    "Theme Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Load configuration; if username or RAM not set, show settings dialog
        config = Config.load();
        if (config.getUsername() == null || config.getRamMB() == 0) {
            SwingUtilities.invokeLater(SettingsDialog::new);
        } else {
            launch();
        }
    }

    /**
     * Main launch sequence: checks disk space, fetches current modpack info,
     * determines the active branch, prepares download stages, and starts the download.
     */
    public static void launch() {
        log.info("Launching launcher for user {}", config.getUsername());

        // Verify sufficient disk space
        if (!DiskSpaceChecker.check(LAUNCHER_DIR, REQUIRED_SPACE)) {
            DialogUtils.showError(null, "Insufficient disk space",
                    String.format("Required: %.2f GB\nFree: %.2f GB",
                            REQUIRED_SPACE / 1e9,
                            DiskSpaceChecker.getFreeSpace(LAUNCHER_DIR) / 1e9));
            System.exit(1);
        }

        // Fetch current_modpack.json to get the active branch and enabled status
        CurrentModpackInfo currentInfo;
        try {
            currentInfo = CurrentModpackInfo.fetch(REPO_BASE_URL);
        } catch (Exception e) {
            log.error("Failed to fetch current_modpack.json", e);
            DialogUtils.showError(null, "Failed to load pack info",
                    "Could not load current_modpack.json from repository.\n" + e.getMessage());
            System.exit(1);
            return;
        }

        // If the pack is disabled globally, show message and exit
        if (!currentInfo.isEnabled()) {
            log.info("Pack is disabled, showing message");
            SwingUtilities.invokeLater(() ->
                    DialogUtils.showInfo(null, "Pack Disabled", "Pack is disabled\nPlease try again later")
            );
            System.exit(0);
            return;
        }

        String targetBranch = currentInfo.getBranch();
        log.info("Active pack branch: {}", targetBranch);
        Path instanceDir = INSTANCES_DIR.resolve(targetBranch);

        // Create necessary directories
        try {
            Files.createDirectories(instanceDir);
            Files.createDirectories(META_DIR);
        } catch (Exception e) {
            log.error("Failed to prepare instance", e);
            DialogUtils.showError(null, "Error", "Could not initialize instance: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Create GitHub repository object for the target branch
        GitHubRepo targetRepo;
        try {
            targetRepo = GitHubRepo.fromBaseAndBranch(REPO_BASE_URL, targetBranch);
        } catch (Exception e) {
            log.error("Failed to create repo for branch {}", targetBranch, e);
            DialogUtils.showError(null, "Error", "Could not switch to branch " + targetBranch);
            System.exit(1);
            return;
        }

        // Fetch modpack.json from the branch to get loader, MC version etc.
        ModpackInfo modpackInfo;
        try {
            modpackInfo = ModpackInfo.fetch(targetRepo);
        } catch (Exception e) {
            log.error("Failed to get modpack.json from branch {}", targetBranch, e);
            DialogUtils.showError(null, "Failed to load pack info",
                    "Could not load modpack.json from branch " + targetBranch + "\n" + e.getMessage());
            System.exit(1);
            return;
        }

        // Check if the pack is enabled or under maintenance in this branch
        if (!modpackInfo.isEnabled()) {
            DialogUtils.showInfo(null, "Pack disabled", "Pack in this branch is disabled\nPlease try later");
            System.exit(0);
        }
        if (modpackInfo.isTechnicalWork()) {
            DialogUtils.showInfo(null, "Maintenance", "Pack is under maintenance\nPlease try later");
            System.exit(0);
        }

        // Build download stages (vanilla, loader, modpack files, big files)
        List<StageTasks> stages = new ArrayList<>();

        try {
            // Stage 1: Vanilla Minecraft
            StageTasks vanillaStage = new StageTasks("Installing Minecraft " + modpackInfo.getMcVersion());
            MinecraftInstaller.collectVanillaTasks(modpackInfo.getMcVersion(), instanceDir, vanillaStage.tasks);
            if (!vanillaStage.tasks.isEmpty()) stages.add(vanillaStage);

            // Stage 2: Mod loader (if not vanilla)
            if (!"vanilla".equalsIgnoreCase(modpackInfo.getLoader())) {
                StageTasks loaderStage = new StageTasks("Installing " + modpackInfo.getLoader() + " loader");
                LoaderInstaller installer = LoaderInstaller.create(modpackInfo.getLoader());
                installer.collectTasks(modpackInfo.getMcVersion(), modpackInfo.getLoaderVersion(), instanceDir, loaderStage.tasks);
                if (!loaderStage.tasks.isEmpty()) stages.add(loaderStage);
            }

            // Stage 3: Modpack files (configs, mods, etc.)
            StageTasks modpackStage = new StageTasks("Updating modpack");
            ModpackUpdater.collectUpdateTasks(targetRepo, modpackInfo.getLoader(), instanceDir, modpackStage.tasks);
            if (!modpackStage.tasks.isEmpty()) stages.add(modpackStage);

            // Stage 4: Large files listed in 25MB_dl.json
            StageTasks bigFilesStage = new StageTasks("Downloading large files");
            ModpackUpdater.collectBigFilesTasks(targetRepo, instanceDir, bigFilesStage.tasks);
            if (!bigFilesStage.tasks.isEmpty()) stages.add(bigFilesStage);

        } catch (Exception e) {
            log.error("Failed to collect download tasks", e);
            DialogUtils.showError(null, "Error", "Failed to prepare download list: " + e.getMessage());
            System.exit(1);
            return;
        }

        long totalBytes = stages.stream().flatMap(s -> s.tasks.stream()).mapToLong(t -> t.size).sum();
        log.info("Total download size: {} bytes", totalBytes);

        // Show download dialog
        DownloadDialog dialog = new DownloadDialog(null, totalBytes);
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();

        try {
            DownloadManager downloadManager = new DownloadManager(dialog, Runtime.getRuntime().availableProcessors());
            dialog.setDownloadManager(downloadManager);

            // Execute stages sequentially
            for (StageTasks stage : stages) {
                if (downloadManager.isCancelled()) throw new InterruptedException();
                long stageSize = stage.tasks.stream().mapToLong(t -> t.size).sum();
                dialog.startStage(stage.name, stageSize);
                for (DownloadTask task : stage.tasks) {
                    if (downloadManager.isCancelled()) throw new InterruptedException();
                    downloadManager.submit(task.url, task.target, task.size, task.label);
                }
                downloadManager.awaitTermination();
                if (downloadManager.isCancelled()) throw new InterruptedException();
                dialog.stageCompleted();
            }

            downloadManager.shutdown();

            // Launch Minecraft process
            Process minecraftProcess = GameLauncher.launch(config, modpackInfo, instanceDir);
            dialog.closeDialog();

            // Wait for Minecraft to exit and pass its exit code
            int exitCode = minecraftProcess.waitFor();
            System.exit(exitCode);

        } catch (InterruptedException e) {
            log.info("Download cancelled by user");
            DialogUtils.showInfo(null, "Cancelled", "Download aborted");
            dialog.closeDialog();
            System.exit(0);
        } catch (Exception e) {
            log.error("Critical error", e);
            DialogUtils.showError(null, "Error", e.getMessage());
            dialog.closeDialog();
            System.exit(1);
        }
    }

    public static Config getConfig() { return config; }
}