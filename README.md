# Minecraft Modpack Launcher / Лаунчер модпаков для Minecraft

**English** | [Русский](#русский)

---

## English

A custom Minecraft launcher that downloads and runs modpacks from a GitHub repository.  
Supports Fabric, Quilt, and vanilla installations, with automatic updates and asset management.

### Features

- Fetches modpack information from GitHub (`modpack.json`, `current_modpack.json`)
- Supports multiple branches (main/master) for different pack versions
- Automatically downloads Minecraft vanilla client, libraries, and assets
- Installs Fabric or Quilt loaders
- Syncs modpack files (configs, mods, etc.) using git SHA checksums
- Downloads large files listed in `25MB_dl.json`
- User-friendly Swing GUI with dark theme and download progress
- Saves user settings (nickname, RAM, UUID)

### Prerequisites

- Java 17 or higher (to run the launcher)
- Gradle (to build) – or use the provided `gradlew` wrapper

### Setup

#### 1. Clone the repository
```bash
git clone https://github.com/yourusername/your-launcher-repo.git
cd your-launcher-repo
```

#### 2. Configure the launcher

Open `src/main/java/launcher/LauncherApp.java` and modify the following constants:

- `REPO_BASE_URL` – the base URL of your GitHub repository (e.g., `https://github.com/yourusername/your-modpack-repo`)
- `GITHUB_TOKEN` – (optional) a GitHub personal access token.  
  Needed if your repository is private or to avoid API rate limits.  
  You can generate one at [GitHub Settings → Developer settings → Personal access tokens](https://github.com/settings/tokens) (no special scopes required).

Example:
```java
public static final String REPO_BASE_URL = "https://github.com/mega-maks2011/modpack_launcher_test";
public static final String GITHUB_TOKEN = "ghp_xxxxxxxxxxxxxxxxxxxx";
```

If you don't have a token, leave the string empty:
```java
public static final String GITHUB_TOKEN = "";
```

#### 3. Build the launcher

Use the Gradle wrapper to create an executable JAR:

```bash
chmod +x gradlew          # on Linux/macOS
./gradlew clean jar
```

The JAR file will be created at `build/libs/Minecraft_launcher.jar` (the filename may vary).

#### 4. Run the launcher

```bash
java -jar build/libs/Minecraft_launcher.jar
```

On first launch, you will be prompted to enter your Minecraft nickname and allocate RAM.  
The launcher will then fetch the current modpack info from the configured repository and start downloading.

### Repository structure for your modpack

Your GitHub repository should contain at least:

- `current_modpack.json` – tells the launcher which branch is active and whether the pack is enabled globally.
- `modpack.json` – describes the modpack (loader, Minecraft version, etc.) and is placed in **each branch** you want to use.
- (Optional) `25MB_dl.json` – lists large files to be downloaded separately (e.g., resource packs, mods >25 MB).

#### `current_modpack.json` (placed in the repository root, in `main` or `master` branch)
```json
{
  "enable": true,
  "branch": "main"
}
```

#### `modpack.json` (placed in the active branch)
```json
{
  "enabled": true,
  "technical work": false,
  "loader": "fabric",
  "loader_version": "0.15.11",
  "MC_version": "1.20.1"
}
```
Supported loaders: `"fabric"`, `"quilt"`, `"vanilla"`.

#### `25MB_dl.json` (optional, in the active branch)
```json
[
  { "path": "mods", "url": "https://example.com/bigmod.jar" },
  { "path": "resourcepacks", "url": "https://example.com/bigpack.zip" }
]
```

### Working with branches

The launcher uses Git branches to manage different versions of your modpack.  
The `current_modpack.json` file in the default branch (`main` or `master`) determines which branch is currently active.

#### How it works

1. You create a branch for a specific modpack version (e.g., `1.20.1-fabric`, `1.19.2-quilt`).
2. In that branch, you place a `modpack.json` with the appropriate settings and all the modpack files (configs, mods, scripts, etc.).
3. To switch the active branch, you update the `branch` field in `current_modpack.json` in the default branch and commit the change.
4. The launcher always reads `current_modpack.json` from the default branch, then fetches all files from the specified branch.

#### Example: creating a new branch

```bash
# Switch to default branch (main)
git checkout main

# Create and switch to a new branch for Minecraft 1.20.1 with Fabric
git checkout -b 1.20.1-fabric

# Edit modpack.json (set loader, MC_version, etc.)
# Add your mods, configs, and other files

# Commit and push the new branch
git add .
git commit -m "Add modpack for 1.20.1 Fabric"
git push -u origin 1.20.1-fabric
```

#### Switching the active branch

```bash
# Go back to the default branch
git checkout main

# Edit current_modpack.json to point to the new branch
{
  "enable": true,
  "branch": "1.20.1-fabric"
}

# Commit and push
git add current_modpack.json
git commit -m "Switch to 1.20.1-fabric branch"
git push
```

Now all launcher clients will automatically download and use the modpack from the `1.20.1-fabric` branch.

#### Important notes

- Each branch can have completely different files – they are independent.
- The launcher stores downloaded files in a subfolder named after the branch (`instances/<branch-name>`), so switching branches does not mix files.
- If you disable the pack globally (`"enable": false` in `current_modpack.json`), the launcher will show a message and exit.
- You can also temporarily disable a branch by setting `"enabled": false` in its `modpack.json` or mark it under maintenance with `"technical work": true`.

### License

This project is licensed under the MIT License – see the [LICENSE](LICENSE) file for details.

---

## Русский

Кастомный лаунчер для Minecraft, который скачивает и запускает модпаки из репозитория GitHub.  
Поддерживает Fabric, Quilt и ванильные установки, автоматическое обновление и управление ассетами.

### Возможности

- Получает информацию о модпаке из GitHub (`modpack.json`, `current_modpack.json`)
- Поддерживает несколько веток (main/master) для разных версий пака
- Автоматически скачивает ванильный клиент Minecraft, библиотеки и ассеты
- Устанавливает загрузчики Fabric или Quilt
- Синхронизирует файлы модпака (конфиги, моды и т.д.) используя контрольные суммы git SHA
- Скачивает большие файлы, перечисленные в `25MB_dl.json`
- Удобный Swing-интерфейс с тёмной темой и прогрессом загрузки
- Сохраняет настройки пользователя (ник, RAM, UUID)

### Требования

- Java 17 или выше (для запуска лаунчера)
- Gradle (для сборки) – или используйте обёртку `gradlew`

### Настройка

#### 1. Клонируйте репозиторий
```bash
git clone https://github.com/yourusername/your-launcher-repo.git
cd your-launcher-repo
```

#### 2. Настройте лаунчер

Откройте `src/main/java/launcher/LauncherApp.java` и измените следующие константы:

- `REPO_BASE_URL` – базовый URL вашего GitHub репозитория (например, `https://github.com/yourusername/your-modpack-repo`)
- `GITHUB_TOKEN` – (опционально) персональный токен GitHub.  
  Нужен, если репозиторий приватный или для избежания ограничений API.  
  Токен можно создать в [Настройках GitHub → Developer settings → Personal access tokens](https://github.com/settings/tokens) (права не требуются).

Пример:
```java
public static final String REPO_BASE_URL = "https://github.com/mega-maks2011/modpack_launcher_test";
public static final String GITHUB_TOKEN = "ghp_xxxxxxxxxxxxxxxxxxxx";
```

Если у вас нет токена, оставьте строку пустой:
```java
public static final String GITHUB_TOKEN = "";
```

#### 3. Соберите лаунчер

Используйте обёртку Gradle для создания исполняемого JAR:

```bash
chmod +x gradlew          # на Linux/macOS
./gradlew clean jar
```

JAR-файл будет создан в `build/libs/Minecraft_launcher.jar` (имя может отличаться).

#### 4. Запустите лаунчер

```bash
java -jar build/libs/Minecraft_launcher.jar
```

При первом запуске вам будет предложено ввести никнейм Minecraft и выделить RAM.  
Затем лаунчер получит информацию о текущем модпаке из настроенного репозитория и начнёт загрузку.

### Структура репозитория вашего модпака

Ваш репозиторий GitHub должен содержать как минимум:

- `current_modpack.json` – сообщает лаунчеру, какая ветка активна и включён ли пак глобально.
- `modpack.json` – описывает модпак (загрузчик, версию Minecraft и т.д.) и находится в **каждой ветке**, которую вы хотите использовать.
- (Опционально) `25MB_dl.json` – список больших файлов для отдельной загрузки (например, ресурспаки, моды >25 МБ).

#### `current_modpack.json` (размещается в корне репозитория, в ветке `main` или `master`)
```json
{
  "enable": true,
  "branch": "main"
}
```

#### `modpack.json` (размещается в активной ветке)
```json
{
  "enabled": true,
  "technical work": false,
  "loader": "fabric",
  "loader_version": "0.15.11",
  "MC_version": "1.20.1"
}
```
Поддерживаемые загрузчики: `"fabric"`, `"quilt"`, `"vanilla"`.

#### `25MB_dl.json` (опционально, в активной ветке)
```json
[
  { "path": "mods", "url": "https://example.com/bigmod.jar" },
  { "path": "resourcepacks", "url": "https://example.com/bigpack.zip" }
]
```

### Работа с ветками

Лаунчер использует Git-ветки для управления разными версиями вашего модпака.  
Файл `current_modpack.json` в ветке по умолчанию (`main` или `master`) определяет, какая ветка сейчас активна.

#### Как это работает

1. Вы создаёте ветку для конкретной версии модпака (например, `1.20.1-fabric`, `1.19.2-quilt`).
2. В этой ветке размещаете `modpack.json` с нужными настройками и все файлы модпака (конфиги, моды, скрипты и т.д.).
3. Чтобы переключить активную ветку, вы изменяете поле `branch` в `current_modpack.json` в ветке по умолчанию и коммитите изменение.
4. Лаунчер всегда читает `current_modpack.json` из ветки по умолчанию, а затем загружает все файлы из указанной ветки.

#### Пример: создание новой ветки

```bash
# Переключаемся на ветку по умолчанию (main)
git checkout main

# Создаём и переключаемся на новую ветку для Minecraft 1.20.1 с Fabric
git checkout -b 1.20.1-fabric

# Редактируем modpack.json (указываем загрузчик, версию MC и т.д.)
# Добавляем свои моды, конфиги и другие файлы

# Коммитим и пушим новую ветку
git add .
git commit -m "Добавлен модпак для 1.20.1 Fabric"
git push -u origin 1.20.1-fabric
```

#### Переключение активной ветки

```bash
# Возвращаемся в ветку по умолчанию
git checkout main

# Редактируем current_modpack.json, указывая новую ветку
{
  "enable": true,
  "branch": "1.20.1-fabric"
}

# Коммитим и пушим
git add current_modpack.json
git commit -m "Переключение на ветку 1.20.1-fabric"
git push
```

После этого все клиенты лаунчера автоматически загрузят и будут использовать модпак из ветки `1.20.1-fabric`.

#### Важные замечания

- Каждая ветка может содержать совершенно разные файлы – они независимы.
- Лаунчер сохраняет загруженные файлы в подпапку с именем ветки (`instances/<имя-ветки>`), поэтому при переключении веток файлы не перемешиваются.
- Если отключить пак глобально (`"enable": false` в `current_modpack.json`), лаунчер покажет сообщение и закроется.
- Вы также можете временно отключить конкретную ветку, установив `"enabled": false` в её `modpack.json`, или пометить её как находящуюся на обслуживании (`"technical work": true`).

### Лицензия

Этот проект распространяется под лицензией MIT – подробности в файле [LICENSE](LICENSE).
