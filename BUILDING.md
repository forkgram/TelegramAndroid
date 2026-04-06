## Building instructions for Debian 13

1. Install necessary software
      - `sudo apt install cmake golang gperf meson ninja-build sdkmanager wget yasm`
      - `sudo apt install google-android-platform-35-installer google-android-build-tools-35.0.0-installer google-android-ndk-r21e-installer google-android-ndk-r23c-installer openjdk-21-jdk-headless`

2. Don't forget to include the submodules when you clone:
      - `git clone --recursive https://github.com/forkgram/TelegramAndroid.git`

3. Build native FFmpeg and BoringSSL dependencies:
      - Go to the `TMessagesProj/jni` folder and execute the following (define the paths to your NDK and Ninja):

      ```
      export NDK=/usr/lib/android-sdk/ndk/21.4.7075529
      export NINJA_PATH=/usr/bin/ninja
      export ANDROID_SDK=/usr/lib/android-sdk
      export ANDROID_HOME=/usr/lib/android-sdk
      sudo sdkmanager "cmake;3.22.1" --sdk_root /usr/lib/android-sdk
      ./build_libvpx_clang.sh
      ./build_ffmpeg_clang.sh
      ./patch_ffmpeg.sh
      ./patch_boringssl.sh
      ./build_boringssl.sh
      ```

4. If you want to publish a modified version of Telegram:
      - You should get **your own API key** here: https://core.telegram.org/api/obtaining_api_id and edit a file called `gradle.properties` in the source root directory.
        The contents should look like this:
        ```
        APP_ID = 12345
        APP_HASH = aaaaaaaabbbbbbccccccfffffff001122
        ```
      - Do not use the name Telegram and the standard logo (white paper plane in a blue circle) for your app — or make sure your users understand that it is unofficial
      - Take good care of your users' data and privacy
      - **Please remember to publish your code too in order to comply with the licenses**

The project can be built with Android Studio or from the command line with gradle:

`./gradlew assembleAfatRelease`

## Reproducible builds

The repository ships a pinned Docker image and a build script that produce
byte-for-byte identical APKs across machines, given the same git revision and
signing key.

Pinned in [docker/Dockerfile](docker/Dockerfile):

- Ubuntu 22.04, JDK 17, UTC timezone, `C.UTF-8` locale
- Android command-line tools `11076708`
- SDK platform 35, Build Tools 35.0.0
- NDK `21.4.7075529` (native dependency scripts), `23.2.8568313`, `27.2.12479018` (gradle `externalNativeBuild`)
- CMake `3.10.2.4988404`

### Building

```
scripts/reproducible-build.sh
```

`SOURCE_DATE_EPOCH` is taken from `git log -1 --format=%ct HEAD`, then exported
into the container. The script applies [reproducible.gradle](reproducible.gradle)
as an init script, which forces `preserveFileTimestamps = false` and
`reproducibleFileOrder = true` on every archive task (AGP 8 already does this
for APKs and AABs; the init script covers custom Zip/Jar tasks).

To build a non-default target:

```
scripts/reproducible-build.sh :TMessagesProj_App:bundleBundleAfatRelease
```

### Verifying a published APK

```
scripts/verify-apk.sh path/to/published.apk \
    TMessagesProj_App/build/outputs/apk/afat/release/app.apk
```

The script delegates to [apkdiff.py](apkdiff.py) which ignores the v1 signature
block (`META-INF/MANIFEST.MF`, `META-INF/CERT.RSA`, `META-INF/CERT.SF`) since
those depend on the signing key, not on the source.

### Known caveats

- **Build host OS leaks into the APK.** `Utilities.parseInt` branches on
  `BuildConfig.BUILD_HOST_IS_WINDOWS`, which is set at build time. The
  reproducible image is Linux, so the canonical value is always `false`.
  Builds run directly on Windows will diverge.
- **Signing key.** Two builds signed with different keys will differ in the
  signature blocks. Use `scripts/verify-apk.sh` to compare ignoring those
  entries, or sign the rebuilt APK with the same key before comparing.
- **NDK / AGP upgrades.** Bumping `ndkVersion`, `buildToolsVersion`, or the
  AGP/Kotlin plugin versions invalidates the reproducibility baseline. Update
  the Docker image in the same commit and rebuild the reference APK.