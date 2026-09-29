# Setup Guide: running this project on another PC

This guide takes a fresh Windows PC from nothing to a running app. Setup takes about 15 minutes, and most of that is downloads.

---

## 1. What you need

| | Tool | Why | Get it |
|---|---|---|---|
| ✅ **Required** | **JDK 21** (Eclipse Temurin) | Compiles and runs the Java code | https://adoptium.net → *Temurin 21 (LTS)* → Windows x64 `.msi` |
| ✅ **Required** | **Internet** (first build only) | Downloads Maven + libraries once (tens of MB), cached in `%USERPROFILE%\.m2` | — |
| 👍 Recommended | **Git** | Clone, pull and push the code | https://git-scm.com/download/win |
| 👍 Recommended | **IntelliJ IDEA** (free) | IDE with a Run button, debugger and test view | https://www.jetbrains.com/idea/download |
| ➕ Optional | **GitHub CLI** (`gh`) | Push without login pop-ups | https://cli.github.com |
| ➕ Optional | **Kiro / VS Code** + *Extension Pack for Java* | Alternative editor (the extension is suggested automatically) | — |

### You do **not** need

| Tool | Why not |
|---|---|
| Maven | The included wrapper (`mvnw.cmd`) downloads the correct version automatically |
| JavaFX SDK | Maven downloads JavaFX as a normal library |
| MySQL / any database server | H2 is embedded inside the app |
| Blender / 3D models | The car and city are generated in Java code |
| Scene Builder | The UI is written in Java code |

### Exact versions the project uses

All versions are pinned in `pom.xml` and `.mvn/wrapper/maven-wrapper.properties`, so every PC builds the same way.

| Component | Version |
|---|---|
| Java (compile target) | 21 (any JDK 21 or newer can build it) |
| Maven (via wrapper) | 3.9.16 |
| JavaFX | 21.0.12 |
| H2 database | 2.5.252 |
| JUnit | 6.1.3 |

---

## 2. Install the JDK

1. Download **Temurin 21 LTS** for **Windows x64** (`.msi`) from https://adoptium.net.
2. In the installer, click the feature list and enable **"Set JAVA_HOME variable"**. This is off by default and is the most common cause of trouble.
3. Open a **new** terminal and check:

   ```powershell
   java -version       # should print: openjdk version "21...."
   echo $env:JAVA_HOME # should print the JDK folder
   ```

> [!NOTE]
> If `JAVA_HOME` already points to another JDK 21 or newer (for example Android Studio's `jbr`), that works too.

---

## 3. Get the code

The repository is **private**, so the owner must give your GitHub account access first.

**Owner (one time per friend):** GitHub → repository → *Settings* → *Collaborators* → *Add people*. From the terminal:

```powershell
gh api -X PUT repos/InvictusVerse/selfdrive/collaborators/FRIEND_GITHUB_USERNAME -f permission=push
```

**Friend:** accept the email invitation, then clone:

```powershell
git clone https://github.com/InvictusVerse/selfdrive.git
cd selfdrive
```

No Git? The owner can send a ZIP instead (GitHub → *Code* → *Download ZIP*). Extract it anywhere; everything below still works.

---

## 4. Check the PC

Run the environment checker from the project folder. It only reads. It doesn't change anything on the PC.

```powershell
scripts\check-environment.cmd -Build
```

It checks Java, Git, network access and project files. With `-Build`, it also compiles the project and runs the tests. You can also double-click `scripts\check-environment.cmd` in Explorer.

---

## 5. Run the app

### Option A: terminal

```powershell
.\mvnw.cmd javafx:run
```

### Option B: IntelliJ IDEA

1. *File → Open* → select the project folder → **Trust Project**.
2. Wait for the Maven import to finish (progress bar at the bottom).
3. If asked for a JDK, choose **21** (*File → Project Structure → SDK*).
4. Pick **Run App** in the run dropdown (top right) and press ▶.

These shared run configurations come with the project (`.run/` folder):

| Configuration | What it does |
|---|---|
| **Run App** | Starts the app through Maven (`javafx:run`) |
| **Build and Test** | `clean verify`: compile + run all tests |
| **Run Main (direct)** | Runs `Main.java` directly, which is best for the debugger. Prints a harmless `Unsupported JavaFX configuration: … unnamed module` warning |

### Option C: Kiro / VS Code

Open the folder, accept the suggested Java extensions, then run `.\mvnw.cmd javafx:run` in the terminal.

---

## 6. Push changes without login pop-ups (optional)

One time per PC:

```powershell
gh auth login          # choose GitHub.com → HTTPS → login with browser
gh auth setup-git      # makes git use the gh login, so no more prompts
```

Everyday workflow:

```powershell
git pull
# ... make changes ...
.\mvnw.cmd verify
git add <files>
git commit -m "Describe the change"
git push
```

---

## 7. Troubleshooting

| Problem | Fix |
|---|---|
| `The JAVA_HOME environment variable is not defined correctly` | Point `JAVA_HOME` at the JDK folder (the one that contains `bin\java.exe`), then open a new terminal |
| `This project needs JDK 21 or newer` | An older Java is active. Install Temurin 21 and update `JAVA_HOME` |
| `JavaFX runtime components are missing` | Run `com.selfdriving.Main`, not `SelfDrivingApp`, or use **Run App** |
| First build hangs or fails downloading | A restricted network (office or public Wi-Fi) or a proxy may block Maven Central. Try another network, e.g. a phone hotspot, for the first build |
| IntelliJ shows red imports | Maven panel (right side) → 🔄 *Reload All Maven Projects* |
| `LF will be replaced by CRLF` warnings in Git | Harmless. Line endings are handled by `.gitattributes` |
| `scripts\check-environment.ps1` is blocked | Run the `.cmd` version instead. It bypasses the execution policy for that script only |
| `WARNING: A terminally deprecated method in sun.misc.Unsafe has been called` | Harmless. JavaFX 21 prints it when running on JDK 24 or newer. It does not appear on JDK 21 |
| The car does not react to keys | Click inside the window first so it has keyboard focus |
