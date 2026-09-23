package com.freesmsgateway.poc;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.messaging.FirebaseMessaging;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int CONFIG_VERSION = 4;

    public static final String[] DEFAULT_URLS = new String[] {
        "https://api.aranyasetu.org",
        "http://192.168.29.54:5102",
        "http://115.124.117.226:5102"
    };

    private static WebSocket currentWebSocket;
    private static OkHttpClient okHttpClient;

    private EditText serverUrlInput;
    private Spinner simSelectorSpinner;
    private EditText simNumberInput;
    private TextView simNumberLabel;

    private Button startBtn;
    private Button stopBtn;
    private TextView statusTextView;
    private SharedPreferences prefs;

    static class SimCardItem {
        int slot;
        int subId;
        String carrier;
        String number;

        SimCardItem(int slot, int subId, String carrier, String number) {
            this.slot = slot;
            this.subId = subId;
            this.carrier = carrier;
            this.number = number;
        }

        @Override
        public String toString() {
            String label = "SIM " + (slot + 1) + " (" + carrier + ")";
            if (number != null && !number.isEmpty() && number.matches(".*\\d.*")) {
                label += " - " + number;
            }
            return label;
        }
    }

    private final List<SimCardItem> detectedSims = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrlInput = findViewById(R.id.serverUrlInput);
        simSelectorSpinner = findViewById(R.id.simSelectorSpinner);
        simNumberInput = findViewById(R.id.simNumberInput);
        simNumberLabel = findViewById(R.id.simNumberLabel);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        statusTextView = findViewById(R.id.statusTextView);

        prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
        String savedUrl = prefs.getString("server_url", "");
        if (!savedUrl.isEmpty()) {
            serverUrlInput.setText(savedUrl);
        }

        String savedPhone = prefs.getString("sim_phone_number", "");
        if (!savedPhone.isEmpty()) {
            simNumberInput.setText(savedPhone);
        }

        if (prefs.getInt("fcm_config_ver", 0) < CONFIG_VERSION) {
            prefs.edit().remove("fcm_token").putInt("fcm_config_ver", CONFIG_VERSION).apply();
            try {
                FirebaseMessaging.getInstance().deleteToken();
            } catch (Exception ignored) {}
        }

        requestPermissionsIfNeeded();
        requestBatteryExemptionIfNeeded();
        detectSimInfo();

        // Auto-subscribe if previously active
        if (prefs.getBoolean("is_active", false)) {
            FirebaseMessaging.getInstance().subscribeToTopic("sms_gateway");
        }

        startBtn.setOnClickListener(v -> {
            String enteredUrl = serverUrlInput.getText().toString().trim();
            String customPhone = simNumberInput.getText().toString().trim();

            statusTextView.setText("Status: 🟡 CONNECTING...");
            statusTextView.setTextColor(android.graphics.Color.parseColor("#EAB308"));
            startBtn.setEnabled(false);

            new Thread(() -> {
                String targetUrl = null;
                int code = -1;

                if (!enteredUrl.isEmpty()) {
                    // When user enters URL in field: USE THIS URL
                    targetUrl = enteredUrl;
                    code = HttpHelper.get(targetUrl + "/v1/system/health");
                } else {
                    // When user has NOT entered URL: USE FROM INITIAL DEFAULT URLS
                    for (String candidate : DEFAULT_URLS) {
                        int c = HttpHelper.get(candidate + "/v1/system/health");
                        if (c == 200) {
                            targetUrl = candidate;
                            code = c;
                            break;
                        }
                    }
                    if (targetUrl == null) {
                        targetUrl = DEFAULT_URLS[0];
                    }
                }

                final String finalUrl = targetUrl;
                final int finalCode = code;

                runOnUiThread(() -> {
                    startBtn.setEnabled(true);
                    if (finalCode == 200) {
                        prefs.edit()
                                .putString("server_url", finalUrl)
                                .putString("sim_phone_number", customPhone)
                                .putBoolean("is_active", true)
                                .apply();

                        statusTextView.setText("Status: 🟢 ONLINE (" + finalUrl + ")");
                        statusTextView.setTextColor(android.graphics.Color.parseColor("#22C55E"));

                        FirebaseMessaging.getInstance().subscribeToTopic("sms_gateway")
                                .addOnCompleteListener(task -> {
                                    if (task.isSuccessful()) {
                                        Log.i(TAG, "Subscribed to FCM topic: sms_gateway");
                                    }
                                });

                        refreshAndRegister(finalUrl);
                        Toast.makeText(this, "Connected: " + finalUrl, Toast.LENGTH_SHORT).show();
                    } else {
                        statusTextView.setText("Status: 🔴 SERVER OFFLINE (" + finalUrl + ")");
                        statusTextView.setTextColor(android.graphics.Color.parseColor("#EF4444"));
                        Toast.makeText(this, "Cannot connect to: " + finalUrl, Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        });

        stopBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean("is_active", false).apply();

            statusTextView.setText("Status: 🔴 STOPPED");
            statusTextView.setTextColor(android.graphics.Color.parseColor("#EF4444"));

            FirebaseMessaging.getInstance().unsubscribeFromTopic("sms_gateway");
            disconnectWebSocket();

            String url = serverUrlInput.getText().toString().trim();
            if (url.isEmpty()) {
                url = prefs.getString("server_url", DEFAULT_URLS[0]);
            }
            unregisterDeviceFromServer(url);

            Toast.makeText(this, "Gateway Stopped", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getBoolean("is_active", false)) {
            String enteredUrl = serverUrlInput.getText().toString().trim();
            String savedUrl = prefs.getString("server_url", "");
            String url = !enteredUrl.isEmpty() ? enteredUrl : (!savedUrl.isEmpty() ? savedUrl : DEFAULT_URLS[0]);

            statusTextView.setText("Status: 🟡 CONNECTING...");
            statusTextView.setTextColor(android.graphics.Color.parseColor("#EAB308"));

            new Thread(() -> {
                String activeUrl = url;
                int code = HttpHelper.get(activeUrl + "/v1/system/health");
                if (code != 200 && enteredUrl.isEmpty()) {
                    for (String candidate : DEFAULT_URLS) {
                        if (HttpHelper.get(candidate + "/v1/system/health") == 200) {
                            activeUrl = candidate;
                            code = 200;
                            break;
                        }
                    }
                }

                final String finalUrl = activeUrl;
                final int finalCode = code;
                runOnUiThread(() -> {
                    if (prefs.getBoolean("is_active", false)) {
                        if (finalCode == 200) {
                            statusTextView.setText("Status: 🟢 ONLINE (" + finalUrl + ")");
                            statusTextView.setTextColor(android.graphics.Color.parseColor("#22C55E"));
                            String token = prefs.getString("fcm_token", null);
                            registerDeviceWithServer(MainActivity.this, finalUrl, token != null ? token : "topic_subscriber");
                            connectWebSocket(MainActivity.this, finalUrl, token != null ? token : "topic_subscriber");
                        } else {
                            statusTextView.setText("Status: 🔴 SERVER OFFLINE");
                            statusTextView.setTextColor(android.graphics.Color.parseColor("#EF4444"));
                        }
                    }
                });
            }).start();
        } else {
            statusTextView.setText("Status: 🔴 STOPPED");
            statusTextView.setTextColor(android.graphics.Color.parseColor("#EF4444"));
        }
    }

    private void refreshAndRegister(String serverUrl) {
        FirebaseMessaging.getInstance().deleteToken()
                .addOnCompleteListener(deleteTask -> {
                    FirebaseMessaging.getInstance().getToken()
                            .addOnCompleteListener(tokenTask -> {
                                if (!tokenTask.isSuccessful() || tokenTask.getResult() == null) {
                                    statusTextView.setText("Status: 🔴 FCM INIT FAILED");
                                    statusTextView.setTextColor(android.graphics.Color.parseColor("#EF4444"));
                                    Toast.makeText(this, "Failed to get FCM token", Toast.LENGTH_SHORT).show();
                                    return;
                                }

                                String freshToken = tokenTask.getResult();
                                Log.i(TAG, "Fresh FCM Token: " + freshToken);
                                prefs.edit().putString("fcm_token", freshToken).apply();
                                registerDeviceWithServer(this, serverUrl, freshToken);
                                connectWebSocket(this, serverUrl, freshToken);
                            });
                });
    }

    public static JSONObject buildDevicePayload(Context context, String fcmToken) {
        JSONObject payload = new JSONObject();
        try {
            SharedPreferences prefs = context.getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            String deviceId = "android_" + Build.ID;
            payload.put("deviceId", deviceId);
            payload.put("fcmToken", fcmToken != null ? fcmToken : "topic_subscriber");
            payload.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            payload.put("batteryLevel", getBatteryLevel(context));
            payload.put("networkType", getNetworkType(context));

            JSONArray simArray = getActiveSimCards(context);
            payload.put("simCards", simArray);

            int selectedSlot = prefs.getInt("selected_sim_slot", 0);
            payload.put("selectedSimSlot", selectedSlot);

            String customPhone = prefs.getString("sim_phone_number", "").trim();
            if (!customPhone.isEmpty() && customPhone.matches(".*\\d.*")) {
                payload.put("phoneNumber", customPhone);
                if (simArray.length() > 0) {
                    payload.put("operator", simArray.getJSONObject(0).optString("carrier", "Cellular Carrier"));
                } else {
                    payload.put("operator", "Cellular Carrier");
                }
            } else if (simArray.length() > 0) {
                JSONObject chosenSim = null;
                for (int i = 0; i < simArray.length(); i++) {
                    JSONObject s = simArray.getJSONObject(i);
                    if (s.optInt("slot", 0) == selectedSlot) {
                        chosenSim = s;
                        break;
                    }
                }
                if (chosenSim == null) chosenSim = simArray.getJSONObject(0);

                String num = chosenSim.optString("number", "");
                if (num.matches(".*\\d.*")) {
                    payload.put("phoneNumber", num);
                } else {
                    payload.put("phoneNumber", chosenSim.optString("carrier", "Active SIM"));
                }
                payload.put("operator", chosenSim.optString("carrier", "SIM Carrier"));
            } else {
                payload.put("phoneNumber", "Active SIM Device");
                payload.put("operator", "Cellular Carrier");
            }
        } catch (Exception ignored) {}
        return payload;
    }

    public static synchronized void connectWebSocket(Context context, String serverUrl, String fcmToken) {
        disconnectWebSocket();
        if (serverUrl == null || serverUrl.isEmpty()) return;

        try {
            if (okHttpClient == null) {
                okHttpClient = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(0, TimeUnit.MILLISECONDS)
                        .pingInterval(15, TimeUnit.SECONDS)
                        .build();
            }

            String wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws";
            Request request = new Request.Builder().url(wsUrl).build();

            currentWebSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    Log.i(TAG, "🌐 WebSocket Connected: " + wsUrl);
                    try {
                        JSONObject regPayload = buildDevicePayload(context, fcmToken);
                        regPayload.put("type", "REGISTER");
                        webSocket.send(regPayload.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "WebSocket REGISTER error: " + e.getMessage());
                    }
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    Log.i(TAG, "📩 WebSocket Message: " + text);
                    try {
                        JSONObject msg = new JSONObject(text);
                        if (msg.optBoolean("requestRegister", false)) {
                            JSONObject regPayload = buildDevicePayload(context, fcmToken);
                            regPayload.put("type", "REGISTER");
                            webSocket.send(regPayload.toString());
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    Log.w(TAG, "⚠️ WebSocket Failure: " + t.getMessage());
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    Log.i(TAG, "WebSocket Closed: " + reason);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "connectWebSocket error: " + e.getMessage());
        }
    }

    public static synchronized void disconnectWebSocket() {
        if (currentWebSocket != null) {
            try {
                currentWebSocket.close(1000, "Stopped");
            } catch (Exception ignored) {}
            currentWebSocket = null;
        }
    }

    public static void registerDeviceWithServer(Context context, String serverUrl, String fcmToken) {
        if (serverUrl == null || serverUrl.isEmpty() || fcmToken == null) return;

        new Thread(() -> {
            try {
                JSONObject payload = buildDevicePayload(context, fcmToken);
                int resp = HttpHelper.postJson(serverUrl + "/v1/devices/register", payload.toString());
                if (resp != 200) {
                    for (String candidate : DEFAULT_URLS) {
                        if (!candidate.equals(serverUrl)) {
                            int altResp = HttpHelper.postJson(candidate + "/v1/devices/register", payload.toString());
                            if (altResp == 200) {
                                SharedPreferences prefs = context.getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
                                prefs.edit().putString("server_url", candidate).apply();
                                connectWebSocket(context, candidate, fcmToken);
                                break;
                            }
                        }
                    }
                } else {
                    connectWebSocket(context, serverUrl, fcmToken);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void unregisterDeviceFromServer(String serverUrl) {
        if (serverUrl == null || serverUrl.isEmpty()) return;

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("deviceId", "android_" + Build.ID);
                HttpHelper.postJson(serverUrl + "/v1/devices/unregister", payload.toString());
            } catch (Exception ignored) {}
        }).start();
    }

    public static int getBatteryLevel(Context context) {
        try {
            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return (int) ((level / (float) scale) * 100);
                }
            }
        } catch (Exception ignored) {}
        return 100;
    }

    public static String getNetworkType(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Cellular";
                    }
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    public static JSONArray getActiveSimCards(Context context) {
        JSONArray arr = new JSONArray();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

                boolean hasPhoneState = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
                boolean hasPhoneNumbers = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    hasPhoneNumbers = context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED;
                }

                if (sm != null && (hasPhoneState || hasPhoneNumbers)) {
                    List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
                    if (list != null) {
                        for (SubscriptionInfo sub : list) {
                            JSONObject simObj = new JSONObject();
                            int slot = sub.getSimSlotIndex();
                            int subId = sub.getSubscriptionId();
                            simObj.put("slot", slot);
                            simObj.put("subId", subId);
                            simObj.put("carrier", sub.getCarrierName() != null ? sub.getCarrierName().toString() : "SIM " + (slot + 1));

                            String num = null;

                            // 1. SubscriptionManager (Android 13+ / API 33+)
                            if (Build.VERSION.SDK_INT >= 33) {
                                try {
                                    num = sm.getPhoneNumber(subId);
                                } catch (Exception ignored) {}
                            }

                            // 2. SubscriptionInfo (API 22+)
                            if (num == null || num.isEmpty()) {
                                try {
                                    num = sub.getNumber();
                                } catch (Exception ignored) {}
                            }

                            // 3. TelephonyManager for SubId (Android 7+ / API 24+)
                            if ((num == null || num.isEmpty()) && tm != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    try {
                                        TelephonyManager slotTm = tm.createForSubscriptionId(subId);
                                        num = slotTm.getLine1Number();
                                    } catch (Exception ignored) {}
                                }
                            }

                            // 4. Fallback Default TelephonyManager
                            if ((num == null || num.isEmpty()) && tm != null) {
                                try {
                                    num = tm.getLine1Number();
                                } catch (Exception ignored) {}
                            }

                            if (num != null && !num.isEmpty() && num.matches(".*\\d.*")) {
                                simObj.put("number", num.trim());
                            }
                            arr.put(simObj);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private void detectSimInfo() {
        try {
            JSONArray sims = getActiveSimCards(this);
            detectedSims.clear();
            List<String> spinnerLabels = new ArrayList<>();

            for (int i = 0; i < sims.length(); i++) {
                JSONObject s = sims.getJSONObject(i);
                int slot = s.optInt("slot", i);
                int subId = s.optInt("subId", -1);
                String carrier = s.optString("carrier", "SIM " + (slot + 1));
                String num = s.optString("number", "");

                SimCardItem item = new SimCardItem(slot, subId, carrier, num);
                detectedSims.add(item);
                spinnerLabels.add(item.toString());
            }

            if (detectedSims.isEmpty()) {
                spinnerLabels.add("SIM 1 (Active Gateway)");
                detectedSims.add(new SimCardItem(0, -1, "Active SIM", ""));
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, spinnerLabels);
            simSelectorSpinner.setAdapter(adapter);

            int savedSlot = prefs.getInt("selected_sim_slot", 0);
            int selectIndex = 0;
            for (int i = 0; i < detectedSims.size(); i++) {
                if (detectedSims.get(i).slot == savedSlot) {
                    selectIndex = i;
                    break;
                }
            }
            simSelectorSpinner.setSelection(selectIndex);

            simSelectorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position < detectedSims.size()) {
                        SimCardItem item = detectedSims.get(position);
                        prefs.edit().putInt("selected_sim_slot", item.slot).apply();
                        if (item.number != null && !item.number.isEmpty() && item.number.matches(".*\\d.*")) {
                            simNumberInput.setText(item.number);
                            prefs.edit().putString("sim_phone_number", item.number).apply();
                        } else {
                            String saved = prefs.getString("sim_phone_number", "");
                            if (!saved.isEmpty() && saved.matches(".*\\d.*")) {
                                simNumberInput.setText(saved);
                            } else {
                                simNumberInput.setText("");
                                simNumberInput.setHint("SIM " + (item.slot + 1) + ": " + item.carrier + " (Tap Edit to set)");
                            }
                        }
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

        } catch (Exception ignored) {}
    }

    private void requestBatteryExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> permList = new java.util.ArrayList<>();
            permList.add(Manifest.permission.SEND_SMS);
            permList.add(Manifest.permission.READ_PHONE_STATE);
            permList.add(Manifest.permission.READ_PHONE_NUMBERS);
            permList.add(Manifest.permission.READ_SMS);
            permList.add(Manifest.permission.INTERNET);

            if (Build.VERSION.SDK_INT >= 33) {
                permList.add(Manifest.permission.POST_NOTIFICATIONS);
            }

            java.util.List<String> reqList = new java.util.ArrayList<>();
            for (String p : permList) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    reqList.add(p);
                }
            }

            if (!reqList.isEmpty()) {
                requestPermissions(reqList.toArray(new String[0]), PERMISSION_REQ_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            detectSimInfo();
        }
    }
}
