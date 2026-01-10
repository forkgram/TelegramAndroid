## Building instructions for Debian 13

1. Install necessary software
      - `sudo apt install cmake golang gperf meson nasm ninja-build pkg-config rustup sdkmanager wget yasm`
      - `rustup default stable && rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android`
      - `sudo apt install google-android-platform-36-installer google-android-build-tools-36.0.0-installer google-android-ndk-r27c-installer openjdk-21-jdk-headless`

2. Don't forget to include the submodules when you clone:
      - `git clone --recursive https://github.com/forkgram/TelegramAndroid.git`

3. Build the native dependencies (BoringSSL, OpenH264, Opus, libvpx, dav1d, FFmpeg, tlottie and TDLib):
      - Go to the `TMessagesProj/jni` folder and execute the following (define the paths to your NDK and SDK):

      ```
      export ANDROID_NDK_HOME=/usr/lib/android-sdk/ndk/27.2.12479018
      export ANDROID_SDK_ROOT=/usr/lib/android-sdk
      export ABIS="arm64-v8a armeabi-v7a"
      sudo sdkmanager "cmake;3.22.1" "ndk;27.2.12479018" --sdk_root /usr/lib/android-sdk
      ./prebuild/build_all.sh
      ```

      The archives land in `TMessagesProj/jni/prebuild/lib/<abi>/`; drop `ABIS` to build all four.

      Gradle runs the same steps automatically through `TMessagesProj/jni/prepare.py`.

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