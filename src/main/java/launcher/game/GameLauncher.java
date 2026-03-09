package launcher.game;

import launcher.config.Config;
import launcher.download.HttpClientSingleton;
import launcher.github.ModpackInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import static launcher.LauncherApp.META_DIR;

/**
 * Launches the Minecraft game with the correct classpath, JVM arguments, and game arguments.
 */
public class GameLauncher {
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
            // Load inherited vanilla version JSON
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

        // Extract native libraries to instance natives directory
        extractNatives(versionData, vanillaJson, instanceDir);

        // Build classpath
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

    /**
     * Extracts native libraries (DLL, so, dylib) from downloaded JARs to the natives directory.
     */
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
                // classifiers (natives) are not added to classpath
            } else {
                // Handle libraries without downloads object (e.g., custom URL)
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