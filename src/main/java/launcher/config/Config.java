package launcher.config;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static launcher.LauncherApp.CONFIG_FILE;

/**
 * Stores user configuration: username, RAM allocation, and UUID.
 * Loads from and saves to config.json.
 */
public class Config {
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