package org.telegram.messenger;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;
import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public class CloudflareSTT {
    private static final Gson gson = new Gson();
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    public static boolean isConfigured() {
        return SharedConfig.cfEnableStt && !TextUtils.isEmpty(SharedConfig.cfAccountID) && !TextUtils.isEmpty(SharedConfig.cfApiToken);
    }

    public static void showErrorDialog(Exception e) {
        var fragment = LaunchActivity.getSafeLastFragment();
        var message = e.getLocalizedMessage();
        if (fragment == null || !BulletinFactory.canShowBulletin(fragment) || message == null) {
            return;
        }
        if (message.length() > 45) {
            AlertsCreator.showSimpleAlert(fragment, LocaleController.getString("ErrorOccurred", R.string.ErrorOccurred), e.getMessage());
        } else {
            BulletinFactory.of(fragment).createErrorBulletin(message).show();
        }
    }

    private static void extractAudio(String inputFilePath, String outputFilePath) throws IOException {
        var extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(inputFilePath);

            MediaFormat audioFormat = null;
            int audioTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                var format = extractor.getTrackFormat(i);
                var mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioFormat = format;
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioFormat == null) {
                throw new IOException("No audio track found in " + inputFilePath);
            }

            muxer = new MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            var trackIndex = muxer.addTrack(audioFormat);
            muxer.start();

            extractor.selectTrack(audioTrackIndex);

            var bufferInfo = new MediaCodec.BufferInfo();
            var buffer = ByteBuffer.allocate(65536);

            while (true) {
                var sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    break;
                }

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = 0;

                muxer.writeSampleData(trackIndex, buffer, bufferInfo);
                extractor.advance();
            }

            muxer.stop();
        } finally {
            if (muxer != null) {
                muxer.release();
            }
            extractor.release();
        }
    }

    public static void requestWorkersAi(String path, boolean video, BiConsumer<String, Exception> callback) {
        if (!isConfigured()) {
            callback.accept(null, new Exception(LocaleController.getString("CloudflareCredentialsNotSet", R.string.CloudflareCredentialsNotSet)));
            return;
        }
        executorService.submit(() -> {
            File audioPath;
            if (video) {
                var audioFile = new File(path + ".m4a");
                try {
                    extractAudio(path, audioFile.getAbsolutePath());
                } catch (IOException e) {
                    FileLog.e(e);
                    callback.accept(null, e);
                    return;
                }
                audioPath = audioFile;
            } else {
                audioPath = new File(path);
            }
            byte[] audio;
            try {
                audio = Files.readAllBytes(audioPath.toPath());
            } catch (IOException e) {
                callback.accept(null, e);
                return;
            }

            var payload = new WhisperRequest();
            payload.audio = Base64.encodeToString(audio, Base64.NO_WRAP);
            payload.vadFilter = false;

            try {
                URL url = new URL("https://api.cloudflare.com/client/v4/accounts/" + SharedConfig.cfAccountID + "/ai/run/@cf/openai/whisper-large-v3-turbo");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + SharedConfig.cfApiToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(120000);
                conn.setReadTimeout(120000);
                conn.setDoOutput(true);

                String jsonInputString = gson.toJson(payload);
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }

                var whisperResponse = gson.fromJson(response.toString(), WhisperResponse.class);
                if (whisperResponse.success != null && whisperResponse.success && whisperResponse.result != null) {
                    callback.accept(whisperResponse.result.text, null);
                } else {
                    var errors = whisperResponse.errors;
                    if (errors != null && !errors.isEmpty()) {
                        callback.accept(null, new Exception(errors.size() == 1 ? errors.get(0).message : errors.toString()));
                    } else {
                        callback.accept(null, new Exception("Unknown error from Cloudflare: " + code));
                    }
                }
            } catch (Exception e) {
                callback.accept(null, e);
            }
        });
    }

    public static class WhisperRequest {
        @SerializedName("audio")
        @Expose
        public String audio;
        @SerializedName("vad_filter")
        @Expose
        public Boolean vadFilter;
    }

    public static class Result {
        @SerializedName("text")
        @Expose
        public String text;
    }

    public static class WhisperResponse {
        @SerializedName("result")
        @Expose
        public Result result;
        @SerializedName("success")
        @Expose
        public Boolean success;
        @SerializedName("errors")
        @Expose
        public List<Error> errors;
    }

    public static class Error {
        @SerializedName("message")
        @Expose
        public String message;

        @Override
        public String toString() {
            return message;
        }
    }
}
