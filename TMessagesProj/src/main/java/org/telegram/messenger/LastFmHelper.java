package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class LastFmHelper {

    private static LastFmHelper instance;
    private SharedPreferences prefs;
    private long lastApiCallTime = 0;
    private static final long API_CALL_INTERVAL = 30000; // 30 seconds

    public static LastFmHelper getInstance() {
        if (instance == null) {
            instance = new LastFmHelper();
        }
        return instance;
    }

    private LastFmHelper() {
        if (ApplicationLoader.applicationContext != null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences("lastfm", Context.MODE_PRIVATE);
        }
    }

    public boolean isLoggedIn() {
        return prefs != null && prefs.getBoolean("logged_in", false);
    }

    public String getUsername() {
        return prefs != null ? prefs.getString("username", "") : "";
    }

    public void logout() {
        if (prefs != null) {
            prefs.edit().clear().apply();
        }
    }
    
    public void login(String username, String password, LoginCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                // Step 1: Get token
                String token = getToken();
                if (token == null) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Failed to get token"));
                    return;
                }
                
                // Step 2: Get session
                String sessionKey = getSession(username, password, token);
                if (sessionKey != null) {
                    prefs.edit()
                        .putBoolean("logged_in", true)
                        .putString("username", username)
                        .putString("session_key", sessionKey)
                        .apply();
                    AndroidUtilities.runOnUIThread(() -> callback.onSuccess());
                } else {
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Invalid credentials"));
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    public interface LoginCallback {
        void onSuccess();
        void onError(String error);
    }

    public void scrobbleTrack(String artist, String track, String album, long timestamp) {
        if (!isLoggedIn() || artist == null || track == null) {
            return;
        }
        
        if (!canMakeApiCall()) {
            return;
        }
        
        Map<String, String> params = new HashMap<>();
        params.put("method", "track.scrobble");
        params.put("api_key", BuildVars.LASTFM_API_KEY);
        params.put("artist", artist);
        params.put("track", track);
        if (album != null) {
            params.put("album", album);
        }
        params.put("timestamp", String.valueOf(timestamp));
        params.put("sk", getSessionKey());
        params.put("api_sig", generateApiSignature(params));
        
        Utilities.globalQueue.postRunnable(() -> {
            try {
                sendPostRequestVoid(params);
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Last.fm scrobbled: " + artist + " - " + track);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
    
    public void updateNowPlaying(String artist, String track, String album) {
        if (!isLoggedIn() || artist == null || track == null) {
            return;
        }
        
        if (!canMakeApiCall()) {
            return;
        }
        
        Map<String, String> params = new HashMap<>();
        params.put("method", "track.updateNowPlaying");
        params.put("api_key", BuildVars.LASTFM_API_KEY);
        params.put("artist", artist);
        params.put("track", track);
        if (album != null) {
            params.put("album", album);
        }
        params.put("sk", getSessionKey());
        params.put("api_sig", generateApiSignature(params));
        
        Utilities.globalQueue.postRunnable(() -> {
            try {
                sendPostRequestVoid(params);
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Last.fm now playing: " + artist + " - " + track);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
    
    private String getSessionKey() {
        return prefs != null ? prefs.getString("session_key", "") : "";
    }
    
    private boolean canMakeApiCall() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastApiCallTime >= API_CALL_INTERVAL) {
            lastApiCallTime = currentTime;
            return true;
        }
        return false;
    }

    public String generateApiSignature(Map<String, String> params) {
        TreeMap<String, String> sortedParams = new TreeMap<>(params);
        StringBuilder signature = new StringBuilder();
        
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            signature.append(entry.getKey()).append(entry.getValue());
        }
        signature.append(BuildVars.LASTFM_API_SECRET);
        
        return md5(signature.toString());
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    private String getToken() {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("method", "auth.getToken");
            params.put("api_key", BuildVars.LASTFM_API_KEY);
            params.put("api_sig", generateApiSignature(params));
            
            String response = sendGetRequest(params);
            // Parse XML response to get token
            // Simplified implementation - real project needs XML parser
            if (response != null && response.contains("<token>")) {
                int start = response.indexOf("<token>") + 7;
                int end = response.indexOf("</token>");
                if (start > 6 && end > start) {
                    return response.substring(start, end);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }
    
    private String getSession(String username, String password, String token) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("method", "auth.getMobileSession");
            params.put("api_key", BuildVars.LASTFM_API_KEY);
            params.put("username", username);
            params.put("password", password);
            params.put("api_sig", generateApiSignature(params));
            
            String response = sendPostRequest(params);
            // Parse XML response to get session key
            if (response != null && response.contains("<key>")) {
                int start = response.indexOf("<key>") + 5;
                int end = response.indexOf("</key>");
                if (start > 4 && end > start) {
                    return response.substring(start, end);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }
    
    private String sendGetRequest(Map<String, String> params) {
        try {
            StringBuilder urlBuilder = new StringBuilder(BuildVars.LASTFM_API_URL + "?");
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (urlBuilder.length() > BuildVars.LASTFM_API_URL.length() + 1) {
                    urlBuilder.append('&');
                }
                urlBuilder.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"));
                urlBuilder.append('=');
                urlBuilder.append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            
            java.net.URL url = new java.net.URL(urlBuilder.toString());
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                connection.disconnect();
                return response.toString();
            }
            connection.disconnect();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }
    
    private String sendPostRequest(Map<String, String> params) {
        try {
            java.net.URL url = new java.net.URL(BuildVars.LASTFM_API_URL);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            
            StringBuilder postData = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (postData.length() != 0) {
                    postData.append('&');
                }
                postData.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"));
                postData.append('=');
                postData.append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            
            byte[] postDataBytes = postData.toString().getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
            connection.getOutputStream().write(postDataBytes);
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                connection.disconnect();
                return response.toString();
            }
            
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Last.fm API response code: " + responseCode);
            }
            
            connection.disconnect();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }
    
    private void sendPostRequestVoid(Map<String, String> params) {
        try {
            java.net.URL url = new java.net.URL(BuildVars.LASTFM_API_URL);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            
            StringBuilder postData = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (postData.length() != 0) {
                    postData.append('&');
                }
                postData.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"));
                postData.append('=');
                postData.append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            
            byte[] postDataBytes = postData.toString().getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
            connection.getOutputStream().write(postDataBytes);
            
            int responseCode = connection.getResponseCode();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Last.fm API response code: " + responseCode);
            }
            
            connection.disconnect();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}