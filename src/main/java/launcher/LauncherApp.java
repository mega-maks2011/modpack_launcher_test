package launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LauncherApp {
    private static final Logger log = LoggerFactory.getLogger(LauncherApp.class);
    public static final String REPO_BASE_URL = "";
    public static final Path LAUNCHER_DIR = Paths.get(System.getProperty("user.dir"));
    public static final Path INSTANCES_DIR = LAUNCHER_DIR.resolve("instances");
    public static final Path META_DIR = LAUNCHER_DIR.resolve("meta");
    public static final Path CONFIG_FILE = LAUNCHER_DIR.resolve("config.json");
    public static final long REQUIRED_SPACE = 5L * 1024 * 1024 * 1024;
    private static final String GITHUB_TOKEN = "";

    private static Config config;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf");
        } catch (Exception e) {
            log.error("Failed to load FlatLaf", e);
            JOptionPane.showMessageDialog(null,
                    "Failed to load dark theme.\nMake sure flatlaf.jar is in classpath.",
                    "Theme Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        config = Config.load();
        if (config.getUsername() == null || config.getRamMB() == 0) {
            SwingUtilities.invokeLater(SettingsDialog::new);
        } else {
            launch();
        }
    }

    public static void launch() {
        log.info("Launching launcher for user {}", config.getUsername());

        if (!DiskSpaceChecker.check(LAUNCHER_DIR, REQUIRED_SPACE)) {
            DialogUtils.showError(null, "Insufficient disk space",
                    String.format("Required: %.2f GB\nFree: %.2f GB",
                            REQUIRED_SPACE / 1e9,
                            DiskSpaceChecker.getFreeSpace(LAUNCHER_DIR) / 1e9));
            System.exit(1);
        }

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

        try {
            Files.createDirectories(instanceDir);
            Files.createDirectories(META_DIR);
        } catch (Exception e) {
            log.error("Failed to prepare instance", e);
            DialogUtils.showError(null, "Error", "Could not initialize instance: " + e.getMessage());
            System.exit(1);
            return;
        }

        GitHubRepo targetRepo;
        try {
            targetRepo = GitHubRepo.fromBaseAndBranch(REPO_BASE_URL, targetBranch);
        } catch (Exception e) {
            log.error("Failed to create repo for branch {}", targetBranch, e);
            DialogUtils.showError(null, "Error", "Could not switch to branch " + targetBranch);
            System.exit(1);
            return;
        }

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

        if (!modpackInfo.isEnabled()) {
            DialogUtils.showInfo(null, "Pack disabled", "Pack in this branch is disabled\nPlease try later");
            System.exit(0);
        }
        if (modpackInfo.isTechnicalWork()) {
            DialogUtils.showInfo(null, "Maintenance", "Pack is under maintenance\nPlease try later");
            System.exit(0);
        }

        // Сбор задач по этапам
        List<StageTasks> stages = new ArrayList<>();

        try {
            // Vanilla
            StageTasks vanillaStage = new StageTasks("Installing Minecraft " + modpackInfo.getMcVersion());
            MinecraftInstaller.collectVanillaTasks(modpackInfo.getMcVersion(), instanceDir, vanillaStage.tasks);
            if (!vanillaStage.tasks.isEmpty()) stages.add(vanillaStage);

            // Loader
            if (!"vanilla".equalsIgnoreCase(modpackInfo.getLoader())) {
                StageTasks loaderStage = new StageTasks("Installing " + modpackInfo.getLoader() + " loader");
                LoaderInstaller installer = LoaderInstaller.create(modpackInfo.getLoader());
                installer.collectTasks(modpackInfo.getMcVersion(), modpackInfo.getLoaderVersion(), instanceDir, loaderStage.tasks);
                if (!loaderStage.tasks.isEmpty()) stages.add(loaderStage);
            }

            // Modpack files
            StageTasks modpackStage = new StageTasks("Updating modpack");
            ModpackUpdater.collectUpdateTasks(targetRepo, modpackInfo.getLoader(), instanceDir, modpackStage.tasks);
            if (!modpackStage.tasks.isEmpty()) stages.add(modpackStage);

            // Big files
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

        DownloadDialog dialog = new DownloadDialog(null, totalBytes);
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();

        try {
            DownloadManager downloadManager = new DownloadManager(dialog, Runtime.getRuntime().availableProcessors());
            dialog.setDownloadManager(downloadManager);

            // Выполняем этапы последовательно
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

            Process minecraftProcess = GameLauncher.launch(config, modpackInfo, instanceDir);
            dialog.closeDialog();

            // Ждём завершения Minecraft и выходим с его кодом
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

    // ---------- Генерация параметра для кеш-бастера ----------
    private static String buildCacheBuster() {
        LocalTime now = LocalTime.now();
        String time = String.format("%02d.%02d.%02d", now.getSecond(), now.getMinute(), now.getHour());
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "&_=" + time + "-" + random;
    }

    private static String addCacheBuster(String url) {
        try {
            URI uri = new URI(url);
            String query = uri.getQuery();
            String newQuery = (query == null ? "" : query + "&") + buildCacheBuster().substring(1); // убираем первый &
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), newQuery, uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            log.warn("Failed to add cache buster to URL: {}", url, e);
            return url;
        }
    }

    // ---------- Вспомогательный класс для этапа ----------
    static class StageTasks {
        String name;
        List<DownloadTask> tasks = new ArrayList<>();
        StageTasks(String name) { this.name = name; }
    }

    // ---------- Класс задачи скачивания ----------
    static class DownloadTask {
        String url;
        Path target;
        long size;
        String label;
        DownloadTask(String url, Path target, long size, String label) {
            this.url = url;
            this.target = target;
            this.size = size;
            this.label = label;
        }
    }

    // ---------- Единый HTTP-клиент ----------
    static class HttpClientSingleton {
        private static final HttpClient instance = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        public static HttpClient getClient() { return instance; }
    }

    // ---------- Конфигурация ----------
    static class Config {
        private static final Logger log = LoggerFactory.getLogger(Config.class);
        private String username;
        private int ramMB;
        private String uuid;
        private Config() {}
        public static Config load() {
            Config cfg = new Config();
            if (Files.exists(CONFIG_FILE)) {
                try {
                    String content = Files.readString(CONFIG_FILE);
                    JSONObject json = new JSONObject(content);
                    cfg.username = json.optString("username", null);
                    cfg.ramMB = json.optInt("ram", 0);
                    cfg.uuid = json.optString("uuid", null);
                } catch (IOException e) {
                    log.error("Failed to read config", e);
                }
            }
            if (cfg.uuid == null || cfg.uuid.isEmpty()) {
                cfg.uuid = generateUUID();
            }
            return cfg;
        }
        public void save() {
            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("ram", ramMB);
            json.put("uuid", uuid);
            try {
                Files.writeString(CONFIG_FILE, json.toString(4));
            } catch (IOException e) {
                log.error("Failed to save config", e);
            }
        }
        private static String generateUUID() {
            return UUID.randomUUID().toString().replace("-", "");
        }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public int getRamMB() { return ramMB; }
        public void setRamMB(int ramMB) { this.ramMB = ramMB; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
    }

    // ---------- Проверка дискового пространства ----------
    static class DiskSpaceChecker {
        public static boolean check(Path path, long requiredBytes) {
            try {
                FileStore store = Files.getFileStore(path);
                return store.getUsableSpace() >= requiredBytes;
            } catch (IOException e) {
                return false;
            }
        }
        public static long getFreeSpace(Path path) {
            try {
                FileStore store = Files.getFileStore(path);
                return store.getUsableSpace();
            } catch (IOException e) {
                return 0;
            }
        }
    }

    // ---------- Менеджер загрузок ----------
    static class DownloadManager {
        private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
        private final ExecutorService executor;
        private final DownloadDialog dialog;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger activeTasks = new AtomicInteger(0);
        private final Object lock = new Object();

        public DownloadManager(DownloadDialog dialog, int threads) {
            this.dialog = dialog;
            this.executor = Executors.newFixedThreadPool(threads);
        }

        public DownloadDialog getDialog() { return dialog; }

        public void submit(String url, Path target, long size, String fileLabel) {
            if (cancelled.get()) return;
            synchronized (lock) {
                activeTasks.incrementAndGet();
            }
            executor.submit(() -> {
                try {
                    if (cancelled.get()) return;
                    downloadWithRetry(url, target, size, fileLabel);
                } catch (Exception e) {
                    log.error("Download error {}: {}", url, e.getMessage());
                } finally {
                    synchronized (lock) {
                        if (activeTasks.decrementAndGet() == 0) {
                            lock.notifyAll();
                        }
                    }
                }
            });
        }

        private void downloadWithRetry(String url, Path target, long size, String fileLabel) throws IOException {
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    if (cancelled.get()) return;
                    dialog.fileStarted(fileLabel);

                    String finalUrl = addCacheBuster(url);

                    HttpRequest req = HttpRequest.newBuilder(URI.create(finalUrl))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .timeout(Duration.ofSeconds(30))
                            .GET().build();
                    HttpResponse<byte[]> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (cancelled.get()) return;
                    if (resp.statusCode() != 200) {
                        throw new IOException("HTTP " + resp.statusCode());
                    }
                    byte[] data = resp.body();

                    Files.createDirectories(target.getParent());
                    Files.write(target, data);
                    dialog.addCompletedBytes(size);
                    dialog.fileCompleted(fileLabel);
                    log.info("Downloaded {} ({} bytes)", fileLabel, data.length);
                    return;
                } catch (Exception e) {
                    if (attempt == maxAttempts) {
                        throw new IOException("Failed after " + maxAttempts + " attempts", e);
                    }
                    log.warn("Attempt {} failed for {}, retry in 1s", attempt, url);
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    if (cancelled.get()) return;
                }
            }
        }

        public void awaitTermination() throws InterruptedException {
            synchronized (lock) {
                while (activeTasks.get() > 0 && !cancelled.get()) {
                    lock.wait();
                }
            }
            if (cancelled.get()) {
                throw new InterruptedException("Download cancelled");
            }
        }

        public void cancel() {
            cancelled.set(true);
            executor.shutdownNow();
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        public boolean isCancelled() { return cancelled.get(); }

        public void shutdown() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ===================== УНИФИЦИРОВАННЫЕ ОКНА НА JFrame =====================
    static abstract class BaseFrame extends JFrame {
        protected static final Color BG_COLOR = new Color(30, 30, 35);
        protected static final Color FG_COLOR = new Color(200, 200, 210);
        protected static final Color ACCENT_COLOR = new Color(0, 160, 255);
        protected static final Color BUTTON_BG = new Color(60, 60, 70);
        protected static final Color BUTTON_HOVER = new Color(80, 80, 90);
        protected static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 18);
        protected static final Font TEXT_FONT = new Font("Segoe UI", Font.PLAIN, 14);

        private final JFrame parent;
        private final boolean modal;

        public BaseFrame(JFrame parent, String title, boolean modal) {
            this.parent = parent;
            this.modal = modal;
            setTitle(title);
            setUndecorated(true);
            setBackground(BG_COLOR);
            ((JPanel) getContentPane()).setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70), 2));
            setLayout(new BorderLayout());
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        }

        @Override
        public void setVisible(boolean visible) {
            if (modal && visible && parent != null) {
                parent.setEnabled(false);
            }
            super.setVisible(visible);
            if (visible) {
                EventQueue.invokeLater(() ->
                        setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 20, 20))
                );
                toFront();
                requestFocus();
            } else {
                if (modal && parent != null) {
                    parent.setEnabled(true);
                    parent.toFront();
                }
            }
        }

        @Override
        public void dispose() {
            if (modal && parent != null) {
                parent.setEnabled(true);
                parent.toFront();
            }
            super.dispose();
        }

        protected JButton createStyledButton(String text, Color bg, Color fg) {
            JButton button = new JButton(text);
            button.setFont(new Font("Segoe UI", Font.BOLD, 14));
            button.setBackground(bg);
            button.setForeground(fg);
            button.setFocusPainted(false);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(90, 90, 100), 1),
                    BorderFactory.createEmptyBorder(8, 30, 8, 30)));
            button.setCursor(new Cursor(Cursor.HAND_CURSOR));

            button.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    button.setBackground(bg.brighter());
                }
                public void mouseExited(MouseEvent e) {
                    button.setBackground(bg);
                }
            });
            return button;
        }

        protected void centerOnScreen() {
            setLocationRelativeTo(parent);
        }
    }

    static class InfoFrame extends BaseFrame {
        public InfoFrame(JFrame parent, String title, String message) {
            super(parent, title, true);
            initUI(title, message, UIManager.getIcon("OptionPane.informationIcon"));
        }

        private void initUI(String title, String message, Icon icon) {
            JPanel content = new JPanel(new GridBagLayout());
            content.setBackground(BG_COLOR);
            content.setBorder(new EmptyBorder(20, 20, 20, 20));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(10, 10, 10, 10);
            gbc.gridx = 0;
            gbc.gridy = 0;

            if (icon != null) {
                JLabel iconLabel = new JLabel(icon);
                gbc.gridheight = 2;
                content.add(iconLabel, gbc);
                gbc.gridx = 1;
                gbc.gridheight = 1;
            }

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(TITLE_FONT);
            titleLabel.setForeground(ACCENT_COLOR);
            content.add(titleLabel, gbc);

            gbc.gridy = 1;
            JTextArea msgArea = new JTextArea(message);
            msgArea.setEditable(false);
            msgArea.setBackground(BG_COLOR);
            msgArea.setForeground(FG_COLOR);
            msgArea.setFont(TEXT_FONT);
            msgArea.setLineWrap(true);
            msgArea.setWrapStyleWord(true);
            msgArea.setBorder(null);
            content.add(msgArea, gbc);

            gbc.gridy = 2;
            gbc.gridwidth = 2;
            JButton okButton = createStyledButton("OK", BUTTON_BG, Color.WHITE);
            okButton.addActionListener(e -> dispose());
            content.add(okButton, gbc);

            add(content, BorderLayout.CENTER);
            pack();
            setSize(Math.min(500, getWidth()), getHeight());
            centerOnScreen();
        }
    }

    static class ErrorFrame extends BaseFrame {
        public ErrorFrame(JFrame parent, String title, String message) {
            super(parent, title, true);
            initUI(title, message);
        }

        private void initUI(String title, String message) {
            JPanel content = new JPanel(new GridBagLayout());
            content.setBackground(BG_COLOR);
            content.setBorder(new EmptyBorder(20, 20, 20, 20));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(10, 10, 10, 10);
            gbc.gridx = 0;
            gbc.gridy = 0;

            Icon errorIcon = UIManager.getIcon("OptionPane.errorIcon");
            if (errorIcon != null) {
                JLabel iconLabel = new JLabel(errorIcon);
                content.add(iconLabel, gbc);
                gbc.gridx = 1;
            }

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(TITLE_FONT);
            titleLabel.setForeground(new Color(255, 80, 80));
            content.add(titleLabel, gbc);

            gbc.gridy = 1;
            JTextArea msgArea = new JTextArea(message);
            msgArea.setEditable(false);
            msgArea.setBackground(BG_COLOR);
            msgArea.setForeground(FG_COLOR);
            msgArea.setFont(TEXT_FONT);
            msgArea.setLineWrap(true);
            msgArea.setWrapStyleWord(true);
            msgArea.setBorder(null);
            content.add(msgArea, gbc);

            gbc.gridy = 2;
            gbc.gridwidth = 2;
            JButton okButton = createStyledButton("OK", BUTTON_BG, Color.WHITE);
            okButton.addActionListener(e -> dispose());
            content.add(okButton, gbc);

            add(content, BorderLayout.CENTER);
            pack();
            setSize(Math.min(500, getWidth()), getHeight());
            centerOnScreen();
        }
    }

    static class DialogUtils {
        public static void showInfo(Component parent, String title, String message) {
            JFrame frame = findJFrame(parent);
            new InfoFrame(frame, title, message).setVisible(true);
        }

        public static void showError(Component parent, String title, String message) {
            JFrame frame = findJFrame(parent);
            new ErrorFrame(frame, title, message).setVisible(true);
        }

        private static JFrame findJFrame(Component parent) {
            if (parent == null) return null;
            Window window = SwingUtilities.getWindowAncestor(parent);
            while (window != null && !(window instanceof JFrame)) {
                window = window.getOwner();
            }
            return (JFrame) window;
        }
    }

    // ---------- Диалог загрузки ----------
    static class DownloadDialog extends BaseFrame {
        private JProgressBar overallProgress;
        private JProgressBar stageProgress;
        private JLabel stageLabel, fileLabel, speedLabel, timeLabel;
        private JLabel overallPercentLabel, stagePercentLabel;
        private AtomicLong downloadedBytes = new AtomicLong(0);
        private long totalBytes;
        private long stageBytes = 0;
        private long stageCompleted = 0;
        private long startTime;
        private Timer timer;
        private String stageName = "";
        private volatile boolean cancelled = false;
        private DownloadManager downloadManager;

        public DownloadDialog(JFrame parent, long totalBytes) {
            super(parent, "Downloading...", false);
            this.totalBytes = totalBytes;
            setResizable(false);
            setSize(800, 320);
            centerOnScreen();

            JPanel mainPanel = new JPanel(new GridBagLayout());
            mainPanel.setBorder(new EmptyBorder(25, 25, 20, 25));
            mainPanel.setBackground(BG_COLOR);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(8, 8, 8, 8);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            stageLabel = new JLabel("Stage: initializing");
            stageLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
            stageLabel.setForeground(FG_COLOR);
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3;
            mainPanel.add(stageLabel, gbc);

            JLabel overallTitle = new JLabel("Total:");
            overallTitle.setFont(TEXT_FONT);
            overallTitle.setForeground(FG_COLOR);
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.1;
            mainPanel.add(overallTitle, gbc);

            overallProgress = new JProgressBar(0, (int) (totalBytes / 1024));
            overallProgress.setStringPainted(false);
            overallProgress.setPreferredSize(new Dimension(400, 18));
            overallProgress.setBackground(new Color(45, 45, 50));
            overallProgress.setForeground(ACCENT_COLOR);
            overallProgress.setBorder(BorderFactory.createEmptyBorder());
            gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
            mainPanel.add(overallProgress, gbc);

            overallPercentLabel = new JLabel("0%");
            overallPercentLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            overallPercentLabel.setForeground(ACCENT_COLOR);
            gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0.1;
            mainPanel.add(overallPercentLabel, gbc);

            JLabel stageTitle = new JLabel("Stage:");
            stageTitle.setFont(TEXT_FONT);
            stageTitle.setForeground(FG_COLOR);
            gbc.gridx = 0; gbc.gridy = 2;
            mainPanel.add(stageTitle, gbc);

            stageProgress = new JProgressBar(0, 100);
            stageProgress.setStringPainted(false);
            stageProgress.setPreferredSize(new Dimension(400, 18));
            stageProgress.setBackground(new Color(45, 45, 50));
            stageProgress.setForeground(new Color(255, 180, 70));
            stageProgress.setBorder(BorderFactory.createEmptyBorder());
            gbc.gridx = 1; gbc.gridy = 2;
            mainPanel.add(stageProgress, gbc);

            stagePercentLabel = new JLabel("0%");
            stagePercentLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            stagePercentLabel.setForeground(new Color(255, 180, 70));
            gbc.gridx = 2; gbc.gridy = 2;
            mainPanel.add(stagePercentLabel, gbc);

            fileLabel = new JLabel(" ");
            fileLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
            fileLabel.setForeground(new Color(140, 140, 150));
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3;
            mainPanel.add(fileLabel, gbc);

            JPanel infoPanel = new JPanel(new GridLayout(1, 2));
            infoPanel.setBackground(BG_COLOR);
            speedLabel = new JLabel("Speed: --");
            speedLabel.setFont(TEXT_FONT);
            speedLabel.setForeground(FG_COLOR);
            timeLabel = new JLabel("Remaining: --");
            timeLabel.setFont(TEXT_FONT);
            timeLabel.setForeground(FG_COLOR);
            infoPanel.add(speedLabel);
            infoPanel.add(timeLabel);
            gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;
            mainPanel.add(infoPanel, gbc);

            JButton cancelButton = createStyledButton("Cancel", BUTTON_BG, Color.WHITE);
            cancelButton.addActionListener(e -> {
                if (downloadManager != null) downloadManager.cancel();
                cancel();
            });

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            buttonPanel.setBackground(BG_COLOR);
            buttonPanel.add(cancelButton);
            gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 3;
            mainPanel.add(buttonPanel, gbc);

            add(mainPanel, BorderLayout.CENTER);

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (downloadManager != null) downloadManager.cancel();
                    cancel();
                }
            });

            startTime = System.currentTimeMillis();
            timer = new Timer(500, e -> updateStats());
            timer.start();
        }

        public void setDownloadManager(DownloadManager dm) { this.downloadManager = dm; }

        public void startStage(String name, long stageBytes) {
            this.stageName = name;
            this.stageBytes = stageBytes;
            this.stageCompleted = 0;
            SwingUtilities.invokeLater(() -> {
                stageLabel.setText("Stage: " + stageName);
                stageProgress.setMaximum((int) (stageBytes / 1024));
                stageProgress.setValue(0);
                updatePercentLabels();
            });
        }

        public void addCompletedBytes(long bytes) {
            downloadedBytes.addAndGet(bytes);
            stageCompleted += bytes;
            SwingUtilities.invokeLater(() -> {
                overallProgress.setValue((int) (downloadedBytes.get() / 1024));
                stageProgress.setValue((int) (stageCompleted / 1024));
                updatePercentLabels();
            });
        }

        private void updatePercentLabels() {
            if (totalBytes > 0) {
                int overallPercent = (int) (downloadedBytes.get() * 100 / totalBytes);
                overallPercentLabel.setText(overallPercent + "%");
            }
            if (stageBytes > 0) {
                int stagePercent = (int) (stageCompleted * 100 / stageBytes);
                stagePercentLabel.setText(stagePercent + "%");
            }
        }

        public void fileStarted(String fileName) {
            SwingUtilities.invokeLater(() -> fileLabel.setText(shorten(fileName, 70)));
        }

        public void fileCompleted(String fileName) {}

        private String shorten(String s, int max) {
            if (s.length() <= max) return s;
            return s.substring(0, max-3) + "...";
        }

        private void updateStats() {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed == 0) return;
            long bytes = downloadedBytes.get();
            double speed = bytes / (elapsed / 1000.0);
            speedLabel.setText(String.format("Speed: %.2f MB/s", speed / 1_048_576));
            long remainingBytes = totalBytes - bytes;
            if (speed > 0 && remainingBytes > 0) {
                long remainingSecs = (long) (remainingBytes / speed);
                timeLabel.setText(String.format("Remaining: %d:%02d", remainingSecs / 60, remainingSecs % 60));
            } else {
                timeLabel.setText("Remaining: --");
            }
        }

        public void cancel() {
            cancelled = true;
            timer.stop();
            dispose();
        }

        public boolean isCancelled() { return cancelled; }

        public void stageCompleted() {}

        public void closeDialog() {
            timer.stop();
            dispose();
        }
    }

    // ---------- Диалог настроек ----------
    static class SettingsDialog extends BaseFrame {
        private Config config;
        private JTextField nickField;
        private JSlider ramSlider;
        private JTextField ramField;

        public SettingsDialog() {
            super(null, "Settings", true);
            this.config = LauncherApp.getConfig();
            initUI();
            setSize(550, 350);
            centerOnScreen();
            setVisible(true);
        }

        private void initUI() {
            JPanel mainPanel = new JPanel(new GridBagLayout());
            mainPanel.setBackground(BG_COLOR);
            mainPanel.setBorder(new EmptyBorder(25, 25, 25, 25));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(8, 8, 8, 8);

            JLabel titleLabel = new JLabel("Welcome to Modpack Launcher");
            titleLabel.setFont(TITLE_FONT);
            titleLabel.setForeground(ACCENT_COLOR);
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3;
            mainPanel.add(titleLabel, gbc);

            JLabel nickLabel = new JLabel("Nickname:");
            nickLabel.setFont(TEXT_FONT);
            nickLabel.setForeground(FG_COLOR);
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
            mainPanel.add(nickLabel, gbc);

            nickField = new JTextField(config.getUsername() != null ? config.getUsername() : "Steve");
            nickField.setFont(TEXT_FONT);
            nickField.setBackground(new Color(45, 45, 50));
            nickField.setForeground(FG_COLOR);
            nickField.setCaretColor(FG_COLOR);
            nickField.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70)));
            gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 2;
            mainPanel.add(nickField, gbc);

            JLabel ramLabel = new JLabel("RAM (GB):");
            ramLabel.setFont(TEXT_FONT);
            ramLabel.setForeground(FG_COLOR);
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
            mainPanel.add(ramLabel, gbc);

            int defaultRamGB = (config.getRamMB() > 0) ? config.getRamMB() / 1024 : 2;
            ramSlider = new JSlider(1, 16, defaultRamGB);
            ramSlider.setMajorTickSpacing(4);
            ramSlider.setMinorTickSpacing(1);
            ramSlider.setPaintTicks(true);
            ramSlider.setPaintLabels(true);
            ramSlider.setSnapToTicks(true);
            ramSlider.setBackground(BG_COLOR);
            ramSlider.setForeground(FG_COLOR);

            ramField = new JTextField(String.valueOf(ramSlider.getValue()), 4);
            ramField.setHorizontalAlignment(JTextField.CENTER);
            ramField.setFont(TEXT_FONT);
            ramField.setBackground(new Color(45, 45, 50));
            ramField.setForeground(FG_COLOR);
            ramField.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70)));

            ramSlider.addChangeListener(e -> ramField.setText(String.valueOf(ramSlider.getValue())));
            ramField.addActionListener(e -> {
                try {
                    int val = Integer.parseInt(ramField.getText());
                    val = Math.max(1, Math.min(16, val));
                    ramSlider.setValue(val);
                    ramField.setText(String.valueOf(val));
                } catch (NumberFormatException ignored) {}
            });

            JPanel ramPanel = new JPanel(new BorderLayout(5, 0));
            ramPanel.setBackground(BG_COLOR);
            ramPanel.add(ramSlider, BorderLayout.CENTER);
            ramPanel.add(ramField, BorderLayout.EAST);
            gbc.gridx = 1; gbc.gridy = 2; gbc.gridwidth = 2;
            mainPanel.add(ramPanel, gbc);

            JButton saveButton = createStyledButton("Save and Launch", new Color(70, 130, 180), Color.WHITE);
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(20, 8, 8, 8);
            mainPanel.add(saveButton, gbc);

            saveButton.addActionListener(e -> saveAndLaunch());

            add(mainPanel, BorderLayout.CENTER);
        }

        private void saveAndLaunch() {
            String newNick = nickField.getText().trim();
            if (!ValidationUtils.isValidUsername(newNick)) {
                DialogUtils.showError(this, "Error", "Nickname must contain only letters, numbers and '_', length 3-16.");
                return;
            }
            String uuid = fetchUuidFromMojang(newNick);
            if (uuid == null) {
                uuid = Config.generateUUID();
                log.info("Generated random UUID for non-premium user");
            } else {
                log.info("Fetched premium UUID for {}", newNick);
            }
            config.setUsername(newNick);
            config.setRamMB(ramSlider.getValue() * 1024);
            config.setUuid(uuid);
            config.save();
            dispose();
            new Thread(() -> LauncherApp.launch()).start();
        }

        private String fetchUuidFromMojang(String username) {
            try {
                String url = "https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(username, StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "MinecraftLauncher")
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<String> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JSONObject json = new JSONObject(resp.body());
                    return json.getString("id");
                }
            } catch (Exception e) {
                log.warn("Failed to fetch UUID from Mojang: {}", e.getMessage());
            }
            return null;
        }
    }

    // ---------- Запуск игры (исправленная версия) ----------
    static class GameLauncher {
        private static final Logger log = LoggerFactory.getLogger(GameLauncher.class);

        public static Process launch(Config config, ModpackInfo info, Path instanceDir) throws Exception {
            String versionId = info.getVersionId();
            Path versionFolder = META_DIR.resolve("versions").resolve(versionId);
            if (!Files.exists(versionFolder)) {
                throw new Exception("Loader version " + versionId + " not found in " + META_DIR.resolve("versions"));
            }
            Path versionJson = versionFolder.resolve(versionId + ".json");
            String jsonContent = Files.readString(versionJson);
            JSONObject versionData = new JSONObject(jsonContent);
            String mainClass = versionData.getString("mainClass");
            log.info("Main class from JSON: {}", mainClass);
            String inheritsFrom = versionData.optString("inheritsFrom", null);
            JSONObject assetIndexJson;
            String assetIndexId;
            JSONObject vanillaJson = null;
            if (inheritsFrom != null) {
                Path vanillaVersionDir = META_DIR.resolve("versions").resolve(inheritsFrom);
                Path vanillaJsonFile = vanillaVersionDir.resolve(inheritsFrom + ".json");
                if (!Files.exists(vanillaJsonFile)) {
                    throw new Exception("Vanilla version JSON not found: " + vanillaJsonFile);
                }
                String vanillaContent = Files.readString(vanillaJsonFile);
                vanillaJson = new JSONObject(vanillaContent);
                assetIndexJson = vanillaJson.getJSONObject("assetIndex");
                assetIndexId = assetIndexJson.getString("id");
            } else {
                assetIndexJson = versionData.getJSONObject("assetIndex");
                assetIndexId = assetIndexJson.getString("id");
            }

            // Извлечение нативных библиотек
            extractNatives(versionData, vanillaJson, instanceDir);

            List<String> classpath = buildClasspath(versionData, vanillaJson, instanceDir);
            List<String> jvmArgs = buildJvmArguments(config, instanceDir);
            List<String> gameArgs = buildGameArguments(versionData, versionId, assetIndexId, config, instanceDir);
            String cp = String.join(File.pathSeparator, classpath);
            String javaPath = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = new ArrayList<>();
            command.add(javaPath);
            command.addAll(jvmArgs);
            command.add("-cp");
            command.add(cp);
            command.add(mainClass);
            command.addAll(gameArgs);
            log.info("Launch command: {}", String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(instanceDir.toFile());
            pb.inheritIO();
            Process p = pb.start();
            log.info("Minecraft process started with PID: {}", p.pid());
            return p;
        }

        private static void extractNatives(JSONObject versionData, JSONObject vanillaJson, Path instanceDir) throws IOException {
            Path nativesDir = instanceDir.resolve("natives");
            Files.createDirectories(nativesDir);
            List<Path> nativeJars = new ArrayList<>();

            if (vanillaJson != null) {
                addNativeJars(vanillaJson.optJSONArray("libraries"), nativeJars);
            }
            addNativeJars(versionData.optJSONArray("libraries"), nativeJars);

            for (Path jar : nativeJars) {
                log.info("Extracting natives from: {}", jar);
                try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
                    Path root = fs.getPath("/");
                    Files.walk(root)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".jnilib");
                            })
                            .forEach(source -> {
                                try {
                                    Path target = nativesDir.resolve(source.getFileName().toString());
                                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                                    log.debug("Extracted: {}", target);
                                } catch (IOException e) {
                                    log.error("Failed to extract native: {}", source, e);
                                }
                            });
                } catch (Exception e) {
                    log.error("Failed to process native jar: {}", jar, e);
                }
            }
        }

        private static void addNativeJars(JSONArray libs, List<Path> nativeJars) {
            if (libs == null) return;
            Path librariesDir = META_DIR.resolve("libraries");
            for (int i = 0; i < libs.length(); i++) {
                JSONObject lib = libs.getJSONObject(i);
                if (lib.has("rules") && !LibraryUtils.evaluateRules(lib.getJSONArray("rules"))) continue;

                JSONObject downloads = lib.optJSONObject("downloads");
                if (downloads != null) {
                    JSONObject classifiers = downloads.optJSONObject("classifiers");
                    if (classifiers != null) {
                        String currentOs = LibraryUtils.getCurrentOsClassifier();
                        for (String key : classifiers.keySet()) {
                            if (key.equals(currentOs) || key.startsWith("natives-")) {
                                JSONObject classifier = classifiers.getJSONObject(key);
                                Path jarFile = librariesDir.resolve(classifier.getString("path").replace('/', File.separatorChar));
                                if (Files.exists(jarFile)) {
                                    nativeJars.add(jarFile);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        private static List<String> buildClasspath(JSONObject versionData, JSONObject vanillaJson, Path instanceDir) {
            List<String> classpath = new ArrayList<>();
            Path librariesDir = META_DIR.resolve("libraries");

            if (vanillaJson != null) {
                JSONArray vanillaLibraries = vanillaJson.optJSONArray("libraries");
                if (vanillaLibraries != null) {
                    addLibrariesToClasspath(vanillaLibraries, librariesDir, classpath);
                }
            }
            JSONArray libraries = versionData.optJSONArray("libraries");
            if (libraries != null) {
                addLibrariesToClasspath(libraries, librariesDir, classpath);
            }

            String jarVersion = versionData.optString("inheritsFrom", versionData.optString("id"));
            Path versionJar = META_DIR.resolve("versions").resolve(jarVersion).resolve(jarVersion + ".jar");
            if (Files.exists(versionJar)) {
                classpath.add(versionJar.toString());
                log.info("Added client.jar: {}", versionJar);
            } else {
                log.warn("client.jar not found for {}", jarVersion);
            }

            return classpath;
        }

        private static void addLibrariesToClasspath(JSONArray libs, Path librariesDir, List<String> classpath) {
            for (int i = 0; i < libs.length(); i++) {
                JSONObject lib = libs.getJSONObject(i);
                if (lib.has("rules") && !LibraryUtils.evaluateRules(lib.getJSONArray("rules"))) continue;

                JSONObject downloads = lib.optJSONObject("downloads");
                if (downloads != null) {
                    JSONObject artifact = downloads.optJSONObject("artifact");
                    if (artifact != null) {
                        Path libFile = librariesDir.resolve(artifact.getString("path").replace('/', File.separatorChar));
                        if (Files.exists(libFile)) {
                            classpath.add(libFile.toString());
                        }
                    }
                    // Классификаторы (natives) не добавляем в classpath
                } else {
                    String name = lib.optString("name");
                    String url = lib.optString("url");
                    if (!name.isEmpty() && !url.isEmpty()) {
                        String[] parts = name.split(":");
                        if (parts.length >= 3) {
                            String group = parts[0].replace('.', '/');
                            String artifactName = parts[1];
                            String version = parts[2];
                            String path = group + "/" + artifactName + "/" + version + "/" + artifactName + "-" + version + ".jar";
                            Path libFile = librariesDir.resolve(path.replace('/', File.separatorChar));
                            if (Files.exists(libFile)) {
                                classpath.add(libFile.toString());
                            }
                        }
                    }
                }
            }
        }

        private static List<String> buildJvmArguments(Config config, Path instanceDir) {
            List<String> jvmArgs = new ArrayList<>();
            jvmArgs.add("-Xmx" + config.getRamMB() + "M");
            Path nativesDir = instanceDir.resolve("natives");
            jvmArgs.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
            return jvmArgs;
        }

        private static List<String> buildGameArguments(JSONObject versionData, String versionName, String assetIndexId, Config config, Path instanceDir) {
            List<String> gameArgs = new ArrayList<>();
            gameArgs.add("--gameDir");
            gameArgs.add(instanceDir.toString());
            gameArgs.add("--assetsDir");
            gameArgs.add(META_DIR.resolve("assets").toString());
            gameArgs.add("--assetIndex");
            gameArgs.add(assetIndexId);
            gameArgs.add("--uuid");
            gameArgs.add(config.getUuid());
            gameArgs.add("--username");
            gameArgs.add(config.getUsername());
            gameArgs.add("--version");
            gameArgs.add(versionName);
            gameArgs.add("--accessToken");
            gameArgs.add("0");
            gameArgs.add("--userType");
            gameArgs.add("mojang");
            gameArgs.add("--userProperties");
            gameArgs.add("{}");
            return gameArgs;
        }
    }

    // ---------- Работа с GitHub ----------
    static class GitHubRepo {
        private static final Logger log = LoggerFactory.getLogger(GitHubRepo.class);
        private static final Path CACHE_DIR = LAUNCHER_DIR.resolve(".cache");
        private static final Duration CACHE_TTL = Duration.ofSeconds(10);
        public final String user;
        public final String repo;
        public final String branch;
        private Map<String, String> shaMap;
        private Map<String, Long> sizeMap;
        public GitHubRepo(String user, String repo, String branch) {
            this.user = user;
            this.repo = repo;
            this.branch = branch;
        }
        public static GitHubRepo fromBaseAndBranch(String baseUrl, String branch) throws IOException {
            Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)");
            Matcher m = pattern.matcher(baseUrl);
            if (!m.find()) throw new IOException("Invalid base GitHub URL: " + baseUrl);
            String user = m.group(1);
            String repo = m.group(2);
            return new GitHubRepo(user, repo, branch);
        }
        private void fetchTree() throws IOException {
            if (shaMap != null) return;
            Path cacheFile = CACHE_DIR.resolve(user + "_" + repo + "_" + branch + ".json");
            if (Files.exists(cacheFile)) {
                try {
                    Instant modTime = Files.getLastModifiedTime(cacheFile).toInstant();
                    if (modTime.plus(CACHE_TTL).isAfter(Instant.now())) {
                        String json = Files.readString(cacheFile);
                        parseTree(new JSONObject(json));
                        log.info("Loaded file tree from cache");
                        return;
                    } else {
                        log.debug("Cache expired, re-fetching tree for {}/{}", repo, branch);
                    }
                } catch (Exception e) {
                    log.warn("Cache read error, reloading", e);
                }
            }
            String apiUrl = "https://api.github.com/repos/" + user + "/" + repo + "/git/trees/" + branch + "?recursive=1";
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofSeconds(30));
            if (GITHUB_TOKEN != null && !GITHUB_TOKEN.isEmpty()) {
                requestBuilder.header("Authorization", "token " + GITHUB_TOKEN);
            }
            HttpRequest req = requestBuilder.GET().build();
            try {
                HttpResponse<String> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 403) {
                    String errorMsg = "Access forbidden (HTTP 403). ";
                    if (GITHUB_TOKEN == null || GITHUB_TOKEN.isEmpty()) {
                        errorMsg += "API rate limit exceeded. Add a valid GitHub token.";
                    } else {
                        errorMsg += "Token may be invalid or repository private.";
                    }
                    throw new IOException(errorMsg);
                }
                if (resp.statusCode() != 200) {
                    throw new IOException("GitHub API returned " + resp.statusCode() + ": " + resp.body());
                }
                JSONObject treeJson = new JSONObject(resp.body());
                Files.createDirectories(CACHE_DIR);
                Files.writeString(cacheFile, treeJson.toString(4));
                parseTree(treeJson);
            } catch (Exception e) {
                throw new IOException("Failed to load file tree: " + e.getMessage(), e);
            }
        }
        private void parseTree(JSONObject treeJson) {
            shaMap = new HashMap<>();
            sizeMap = new HashMap<>();
            JSONArray tree = treeJson.getJSONArray("tree");
            for (int i = 0; i < tree.length(); i++) {
                JSONObject entry = tree.getJSONObject(i);
                if ("blob".equals(entry.getString("type"))) {
                    String path = entry.getString("path");
                    String sha = entry.getString("sha");
                    long size = entry.optLong("size", 0);
                    shaMap.put(path, sha);
                    sizeMap.put(path, size);
                }
            }
        }
        public Map<String, String> getShaMap() throws IOException {
            fetchTree();
            return shaMap;
        }
        public long getFileSize(String path) throws IOException {
            fetchTree();
            return sizeMap.getOrDefault(path, 0L);
        }
        public String getSha(String path) throws IOException {
            fetchTree();
            return shaMap.get(path);
        }
        public byte[] downloadRawBytes(String path) throws IOException {
            String[] parts = path.split("/");
            StringBuilder encodedPath = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) encodedPath.append("/");
                encodedPath.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
            }
            String rawUrl = "https://raw.githubusercontent.com/" + user + "/" + repo + "/" + branch + "/" + encodedPath.toString();
            String finalUrl = addCacheBuster(rawUrl);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(finalUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofSeconds(30));
            if (GITHUB_TOKEN != null && !GITHUB_TOKEN.isEmpty()) {
                requestBuilder.header("Authorization", "token " + GITHUB_TOKEN);
            }
            HttpRequest req = requestBuilder.GET().build();
            try {
                HttpResponse<byte[]> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) {
                    throw new IOException("HTTP " + resp.statusCode() + " while downloading " + path);
                }
                return resp.body();
            } catch (Exception e) {
                throw new IOException("Failed to download " + path, e);
            }
        }
        public String downloadRawString(String path) throws IOException {
            return new String(downloadRawBytes(path), StandardCharsets.UTF_8);
        }
    }

    // ---------- Утилиты для библиотек ----------
    static class LibraryUtils {
        public static boolean evaluateRules(JSONArray rules) {
            boolean allow = false;
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                String action = rule.getString("action");
                boolean ok = true;
                if (rule.has("os")) {
                    JSONObject os = rule.getJSONObject("os");
                    if (os.has("name")) {
                        String osName = os.getString("name").toLowerCase();
                        String currentOs = System.getProperty("os.name").toLowerCase();
                        if (osName.equals("windows") && !currentOs.contains("windows")) ok = false;
                        else if (osName.equals("linux") && !currentOs.contains("linux")) ok = false;
                        else if (osName.equals("osx") && !(currentOs.contains("mac") || currentOs.contains("darwin"))) ok = false;
                    }
                }
                if (ok) allow = action.equals("allow");
            }
            return allow;
        }
        public static String getCurrentOsClassifier() {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("windows")) return "natives-windows";
            if (osName.contains("linux")) return "natives-linux";
            if (osName.contains("mac")) return "natives-osx";
            return "natives-unknown";
        }
    }

    // ---------- Валидация ника ----------
    static class ValidationUtils {
        public static boolean isValidUsername(String name) {
            return name != null && name.matches("^[a-zA-Z0-9_]{3,16}$");
        }
    }

    // ---------- Модель current_modpack.json ----------
    static class CurrentModpackInfo {
        private static final Logger log = LoggerFactory.getLogger(CurrentModpackInfo.class);
        private final boolean enabled;
        private final String branch;
        private CurrentModpackInfo(JSONObject json) {
            this.enabled = json.optBoolean("enable", true);
            this.branch = json.optString("branch", "main");
            log.info("Created CurrentModpackInfo: enabled={}, branch={}", this.enabled, this.branch);
        }
        public static CurrentModpackInfo fetch(String repoBaseUrl) throws IOException {
            String[] possibleBranches = {"main", "master"};
            for (String branch : possibleBranches) {
                try {
                    GitHubRepo repo = GitHubRepo.fromBaseAndBranch(repoBaseUrl, branch);
                    String content = repo.downloadRawString("current_modpack.json");
                    if (content.length() > 0 && content.charAt(0) == '\uFEFF') {
                        content = content.substring(1);
                    }
                    JSONObject json = new JSONObject(content);
                    log.info("Loaded current_modpack.json from {}: enabled={}, branch={}", branch,
                            json.optBoolean("enable"), json.optString("branch"));
                    return new CurrentModpackInfo(json);
                } catch (IOException e) {
                    log.warn("Failed to load from {}: {}", branch, e.getMessage());
                }
            }
            throw new IOException("Could not find current_modpack.json in main or master branch");
        }
        public boolean isEnabled() { return enabled; }
        public String getBranch() { return branch; }
    }

    // ---------- Модель modpack.json ----------
    static class ModpackInfo {
        private static final Logger log = LoggerFactory.getLogger(ModpackInfo.class);
        private final boolean enabled;
        private final boolean technicalWork;
        private final String loader;
        private final String loaderVersion;
        private final String mcVersion;
        public ModpackInfo(JSONObject json) {
            this.enabled = json.optBoolean("enabled", true);
            this.technicalWork = json.optBoolean("technical work", false);
            this.loader = json.optString("loader", "vanilla").toLowerCase(Locale.ROOT);
            this.loaderVersion = json.optString("loader_version", "");
            this.mcVersion = json.optString("MC_version", "1.21.11");
            log.info("Modpack info: enabled={}, techWork={}, loader={}, loaderVer={}, mc={}",
                    enabled, technicalWork, loader, loaderVersion, mcVersion);
        }
        public static ModpackInfo fetch(GitHubRepo repo) throws IOException {
            String raw = repo.downloadRawString("modpack.json");
            JSONObject json = new JSONObject(raw);
            return new ModpackInfo(json);
        }
        public boolean isEnabled() { return enabled; }
        public boolean isTechnicalWork() { return technicalWork; }
        public String getLoader() { return loader; }
        public String getLoaderVersion() { return loaderVersion; }
        public String getMcVersion() { return mcVersion; }
        public String getVersionId() {
            if ("vanilla".equals(loader)) {
                return mcVersion;
            } else {
                return mcVersion + "-" + loader + loaderVersion;
            }
        }
    }

    // ---------- Интерфейс установщика загрузчика ----------
    interface LoaderInstaller {
        void collectTasks(String mcVersion, String loaderVersion, Path instanceDir, List<DownloadTask> tasks) throws Exception;
        static LoaderInstaller create(String loader) {
            switch (loader) {
                case "fabric": return new FabricInstaller();
                case "quilt": return new QuiltInstaller();
                default: throw new IllegalArgumentException("Unsupported loader: " + loader + ". Supported: fabric, quilt, vanilla");
            }
        }
    }

    // ---------- Fabric ----------
    static class FabricInstaller implements LoaderInstaller {
        private static final Logger log = LoggerFactory.getLogger(FabricInstaller.class);
        private static final String FABRIC_PROFILE_URL = "https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json";
        @Override
        public void collectTasks(String mcVersion, String loaderVersion, Path instanceDir, List<DownloadTask> tasks) throws Exception {
            String profileUrl = String.format(FABRIC_PROFILE_URL, mcVersion, loaderVersion);
            JSONObject fabricProfile = fetchJson(profileUrl);
            String fabricVersionId = mcVersion + "-fabric" + loaderVersion;
            Path fabricVersionDir = META_DIR.resolve("versions").resolve(fabricVersionId);
            Files.createDirectories(fabricVersionDir);
            Path jsonPath = fabricVersionDir.resolve(fabricVersionId + ".json");
            Files.writeString(jsonPath, fabricProfile.toString(4));
            List<LibraryFile> fabricLibs = collectLibraries(fabricProfile.getJSONArray("libraries"));
            for (LibraryFile lib : fabricLibs) {
                tasks.add(new DownloadTask(lib.url, META_DIR.resolve("libraries").resolve(lib.path), lib.size, lib.path));
            }
        }
        private JSONObject fetchJson(String url) throws IOException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(addCacheBuster(url)))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            try {
                HttpResponse<String> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
                return new JSONObject(resp.body());
            } catch (Exception e) {
                throw new IOException("Failed to fetch JSON from " + url, e);
            }
        }
        private List<LibraryFile> collectLibraries(JSONArray libsArray) {
            List<LibraryFile> result = new ArrayList<>();
            for (int i = 0; i < libsArray.length(); i++) {
                JSONObject lib = libsArray.getJSONObject(i);
                if (lib.has("rules") && !LibraryUtils.evaluateRules(lib.getJSONArray("rules"))) continue;
                JSONObject downloads = lib.optJSONObject("downloads");
                if (downloads != null) {
                    JSONObject artifact = downloads.optJSONObject("artifact");
                    if (artifact != null) {
                        addArtifact(artifact, result, false);
                    }
                    JSONObject classifiers = downloads.optJSONObject("classifiers");
                    if (classifiers != null) {
                        String currentOs = LibraryUtils.getCurrentOsClassifier();
                        for (String key : classifiers.keySet()) {
                            if (key.equals(currentOs) || key.startsWith("natives-")) {
                                addArtifact(classifiers.getJSONObject(key), result, key.startsWith("natives-"));
                                break;
                            }
                        }
                    }
                } else {
                    String name = lib.optString("name");
                    String url = lib.optString("url");
                    if (!name.isEmpty() && !url.isEmpty()) {
                        String[] parts = name.split(":");
                        if (parts.length >= 3) {
                            String group = parts[0].replace('.', '/');
                            String artifactName = parts[1];
                            String version = parts[2];
                            String path = group + "/" + artifactName + "/" + version + "/" + artifactName + "-" + version + ".jar";
                            String baseUrl = url.endsWith("/") ? url : url + "/";
                            String fullUrl = baseUrl + path;
                            result.add(new LibraryFile(path, fullUrl, 0, false));
                        }
                    }
                }
            }
            return result;
        }
        private void addArtifact(JSONObject artifact, List<LibraryFile> list, boolean isNative) {
            String path = artifact.getString("path");
            String url = artifact.getString("url");
            long size = artifact.optLong("size", 0);
            list.add(new LibraryFile(path, url, size, isNative));
        }
        private static class LibraryFile {
            String path, url; long size; boolean isNative;
            LibraryFile(String path, String url, long size, boolean isNative) {
                this.path = path; this.url = url; this.size = size; this.isNative = isNative;
            }
        }
    }

    // ---------- Quilt ----------
    static class QuiltInstaller implements LoaderInstaller {
        private static final Logger log = LoggerFactory.getLogger(QuiltInstaller.class);
        private static final String QUILT_PROFILE_URL = "https://meta.quiltmc.org/v3/versions/loader/%s/%s/profile/json";
        @Override
        public void collectTasks(String mcVersion, String loaderVersion, Path instanceDir, List<DownloadTask> tasks) throws Exception {
            String profileUrl = String.format(QUILT_PROFILE_URL, mcVersion, loaderVersion);
            JSONObject quiltProfile = fetchJson(profileUrl);
            String quiltVersionId = mcVersion + "-quilt" + loaderVersion;
            Path quiltVersionDir = META_DIR.resolve("versions").resolve(quiltVersionId);
            Files.createDirectories(quiltVersionDir);
            Path jsonPath = quiltVersionDir.resolve(quiltVersionId + ".json");
            Files.writeString(jsonPath, quiltProfile.toString(4));
            List<LibraryFile> quiltLibs = collectLibraries(quiltProfile.getJSONArray("libraries"));
            for (LibraryFile lib : quiltLibs) {
                tasks.add(new DownloadTask(lib.url, META_DIR.resolve("libraries").resolve(lib.path), lib.size, lib.path));
            }
        }
        private JSONObject fetchJson(String url) throws IOException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(addCacheBuster(url)))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            try {
                HttpResponse<String> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
                return new JSONObject(resp.body());
            } catch (Exception e) {
                throw new IOException("Failed to fetch JSON from " + url, e);
            }
        }
        private List<LibraryFile> collectLibraries(JSONArray libsArray) {
            List<LibraryFile> result = new ArrayList<>();
            for (int i = 0; i < libsArray.length(); i++) {
                JSONObject lib = libsArray.getJSONObject(i);
                if (lib.has("rules") && !LibraryUtils.evaluateRules(lib.getJSONArray("rules"))) continue;
                JSONObject downloads = lib.optJSONObject("downloads");
                if (downloads != null) {
                    JSONObject artifact = downloads.optJSONObject("artifact");
                    if (artifact != null) {
                        addArtifact(artifact, result, false);
                    }
                    JSONObject classifiers = downloads.optJSONObject("classifiers");
                    if (classifiers != null) {
                        String currentOs = LibraryUtils.getCurrentOsClassifier();
                        for (String key : classifiers.keySet()) {
                            if (key.equals(currentOs) || key.startsWith("natives-")) {
                                addArtifact(classifiers.getJSONObject(key), result, key.startsWith("natives-"));
                                break;
                            }
                        }
                    }
                }
            }
            return result;
        }
        private void addArtifact(JSONObject artifact, List<LibraryFile> list, boolean isNative) {
            String path = artifact.getString("path");
            String url = artifact.getString("url");
            long size = artifact.optLong("size", 0);
            list.add(new LibraryFile(path, url, size, isNative));
        }
        private static class LibraryFile {
            String path, url; long size; boolean isNative;
            LibraryFile(String path, String url, long size, boolean isNative) {
                this.path = path; this.url = url; this.size = size; this.isNative = isNative;
            }
        }
    }

    // ---------- Установка ванильного Minecraft ----------
    static class MinecraftInstaller {
        private static final Logger log = LoggerFactory.getLogger(MinecraftInstaller.class);
        private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

        public static void collectVanillaTasks(String mcVer, Path instanceDir, List<DownloadTask> tasks) throws Exception {
            Path versionsDir = META_DIR.resolve("versions");
            Path vanillaVersionDir = versionsDir.resolve(mcVer);
            Path vanillaJsonPath = vanillaVersionDir.resolve(mcVer + ".json");

            if (Files.exists(vanillaJsonPath)) {
                collectMissingLibrariesAndAssets(mcVer, instanceDir, tasks);
                return;
            }

            JSONObject versionManifest = fetchJson(VERSION_MANIFEST_URL);
            JSONArray versions = versionManifest.getJSONArray("versions");
            JSONObject versionInfo = null;
            for (int i = 0; i < versions.length(); i++) {
                JSONObject v = versions.getJSONObject(i);
                if (v.getString("id").equals(mcVer)) {
                    versionInfo = v;
                    break;
                }
            }
            if (versionInfo == null) throw new IOException("Version " + mcVer + " not found.");

            String versionUrl = versionInfo.getString("url");
            JSONObject vanillaJson = fetchJson(versionUrl);

            Files.createDirectories(vanillaVersionDir);
            Files.writeString(vanillaJsonPath, vanillaJson.toString(4));

            JSONObject downloads = vanillaJson.getJSONObject("downloads");
            JSONObject client = downloads.getJSONObject("client");
            long clientSize = client.getLong("size");
            tasks.add(new DownloadTask(client.getString("url"), vanillaVersionDir.resolve(mcVer + ".jar"), clientSize, "client.jar"));

            JSONArray libraries = vanillaJson.getJSONArray("libraries");
            List<LibraryFile> vanillaLibs = collectLibraries(libraries);
            for (LibraryFile lib : vanillaLibs) {
                tasks.add(new DownloadTask(lib.url, META_DIR.resolve("libraries").resolve(lib.path), lib.size, lib.path));
            }

            JSONObject assetIndex = vanillaJson.getJSONObject("assetIndex");
            String assetIndexId = assetIndex.getString("id");
            Path assetsDir = META_DIR.resolve("assets");
            Path indexesDir = assetsDir.resolve("indexes");
            Path indexPath = indexesDir.resolve(assetIndexId + ".json");
            Files.createDirectories(indexesDir);

            if (!Files.exists(indexPath)) {
                log.info("Downloading asset index {}...", assetIndexId);
                String assetIndexUrl = assetIndex.getString("url");
                JSONObject assetIndexJson = fetchJson(assetIndexUrl);
                Files.writeString(indexPath, assetIndexJson.toString(4));
                addAssetTasks(assetIndexJson, instanceDir, tasks);
            } else {
                String indexContent = Files.readString(indexPath);
                JSONObject assetIndexJson = new JSONObject(indexContent);
                addAssetTasks(assetIndexJson, instanceDir, tasks);
            }
        }

        private static void collectMissingLibrariesAndAssets(String mcVer, Path instanceDir, List<DownloadTask> tasks) throws IOException {
            Path vanillaJsonPath = META_DIR.resolve("versions").resolve(mcVer).resolve(mcVer + ".json");
            if (!Files.exists(vanillaJsonPath)) return;
            String content = Files.readString(vanillaJsonPath);
            JSONObject vanillaJson = new JSONObject(content);

            JSONArray libraries = vanillaJson.getJSONArray("libraries");
            List<LibraryFile> allLibs = collectLibraries(libraries);
            for (LibraryFile lib : allLibs) {
                Path libFile = META_DIR.resolve("libraries").resolve(lib.path);
                if (!Files.exists(libFile)) {
                    tasks.add(new DownloadTask(lib.url, libFile, lib.size, lib.path));
                }
            }

            JSONObject assetIndex = vanillaJson.getJSONObject("assetIndex");
            String assetIndexId = assetIndex.getString("id");
            Path indexPath = META_DIR.resolve("assets").resolve("indexes").resolve(assetIndexId + ".json");
            if (!Files.exists(indexPath)) {
                log.info("Downloading missing asset index {}...", assetIndexId);
                String assetIndexUrl = assetIndex.getString("url");
                JSONObject assetIndexJson = fetchJson(assetIndexUrl);
                Files.createDirectories(indexPath.getParent());
                Files.writeString(indexPath, assetIndexJson.toString(4));
                addAssetTasks(assetIndexJson, instanceDir, tasks);
            } else {
                String indexContent = Files.readString(indexPath);
                JSONObject assetIndexJson = new JSONObject(indexContent);
                addAssetTasks(assetIndexJson, instanceDir, tasks);
            }
        }

        private static void addAssetTasks(JSONObject assetIndex, Path instanceDir, List<DownloadTask> tasks) {
            JSONObject objects = assetIndex.getJSONObject("objects");
            Path objectsDir = META_DIR.resolve("assets").resolve("objects");
            for (String key : objects.keySet()) {
                JSONObject obj = objects.getJSONObject(key);
                String hash = obj.getString("hash");
                long size = obj.getLong("size");
                String subDir = hash.substring(0, 2);
                Path objectFile = objectsDir.resolve(subDir).resolve(hash);
                if (!Files.exists(objectFile) || objectFile.toFile().length() != size) {
                    String url = "https://resources.download.minecraft.net/" + subDir + "/" + hash;
                    tasks.add(new DownloadTask(url, objectFile, size, "assets/" + subDir + "/" + hash));
                }
            }
        }

        private static JSONObject fetchJson(String url) throws IOException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(addCacheBuster(url)))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            try {
                HttpResponse<String> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
                return new JSONObject(resp.body());
            } catch (Exception e) {
                throw new IOException("Failed to fetch JSON from " + url, e);
            }
        }

        private static List<LibraryFile> collectLibraries(JSONArray libsArray) {
            List<LibraryFile> result = new ArrayList<>();
            for (int i = 0; i < libsArray.length(); i++) {
                JSONObject lib = libsArray.getJSONObject(i);
                if (lib.has("rules") && !LibraryUtils.evaluateRules(lib.getJSONArray("rules"))) continue;

                JSONObject downloads = lib.optJSONObject("downloads");
                if (downloads != null) {
                    JSONObject artifact = downloads.optJSONObject("artifact");
                    if (artifact != null) {
                        addArtifact(artifact, result, false);
                    }
                    JSONObject classifiers = downloads.optJSONObject("classifiers");
                    if (classifiers != null) {
                        String currentOs = LibraryUtils.getCurrentOsClassifier();
                        for (String key : classifiers.keySet()) {
                            if (key.equals(currentOs) || key.startsWith("natives-")) {
                                addArtifact(classifiers.getJSONObject(key), result, key.startsWith("natives-"));
                                break;
                            }
                        }
                    }
                }
            }
            return result;
        }

        private static void addArtifact(JSONObject artifact, List<LibraryFile> list, boolean isNative) {
            String path = artifact.getString("path");
            String url = artifact.getString("url");
            long size = artifact.optLong("size", 0);
            list.add(new LibraryFile(path, url, size, isNative));
        }

        private static class LibraryFile {
            String path, url; long size; boolean isNative;
            LibraryFile(String path, String url, long size, boolean isNative) {
                this.path = path; this.url = url; this.size = size; this.isNative = isNative;
            }
        }
    }

    // ---------- Синхронизация файлов модпака ----------
    static class ModpackUpdater {
        private static final Logger log = LoggerFactory.getLogger(ModpackUpdater.class);

        private static class BigFile {
            String path;
            String url;
            BigFile(JSONObject obj) {
                this.path = obj.getString("path");
                this.url = obj.getString("url");
            }
        }

        public static void collectUpdateTasks(GitHubRepo repo, String loader, Path instanceDir, List<DownloadTask> tasks) throws IOException {
            Map<String, String> remoteShas = repo.getShaMap();
            Map<String, Long> remoteSizes = new HashMap<>();
            for (String path : remoteShas.keySet()) {
                remoteSizes.put(path, repo.getFileSize(path));
            }

            Path manifestFile = instanceDir.resolve(".manifest.json");
            Map<String, String> localShas = new HashMap<>();
            if (Files.exists(manifestFile)) {
                String content = Files.readString(manifestFile);
                JSONObject localJson = new JSONObject(content);
                for (String key : localJson.keySet()) {
                    localShas.put(key, localJson.getString(key));
                }
            }

            List<String> pathsToSync = new ArrayList<>();
            if ("vanilla".equals(loader)) {
                for (String path : remoteShas.keySet()) {
                    if (path.startsWith("resourcepacks/") || path.equals("options.txt")) {
                        pathsToSync.add(path);
                    }
                }
            } else {
                for (String path : remoteShas.keySet()) {
                    if (path.equals("modpack.json") || path.equals("25MB_dl.json")) {
                        continue;
                    }
                    pathsToSync.add(path);
                }
            }

            for (String path : pathsToSync) {
                String remoteSha = remoteShas.get(path);
                String localSha = localShas.get(path);
                Path localFile = instanceDir.resolve(path);
                boolean needDownload = false;
                if (!Files.exists(localFile)) {
                    needDownload = true;
                } else if (!remoteSha.equals(localSha)) {
                    needDownload = true;
                }
                if (loader.equals("vanilla")) {
                    if (path.equals("options.txt") && Files.exists(localFile)) {
                        needDownload = false;
                    }
                } else {
                    if ((path.startsWith("config/") || path.startsWith("config\\") || path.equals("options.txt")) && Files.exists(localFile)) {
                        needDownload = false;
                    }
                }
                if (needDownload) {
                    long size = remoteSizes.getOrDefault(path, 0L);
                    String[] parts = path.split("/");
                    StringBuilder encodedPath = new StringBuilder();
                    for (int i = 0; i < parts.length; i++) {
                        if (i > 0) encodedPath.append("/");
                        encodedPath.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
                    }
                    String fileUrl = "https://raw.githubusercontent.com/" + repo.user + "/" + repo.repo + "/" + repo.branch + "/" + encodedPath.toString();
                    tasks.add(new DownloadTask(fileUrl, instanceDir.resolve(path), size, path));
                }
            }
        }

        public static void collectBigFilesTasks(GitHubRepo repo, Path instanceDir, List<DownloadTask> tasks) throws IOException {
            Path bigFilesManifest = instanceDir.resolve(".bigfiles_manifest.json");
            Map<String, BigFile> localBigFiles = new HashMap<>();
            if (Files.exists(bigFilesManifest)) {
                String content = Files.readString(bigFilesManifest);
                JSONObject localJson = new JSONObject(content);
                for (String key : localJson.keySet()) {
                    JSONObject obj = localJson.getJSONObject(key);
                    localBigFiles.put(key, new BigFile(obj));
                }
            }

            List<BigFile> remoteBigFiles = new ArrayList<>();
            try {
                String bigListJson = repo.downloadRawString("25MB_dl.json");
                JSONArray arr = new JSONArray(bigListJson);
                for (int i = 0; i < arr.length(); i++) {
                    remoteBigFiles.add(new BigFile(arr.getJSONObject(i)));
                }
            } catch (IOException e) {
                log.info("No 25MB_dl.json found or error loading: {}", e.getMessage());
                return;
            }

            for (BigFile remote : remoteBigFiles) {
                String urlPath = URI.create(remote.url).getPath();
                String fileName = Paths.get(urlPath).getFileName().toString();
                String localKey = remote.path + "/" + fileName;

                BigFile local = localBigFiles.get(localKey);
                Path localFile = instanceDir.resolve(localKey);
                boolean needDownload = false;
                if (local == null) {
                    needDownload = true;
                } else if (!remote.url.equals(local.url)) {
                    needDownload = true;
                } else if (!Files.exists(localFile)) {
                    needDownload = true;
                }

                long size = 0;
                if (needDownload) {
                    try {
                        HttpRequest headReq = HttpRequest.newBuilder(URI.create(remote.url))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build();
                        HttpResponse<Void> headResp = HttpClientSingleton.getClient().send(headReq, HttpResponse.BodyHandlers.discarding());
                        String contentLength = headResp.headers().firstValue("Content-Length").orElse(null);
                        if (contentLength != null) {
                            size = Long.parseLong(contentLength);
                        }
                    } catch (Exception e) {
                        log.warn("Could not determine size for {}: {}", remote.url, e.getMessage());
                    }
                    tasks.add(new DownloadTask(remote.url, localFile, size, localKey));
                }
            }
        }
    }
}
