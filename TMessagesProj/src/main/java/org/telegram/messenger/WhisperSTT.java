package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

// Client for a self-hosted whisper-asr-webservice instance
// (https://github.com/ahmetoner/whisper-asr-webservice).
// POST {base}/asr?output=json&encode=true with the audio as a multipart
// "audio_file" field; encode=true lets the server decode any container
// via ffmpeg, so voice notes are sent as-is.
public class WhisperSTT {
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final String CRLF = "\r\n";

    public static boolean isConfigured() {
        return SharedConfig.whisperEnableStt && !TextUtils.isEmpty(SharedConfig.whisperUrl);
    }

    private static String buildAsrUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/asr?task=transcribe&output=json&encode=true";
    }

    public static void requestTranscription(String path, boolean video, BiConsumer<String, Exception> callback) {
        if (!isConfigured()) {
            callback.accept(null, new Exception(LocaleController.getString("WhisperNotConfigured", R.string.WhisperNotConfigured)));
            return;
        }
        executorService.submit(() -> {
            File audioPath;
            if (video) {
                var audioFile = new File(path + ".m4a");
                try {
                    CloudflareSTT.extractAudio(path, audioFile.getAbsolutePath());
                } catch (IOException e) {
                    FileLog.e(e);
                    audioFile.delete();
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
            } finally {
                if (video) {
                    // The extracted m4a is only an upload staging file; the
                    // original video stays in the cache.
                    audioPath.delete();
                }
            }

            try {
                String requestUrl = buildAsrUrl(SharedConfig.whisperUrl);
                FileLog.d("WhisperSTT: POST " + requestUrl + " payload " + audio.length + " bytes");
                long start = System.currentTimeMillis();

                String boundary = "----TelegramWhisperSTT" + System.currentTimeMillis();
                URL url = new URL(requestUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("Accept", "application/json");
                if (!TextUtils.isEmpty(SharedConfig.whisperAuthHeaderName)) {
                    conn.setRequestProperty(SharedConfig.whisperAuthHeaderName, SharedConfig.whisperAuthHeaderValue == null ? "" : SharedConfig.whisperAuthHeaderValue);
                }
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(300000);
                conn.setDoOutput(true);

                try (DataOutputStream os = new DataOutputStream(conn.getOutputStream())) {
                    os.writeBytes("--" + boundary + CRLF);
                    os.writeBytes("Content-Disposition: form-data; name=\"audio_file\"; filename=\"" + audioPath.getName() + "\"" + CRLF);
                    os.writeBytes("Content-Type: application/octet-stream" + CRLF);
                    os.writeBytes(CRLF);
                    os.write(audio);
                    os.writeBytes(CRLF);
                    os.writeBytes("--" + boundary + "--" + CRLF);
                }

                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder response = new StringBuilder();
                if (stream != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(stream, "utf-8"));
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine);
                    }
                }
                FileLog.d("WhisperSTT: response HTTP " + code + " in " + (System.currentTimeMillis() - start) + "ms, " + response.length() + " chars");

                if (code < 200 || code >= 300) {
                    callback.accept(null, new Exception("Whisper server HTTP " + code + ": " + clip(response.toString(), 200)));
                    return;
                }
                callback.accept(parseResponse(response.toString()), null);
            } catch (Exception e) {
                FileLog.e(e);
                callback.accept(null, e);
            }
        });
    }

    // whisper-asr-webservice returns {"text": "...", "segments": [...], "language": "..."}.
    // WhisperX-based servers omit the top-level "text" and only fill per-segment
    // texts; whisper segment texts carry their own leading spaces.
    private static String parseResponse(String body) throws Exception {
        try {
            JSONObject json = new JSONObject(body);
            String text = json.optString("text", "").trim();
            if (text.isEmpty()) {
                JSONArray segments = json.optJSONArray("segments");
                if (segments != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < segments.length(); i++) {
                        sb.append(segments.getJSONObject(i).optString("text", ""));
                    }
                    text = sb.toString().trim();
                }
            }
            return text;
        } catch (Exception e) {
            throw new Exception("Invalid JSON from Whisper server: " + clip(body, 200), e);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
