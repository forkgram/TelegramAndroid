package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.TreeMap;

public class LastFmLoginActivity extends BaseFragment {

    private static final String API_KEY = BuildVars.LASTFM_API_KEY;
    private static final String API_SECRET = BuildVars.LASTFM_API_SECRET;
    private TextView loginButton;
    private TextView statusText;
    private boolean waitingForAuth = false;
    private boolean isLoggedIn = false;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Last.fm Login");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        
        checkLoginStatus();

        fragmentView = new LinearLayout(context);
        LinearLayout linearLayout = (LinearLayout) fragmentView;
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        TextView headerText = new TextView(context);
        headerText.setText("Connect your Last.fm account");
        headerText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        headerText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        headerText.setGravity(Gravity.CENTER);
        linearLayout.addView(headerText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 24, 24, 12));

        TextView descText = new TextView(context);
        descText.setText("Get authorization token from Last.fm");
        descText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        descText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        descText.setGravity(Gravity.CENTER);
        linearLayout.addView(descText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 12, 24, 12));
        
        statusText = new TextView(context);
        statusText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusText.setGravity(Gravity.CENTER);
        linearLayout.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 12, 24, 12));

        loginButton = new TextView(context);
        loginButton.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
        loginButton.setGravity(Gravity.CENTER);
        loginButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        loginButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        loginButton.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        loginButton.setBackgroundDrawable(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(4), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        updateUI();
        linearLayout.addView(loginButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 24, 24, 24, 0));

        return fragmentView;
    }

    private void checkLoginStatus() {
        SharedPreferences prefs = getParentActivity().getSharedPreferences("lastfm", Context.MODE_PRIVATE);
        isLoggedIn = prefs.getBoolean("logged_in", false);
    }
    
    private void updateUI() {
        if (isLoggedIn) {
            statusText.setText("✓ Logged in to Last.fm");
            statusText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText));
            loginButton.setText("Logout");
            loginButton.setOnClickListener(v -> logout());
        } else {
            statusText.setText("Not logged in");
            statusText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            loginButton.setText("Get Token");
            loginButton.setOnClickListener(v -> getAuthToken());
        }
    }
    
    private void logout() {
        SharedPreferences prefs = getParentActivity().getSharedPreferences("lastfm", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        isLoggedIn = false;
        updateUI();
    }
    
    private void getAuthToken() {
        new GetTokenTask().execute();
    }

    private class GetTokenTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... params) {
            try {
                TreeMap<String, String> apiParams = new TreeMap<>();
                apiParams.put("method", "auth.getToken");
                apiParams.put("api_key", API_KEY);
                
                String apiSig = generateApiSignature(apiParams);
                
                String url = "http://ws.audioscrobbler.com/2.0/?method=auth.getToken&api_key=" + 
                           API_KEY + "&api_sig=" + apiSig + "&format=json";
                
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                String jsonResponse = response.toString();
                if (jsonResponse.contains("\"token\":\"")) {
                    int start = jsonResponse.indexOf("\"token\":\"") + 9;
                    int end = jsonResponse.indexOf("\"", start);
                    return jsonResponse.substring(start, end);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
        
        @Override
        protected void onPostExecute(String token) {
            if (token != null) {
                String authUrl = "http://www.last.fm/api/auth/?api_key=" + API_KEY + "&token=" + token;
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
                getParentActivity().startActivity(intent);
                
                // Store token for later session creation
                SharedPreferences prefs = getParentActivity().getSharedPreferences("lastfm", Context.MODE_PRIVATE);
                prefs.edit().putString("auth_token", token).apply();
                
                waitingForAuth = true;
                loginButton.setText("Waiting for authorization...");
                loginButton.setEnabled(false);
            }
        }
    }
    
    public void createSession() {
        SharedPreferences prefs = getParentActivity().getSharedPreferences("lastfm", Context.MODE_PRIVATE);
        String token = prefs.getString("auth_token", null);
        if (token != null) {
            new GetSessionTask().execute(token);
        }
    }
    
    private class GetSessionTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            String token = params[0];
            try {
                TreeMap<String, String> apiParams = new TreeMap<>();
                apiParams.put("method", "auth.getSession");
                apiParams.put("api_key", API_KEY);
                apiParams.put("token", token);
                
                String apiSig = generateApiSignature(apiParams);
                
                String url = "http://ws.audioscrobbler.com/2.0/?method=auth.getSession&api_key=" + 
                           API_KEY + "&token=" + token + "&api_sig=" + apiSig + "&format=json";
                
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                String jsonResponse = response.toString();
                if (jsonResponse.contains("\"key\":\"")) {
                    int start = jsonResponse.indexOf("\"key\":\"") + 7;
                    int end = jsonResponse.indexOf("\"", start);
                    return jsonResponse.substring(start, end);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
        
        @Override
        protected void onPostExecute(String sessionKey) {
            waitingForAuth = false;
            if (sessionKey != null) {
                SharedPreferences prefs = getParentActivity().getSharedPreferences("lastfm", Context.MODE_PRIVATE);
                prefs.edit()
                        .putString("session_key", sessionKey)
                        .putBoolean("logged_in", true)
                        .apply();
                isLoggedIn = true;
                updateUI();
            } else {
                loginButton.setText("Get Token");
                loginButton.setEnabled(true);
            }
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        if (waitingForAuth) {
            // Try to create session when user returns from browser
            AndroidUtilities.runOnUIThread(() -> createSession(), 1000);
        }
    }
    
    private String generateApiSignature(TreeMap<String, String> params) {
        StringBuilder sig = new StringBuilder();
        for (String key : params.keySet()) {
            sig.append(key).append(params.get(key));
        }
        sig.append(API_SECRET);
        
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(sig.toString().getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}