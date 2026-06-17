package org.telegram.messenger.forkgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class SettingsBackup {

    private static final String MARKER = "forkgram_settings_export";

    private static final String[] ALLOWED_PREFS = {
            "mainconfig",
            "themeconfig",
            "lastfm",
            "langconfig",
            "playback_speed",
            "camera",
            "voippipconfig"
    };

    private static boolean isAllowed(String name) {
        for (String allowed : ALLOWED_PREFS) {
            if (allowed.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static String export(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put(MARKER, 1);
        root.put("appVersion", BuildVars.BUILD_VERSION_STRING);
        JSONObject prefsObject = new JSONObject();
        for (String name : ALLOWED_PREFS) {
            Map<String, ?> all = context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll();
            if (all.isEmpty()) {
                continue;
            }
            JSONObject fileObject = new JSONObject();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                Object value = entry.getValue();
                JSONObject typed = new JSONObject();
                if (value instanceof Boolean) {
                    typed.put("t", "b");
                    typed.put("v", (Boolean) value);
                } else if (value instanceof Integer) {
                    typed.put("t", "i");
                    typed.put("v", (Integer) value);
                } else if (value instanceof Long) {
                    typed.put("t", "l");
                    typed.put("v", (Long) value);
                } else if (value instanceof Float) {
                    typed.put("t", "f");
                    typed.put("v", (double) (Float) value);
                } else if (value instanceof String) {
                    typed.put("t", "s");
                    typed.put("v", (String) value);
                } else if (value instanceof Set) {
                    typed.put("t", "ss");
                    JSONArray array = new JSONArray();
                    for (Object item : (Set<?>) value) {
                        array.put(String.valueOf(item));
                    }
                    typed.put("v", array);
                } else {
                    continue;
                }
                fileObject.put(entry.getKey(), typed);
            }
            prefsObject.put(name, fileObject);
        }
        root.put("prefs", prefsObject);
        return root.toString(2);
    }

    public static boolean restore(Context context, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!root.has(MARKER)) {
            return false;
        }
        JSONObject prefsObject = root.optJSONObject("prefs");
        if (prefsObject == null) {
            return false;
        }
        Iterator<String> fileNames = prefsObject.keys();
        while (fileNames.hasNext()) {
            String name = fileNames.next();
            if (!isAllowed(name)) {
                continue;
            }
            JSONObject fileObject = prefsObject.getJSONObject(name);
            SharedPreferences.Editor editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
            Iterator<String> keys = fileObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject typed = fileObject.getJSONObject(key);
                switch (typed.getString("t")) {
                    case "b": editor.putBoolean(key, typed.getBoolean("v")); break;
                    case "i": editor.putInt(key, typed.getInt("v")); break;
                    case "l": editor.putLong(key, typed.getLong("v")); break;
                    case "f": editor.putFloat(key, (float) typed.getDouble("v")); break;
                    case "s": editor.putString(key, typed.getString("v")); break;
                    case "ss": {
                        JSONArray array = typed.getJSONArray("v");
                        HashSet<String> set = new HashSet<>();
                        for (int i = 0; i < array.length(); i++) {
                            set.add(array.getString(i));
                        }
                        editor.putStringSet(key, set);
                        break;
                    }
                }
            }
            editor.apply();
        }
        return true;
    }
}
