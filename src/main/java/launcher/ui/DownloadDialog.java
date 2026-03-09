package launcher.ui;

import launcher.download.DownloadManager;
import launcher.util.UrlUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dialog that displays download progress, speed, and remaining time.
 * Allows cancellation.
 */
public class DownloadDialog extends BaseFrame {
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

        // Stage name label
        stageLabel = new JLabel("Stage: initializing");
        stageLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        stageLabel.setForeground(FG_COLOR);
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3;
        mainPanel.add(stageLabel, gbc);

        // Overall progress bar
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

        // Stage progress bar
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

        // Current file label
        fileLabel = new JLabel(" ");
        fileLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        fileLabel.setForeground(new Color(140, 140, 150));
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3;
        mainPanel.add(fileLabel, gbc);

        // Speed and time panel
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

        // Cancel button
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

        // Handle window close as cancellation
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

    /**
     * Called when a new stage begins.
     */
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

    /**
     * Adds downloaded bytes to counters and updates progress bars.
     */
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

    /**
     * Called when a file download starts.
     */
    public void fileStarted(String fileName) {
        SwingUtilities.invokeLater(() -> fileLabel.setText(shorten(fileName, 70)));
    }

    public void fileCompleted(String fileName) {}

    private String shorten(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max-3) + "...";
    }

    /**
     * Updates speed and remaining time labels.
     */
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