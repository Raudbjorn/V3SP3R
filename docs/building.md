# Building V3SP3R

## Toolchain

- JDK **21**; Eclipse Temurin **21.0.13+9** is the version used by the manual GitHub workflow.
- Gradle **9.8.2**, downloaded and checksum-verified by the checked-in wrapper.
- Android SDK Platform **35** and Build Tools **36.0.0** (AGP 9.0.1).

Install [Temurin 21](https://adoptium.net/temurin/releases/?version=21), set
`JAVA_HOME` to its installation directory, and add `$JAVA_HOME/bin` to `PATH`.
In Android Studio, select the same JDK under **Settings > Build, Execution,
Deployment > Build Tools > Gradle > Gradle JDK**.

Set `ANDROID_HOME` to your Android SDK directory, or create an ignored
`local.properties` at the repository root containing `sdk.dir=/absolute/path/to/sdk`.
Install the required SDK packages using Android Studio's SDK Manager or:

```bash
sdkmanager 'platforms;android-35' 'build-tools;36.0.0'
./gradlew --version
```

Both Java and Kotlin compile for JVM 21. Android runs the resulting DEX code;
this does not expose every desktop Java 21 library API on Android. The minimum
Android version remains **8.0 / API 26**, which includes the Galaxy Z Fold4.

## Build the current code

From the repository root:

```bash
./scripts/build-release.sh
```

This builds the current working tree, including uncommitted source changes,
with release optimization, code shrinking, and resource shrinking enabled.
Additional Gradle options can be appended, such as `--stacktrace`.

Without signing credentials, the output is:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

**An unsigned APK cannot be installed.** Configure signing below to produce:

```text
app/build/outputs/apk/release/app-release.apk
```

The underlying build command is `./gradlew :app:assembleRelease`. For an
immediately installable development build using Android's debug key:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release signing

Use the existing release key if this application has already been distributed.
Android requires the same signing key to update an installed application.
For a new signing identity, generate a key interactively (passwords are prompted):

```bash
mkdir -p keystore
keytool -genkeypair -keystore keystore/vesper-release.jks \
  -alias vesper -keyalg RSA -keysize 3072 -validity 10000
```

Keep a secure backup of the key and passwords. Add all four properties to the
root `local.properties` file, alongside `sdk.dir` if present:

```properties
RELEASE_STORE_FILE=../keystore/vesper-release.jks
RELEASE_STORE_PASSWORD=replace-with-store-password
RELEASE_KEY_ALIAS=vesper
RELEASE_KEY_PASSWORD=replace-with-key-password
```

Keystore paths are relative to `app/`; absolute paths are also accepted.
Alternatively, provide these four names as environment variables. Nonblank
environment values take precedence over `local.properties`. Incomplete signing
configuration fails with the missing property names, without printing passwords.
If no signing values are supplied, the release build remains unsigned; it never
falls back to a debug signing key.

```bash
./scripts/build-release.sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose \
  app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Preserve `app/build/outputs/mapping/release/mapping.txt` with each distributed
release to decode obfuscated crash reports. A successful build and signature
check do not establish that BLE, USB, AI, or the UI work on a physical device.

## Manual GitHub build

`.github/workflows/android.yml` has only `workflow_dispatch`. Pushes, pull
requests, tags, and schedules do not start builds. No workflow is dispatched by
the local build script. GitHub discovers workflows under `.github/workflows/`,
not `.github/actions/`.

After the workflow is committed and pushed to the default branch, open
**Actions > Build APK > Run workflow**, select the branch, and run it. GitHub
builds the selected remote commit; it cannot include uncommitted local edits.
The default artifact is explicitly named **unsigned** and cannot be installed.

For a signed artifact, first configure these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 encoding of your release keystore |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Signing key alias |
| `RELEASE_KEY_PASSWORD` | Signing key password |

Then check the **signed** input when manually dispatching the workflow. The
workflow restores the key into the runner's temporary directory and removes it
after the build, including on failure. APK and R8 mapping artifacts are retained
for 14 days. The workflow does not publish a GitHub release or upload to a store.

## Generated files

Build directories, Gradle/Kotlin caches, APKs, bundles, local SDK settings,
signing keys, and macOS metadata are ignored. Previously committed generated
files have been removed from the Git index; local files remain available.
This does not remove those files from existing Git history.
