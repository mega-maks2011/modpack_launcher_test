package launcher.ui;

import launcher.LauncherApp;
import launcher.config.Config;
import launcher.download.HttpClientSingleton;
import launcher.util.ValidationUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Settings dialog for username and RAM allocation.
 * Also fetches Mojang UUID if the username is premium.
 */
public class SettingsDialog extends BaseFrame {
    private static final Logger log = LoggerFactory.getLogger(SettingsDialog.class);
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

        // Title
        JLabel titleLabel = new JLabel("Welcome to Modpack Launcher");
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(ACCENT_COLOR);
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3;
        mainPanel.add(titleLabel, gbc);

        // Nickname field
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

        // RAM label
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

        // Synchronize slider and text field
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

        // Save button
        JButton saveButton = createStyledButton("Save and Launch", new Color(70, 130, 180), Color.WHITE);
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(20, 8, 8, 8);
        mainPanel.add(saveButton, gbc);

        saveButton.addActionListener(e -> saveAndLaunch());

        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Validates input, fetches UUID from Mojang API (or generates random),
     * saves config, and launches the main process.
     */
    private void saveAndLaunch() {
        String newNick = nickField.getText().trim();
        if (!ValidationUtils.isValidUsername(newNick)) {
            DialogUtils.showError(this, "Error", "Nickname must contain only letters, numbers and '_', length 3-16.");
            return;
        }
        String uuid = fetchUuidFromMojang(newNick);
        if (uuid == null) {
            uuid = generateUUIDStatic();
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

    /**
     * Attempts to fetch the UUID of a premium Minecraft account from Mojang API.
     */
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

    private static String generateUUIDStatic() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}