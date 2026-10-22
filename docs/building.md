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
For a command-line installation, first install the Android **Command-line Tools**
under `<sdk>/cmdline-tools/latest/` as described in the
[sdkmanager setup instructions](https://developer.android.com/tools/sdkmanager).
Setting `ANDROID_HOME` alone does not put SDK executables on `PATH`. For example,
adjust the SDK location below to your installation, then install the packages:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-35' 'build-tools;36.0.0'
./gradlew --version
```

Android Studio's SDK Manager can install the same packages instead.

Both Java and Kotlin compile for JVM 21. Android runs the resulting DEX code;
this does not expose every desktop Java 21 library API on Android. The minimum
Android version remains **8.0 / API 26**, which includes the Galaxy Z Fold4.

## Updating an existing checkout

This change stops tracking `local.properties`. **Git can delete an unchanged
tracked copy when you pull or switch to this revision.** Before updating a
checkout that has this file, back up its SDK and signing settings outside the
repository, then restore them immediately after the update:

```bash
vesper_config_backup="$(mktemp -d)"
cp -p local.properties "$vesper_config_backup/local.properties" &&
  printf 'Local configuration backup: %s/local.properties\n' "$vesper_config_backup" &&
  git pull --ff-only &&
  cp -p "$vesper_config_backup/local.properties" local.properties
```

The temporary directory is private, and the backup stays available if the update
fails. If Git refuses to update because of local edits, resolve the checkout
update without discarding the backup, then restore it before building. The same
backup/restore procedure applies when switching branches instead of pulling.
Keep the backup until the restored configuration works; signing settings may
contain passwords, so do not commit or share it.

If you already updated and `local.properties` disappeared, recreate `sdk.dir`
or set `ANDROID_HOME` using the toolchain instructions above. Restore signing
settings from your own secure backup. A previous committed version contains only
what was committed, not any later private settings. A checkout with no signing
settings still builds an unsigned release APK.

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

### Device profile

Release builds default to the `fold4` profile (`vesper.deviceProfile` in
`gradle.properties`). It targets the Samsung Galaxy Z Fold 4 (Snapdragon 8+
Gen 1) and packages only the `arm64-v8a` native libraries, which cuts the APK
from about 23 MB to about 9 MB. The app has no native code of its own; the
removed libraries are SQLCipher and DataStore builds for 32-bit ARM and x86.
Any other 64-bit ARM phone can still install the `fold4` APK. 32-bit devices
and x86 emulators need the universal build:

```bash
./gradlew :app:assembleRelease -Pvesper.deviceProfile=universal
```

Debug builds always include every ABI.

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
Alternatively, provide these four names as environment variables. If **any** is
set, including an empty value, the environment must provide all four nonblank
values. Otherwise the complete set is read from `local.properties`. Credentials
are never combined across sources. Incomplete signing configuration fails with
the source and missing property names, without printing passwords.
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
Artifacts are always **unsigned** and cannot be installed until signed. The
workflow has no signing input, secret references, or keystore restore step;
branch-selected build code receives no permanent signing credentials. All
third-party actions are pinned to full commit hashes.

Keep the permanent key local. Do not add release signing credentials as
repository Actions secrets for this workflow. If you added them following the
earlier draft, remove those unused secrets in the repository settings; this code
change cannot revoke secrets already stored on GitHub or remove old workflow
revisions that reference them.

To sign a downloaded artifact locally, extract it and run the following from
the directory containing `app-release-unsigned.apk`. Adjust the keystore path
and alias to your permanent key; `apksigner` prompts for passwords:

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
  --ks /absolute/path/to/vesper-release.jks --ks-key-alias vesper \
  --out app-release.apk app-release-unsigned.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app-release.apk
```

Review the chosen commit before signing its APK. APK and R8 mapping artifacts
are retained for 14 days. The workflow does not publish a GitHub release or
upload to a store.

## Signing configuration regression checks

With JDK 21 available, run `python3 scripts/test-release-signing.py`. The check
configures Gradle in a temporary project using copies of the real build scripts
and synthetic credentials. It exercises complete, missing, partial, blank, and
conflicting sources without changing your `local.properties` or accessing your
signing key. It does not build or sign an APK.

## Generated files

Build directories, Gradle/Kotlin caches, APKs, bundles, local SDK settings,
signing keys, and macOS metadata are ignored. Previously committed generated
files have been removed from the Git index. They were retained in the checkout
where this change was prepared; other checkouts can lose unchanged tracked
copies when updating, so follow the migration steps above for local settings.
Build outputs can be regenerated. This does not remove files from Git history.
