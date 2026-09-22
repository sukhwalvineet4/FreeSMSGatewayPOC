package com.freesmsgateway.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;

/**
 * Firebase Cloud Messaging Service for Direct Real-Time SMS Dispatch
 * Wakes up the mobile device even when the app is killed or device is sleeping.
 */
public class SmsFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FcmSmsGateway";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.i(TAG, "📩 FCM Message Received from: " + remoteMessage.getFrom());

        // Acquire a temporary WakeLock to ensure SMS sending finishes even if CPU is asleep
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeSMSGateway:FcmWakeLock");
            wakeLock.acquire(15000); // 15 seconds max
        }

        try {
            Map<String, String> data = remoteMessage.getData();
            if (data != null && !data.isEmpty()) {
                String type = data.get("type");
                String msgId = data.get("msgId");
                String to = data.get("to");
                String content = data.get("content");
                String serverUrl = data.get("serverUrl");

                if (serverUrl == null || serverUrl.isEmpty()) {
                    SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
                    serverUrl = prefs.getString("server_url", "http://192.168.1.100:3000");
                }

                Log.i(TAG, "📲 Received Direct SMS Task -> msgId: " + msgId + " | to: " + to + " | content: " + content);

                if (to != null && content != null) {
                    boolean sent = sendNativeSms(to, content);
                    if (msgId != null && serverUrl != null) {
                        reportStatusToServer(serverUrl, msgId, sent ? "DELIVERED" : "FAILED", null);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error processing FCM message: " + e.getMessage(), e);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.i(TAG, "🔑 New FCM Token Generated: " + token);

        SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();

        String serverUrl = prefs.getString("server_url", "");
        String phoneNumber = prefs.getString("sim_phone_number", "");

        if (!serverUrl.isEmpty()) {
            registerFcmTokenWithServer(serverUrl, token, phoneNumber);
        }
    }

    private boolean sendNativeSms(String phone, String text) {
        if (phone == null || text == null) return false;
        String cleanPhone = phone.replaceAll("[^\\d+]", "");
        if (cleanPhone.isEmpty()) return false;

        try {
            SmsManager smsManager = null;

            // 1. Android 12+ (API 31+) system service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    smsManager = getSystemService(SmsManager.class);
                } catch (Exception ignored) {}
            }

            // 2. Dual SIM Subscription ID Manager
            if (smsManager == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    int subId = SmsManager.getDefaultSmsSubscriptionId();
                    if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                    }
                } catch (Exception ignored) {}
            }

            // 3. Fallback to default SmsManager
            if (smsManager == null) {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                ArrayList<String> parts = smsManager.divideMessage(text);
                if (parts.size() > 1) {
                    smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null);
                } else {
                    smsManager.sendTextMessage(cleanPhone, null, text, null, null);
                }
                Log.i(TAG, "✅ Native Cellular SMS Transmitted via SIM to: " + cleanPhone);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "❌ Native SMS Send Failed: " + e.getMessage(), e);
            return false;
        }
    }

    private void reportStatusToServer(String serverUrl, String msgId, String status, String error) {
        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + "/v1/devices/message-status");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Bypass-Tunnel-Reminder", "true");
                conn.setRequestProperty("User-Agent", "FreeSIMSMSGatewayApp/1.0");
                conn.setConnectTimeout(5000);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("msgId", msgId);
                body.put("status", status);
                if (error != null) {
                    body.put("error", error);
                }

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes());
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                Log.i(TAG, "⚡ Reported SMS Status to server [" + msgId + " = " + status + "] HTTP: " + code);
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to report status to server: " + e.getMessage());
            }
        }).start();
    }

    private void registerFcmTokenWithServer(String serverUrl, String token, String phoneNumber) {
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

                JSONObject body = new JSONObject();
                body.put("fcmToken", token);
                body.put("phoneNumber", phoneNumber != null ? phoneNumber : "SIM Mobile Phone");
                body.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("operator", "Cellular Carrier");

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes());
                os.flush();
                os.close();

                conn.getResponseCode();
                Log.i(TAG, "🟢 Successfully auto-registered FCM token with server.");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to auto-register FCM token: " + e.getMessage());
            }
        }).start();
    }
}
