package com.freesmsgateway.poc;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 101;

    private EditText serverUrlInput;
    private EditText simNumberInput;
    private Button registerGatewayBtn;
    private TextView statusTextView;
    private TextView fcmTokenTextView;

    private String currentFcmToken = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 50, 40, 50);

        TextView title = new TextView(this);
        title.setText("📱 Free SIM SMS Gateway (FCM Direct)");
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 15);

        TextView description = new TextView(this);
        description.setText("🚀 Direct FCM High-Priority Push Enabled.\n• No background 2s polling loops\n• Works even when app is closed / phone sleeping\n• ₹0.00 SIM Cellular Transmit");
        description.setTextSize(13);
        description.setPadding(0, 0, 0, 25);

        TextView urlLabel = new TextView(this);
        urlLabel.setText("Central Server API URL:");
        urlLabel.setTypeface(null, android.graphics.Typeface.BOLD);

        serverUrlInput = new EditText(this);
        serverUrlInput.setHint("http://192.168.1.100:5102");

        TextView simLabel = new TextView(this);
        simLabel.setText("Device SIM Phone Number:");
        simLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        simLabel.setPadding(0, 15, 0, 0);

        simNumberInput = new EditText(this);
        simNumberInput.setHint("+919876543210");

        SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
        String savedUrl = prefs.getString("server_url", "http://192.168.1.100:5102");
        String savedPhone = prefs.getString("sim_phone_number", "");
        currentFcmToken = prefs.getString("fcm_token", null);

        serverUrlInput.setText(savedUrl);
        simNumberInput.setText(savedPhone);

        registerGatewayBtn = new Button(this);
        registerGatewayBtn.setText("⚡ CONNECT & REGISTER FCM GATEWAY");

        statusTextView = new TextView(this);
        statusTextView.setText("Status: Initializing FCM Push Token...");
        statusTextView.setPadding(0, 20, 0, 10);
        statusTextView.setTextSize(14);

        fcmTokenTextView = new TextView(this);
        fcmTokenTextView.setText(currentFcmToken != null ? "🔑 FCM Token: Ready" : "🔑 FCM Token: Fetching from Google...");
        fcmTokenTextView.setTextSize(12);
        fcmTokenTextView.setPadding(0, 0, 0, 20);

        layout.addView(title);
        layout.addView(description);
        layout.addView(urlLabel);
        layout.addView(serverUrlInput);
        layout.addView(simLabel);
        layout.addView(simNumberInput);
        layout.addView(registerGatewayBtn);
        layout.addView(statusTextView);
        layout.addView(fcmTokenTextView);

        scrollView.addView(layout);
        setContentView(scrollView);

        requestPermissionsIfNeeded();
        fetchFirebaseToken();

        registerGatewayBtn.setOnClickListener(v -> {
            String url = serverUrlInput.getText().toString().trim();
            String phone = simNumberInput.getText().toString().trim();

            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter Central Server URL", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit()
                    .putString("server_url", url)
                    .putString("sim_phone_number", phone)
                    .apply();

            registerDeviceWithServer(url, phone, currentFcmToken);
        });
    }

    private void fetchFirebaseToken() {
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                            fcmTokenTextView.setText("⚠️ FCM Token Fetch Failed (Check internet / Google Play Services)");
                            return;
                        }

                        currentFcmToken = task.getResult();
                        Log.d(TAG, "FCM Token: " + currentFcmToken);

                        SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
                        prefs.edit().putString("fcm_token", currentFcmToken).apply();

                        fcmTokenTextView.setText("✅ FCM Push Ready: " + currentFcmToken.substring(0, Math.min(20, currentFcmToken.length())) + "...");
                        statusTextView.setText("🟢 FCM GATEWAY READY (Ready to receive instant SMS dispatches)");

                        String savedUrl = prefs.getString("server_url", "");
                        String savedPhone = prefs.getString("sim_phone_number", "");
                        if (!savedUrl.isEmpty()) {
                            registerDeviceWithServer(savedUrl, savedPhone, currentFcmToken);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization error: " + e.getMessage());
            fcmTokenTextView.setText("ℹ️ Firebase Services initializing...");
        }
    }

    private void registerDeviceWithServer(String serverUrl, String phoneNumber, String fcmToken) {
        statusTextView.setText("⏳ Registering Gateway with Server...");

        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + "/v1/devices/register");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Bypass-Tunnel-Reminder", "true");
                conn.setRequestProperty("User-Agent", "FreeSIMSMSGatewayApp/1.0");
                conn.setConnectTimeout(6000);
                conn.setDoOutput(true);

                String carrierName = getSimCarrierName();
                String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
                int battery = getBatteryPercentage();

                JSONObject req = new JSONObject();
                req.put("phoneNumber", phoneNumber != null && !phoneNumber.isEmpty() ? phoneNumber : "SIM Gateway Mobile");
                req.put("fcmToken", fcmToken != null ? fcmToken : "");
                req.put("model", deviceModel);
                req.put("operator", carrierName);
                req.put("batteryLevel", battery);

                OutputStream os = conn.getOutputStream();
                os.write(req.toString().getBytes());
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String response = reader.readLine();
                    reader.close();

                    runOnUiThread(() -> {
                        statusTextView.setText("🟢 ACTIVE & CONNECTED VIA FCM!\nServer received push token.\nNo polling required.");
                        Toast.makeText(MainActivity.this, "🟢 Gateway Registered Successfully!", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(() -> {
                        statusTextView.setText("🔴 Server returned HTTP " + responseCode);
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Registration error: " + e.getMessage());
                runOnUiThread(() -> {
                    statusTextView.setText("🔴 Connection Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> permList = new java.util.ArrayList<>();
            permList.add(Manifest.permission.SEND_SMS);
            permList.add(Manifest.permission.READ_PHONE_STATE);
            permList.add(Manifest.permission.RECEIVE_SMS);
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

    private String getSimCarrierName() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            String name = tm.getNetworkOperatorName();
            return (name != null && !name.isEmpty()) ? name : "SIM Carrier";
        } catch (Exception e) {
            return "SIM Carrier";
        }
    }

    private int getBatteryPercentage() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return 98;
        }
    }
}
