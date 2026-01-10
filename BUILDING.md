## Building instructions for Debian 13

1. Install necessary software
      - `sudo apt install cmake golang gperf meson ninja-build sdkmanager wget yasm`
      - `sudo apt install google-android-platform-35-installer google-android-build-tools-35.0.0-installer google-android-ndk-r23c-installer openjdk-21-jdk-headless`

2. Don't forget to include the submodules when you clone:
      - `git clone --recursive https://github.com/forkgram/TelegramAndroid.git`

3. Build native FFmpeg, libvpx, dav1d and BoringSSL dependencies:
      - Go to the `TMessagesProj/jni` folder and execute the following (define the paths to your NDK and Ninja):

      ```
      export ANDROID_NDK_HOME=/usr/lib/android-sdk/ndk/27.2.12479018
      export NDK=$ANDROID_NDK_HOME
      export NINJA_PATH=/usr/bin/ninja
      export ANDROID_SDK=/usr/lib/android-sdk
      export ANDROID_HOME=/usr/lib/android-sdk
      sudo sdkmanager "cmake;3.22.1" "ndk;27.2.12479018" --sdk_root /usr/lib/android-sdk
      ./ffmpeg/build_ffmpeg_libvpx_dav1d_android_ndk27_merged.sh
      ./patch_boringssl.sh
      ./build_boringssl.sh
      ```

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