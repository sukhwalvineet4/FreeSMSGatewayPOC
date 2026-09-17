package com.freesmsgateway.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class SmsGatewayService extends Service {

    private static final String CHANNEL_ID = "SIM_GATEWAY_SERVICE";
    private boolean isRunning = false;
    private String serverUrl = "http://192.168.1.100:3000";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("SERVER_URL")) {
            serverUrl = intent.getStringExtra("SERVER_URL");
        }

        try {
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Notification notification = builder
                    .setContentTitle("🟢 Free SIM SMS Gateway")
                    .setContentText("Background SMS Gateway Active - Dispatched via SIM Card")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        } catch (Exception e) {
            Log.e("SmsGatewayService", "startForeground error: " + e.getMessage());
        }

        if (!isRunning) {
            isRunning = true;
            new Thread(this::pollAndDispatchLoop).start();
        }

        return START_STICKY;
    }

    private void pollAndDispatchLoop() {
        while (isRunning) {
            try {
                fetchPendingAndSendSms();
                Thread.sleep(3000);
            } catch (Exception e) {
                Log.e("SmsGatewayService", "Polling error: " + e.getMessage());
            }
        }
    }

    private void fetchPendingAndSendSms() {
        try {
            URL url = new URL(serverUrl + "/v1/messages/pending");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject resObj = new JSONObject(sb.toString());
                JSONArray dataArr = resObj.getJSONArray("data");

                for (int i = 0; i < dataArr.length(); i++) {
                    JSONObject item = dataArr.getJSONObject(i);
                    String msgId = item.getString("id");
                    String toPhone = item.getString("to");
                    String content = item.getString("content");

                    boolean success = sendSilentSms(toPhone, content);
                    reportStatus(msgId, success ? "DELIVERED" : "FAILED");
                }
            }
        } catch (Exception e) {
            Log.e("SmsGatewayService", "Fetch pending error: " + e.getMessage());
        }
    }

    private boolean sendSilentSms(String phone, String text) {
        if (phone == null || text == null) return false;
        String cleanPhone = phone.replaceAll("[^\\d+]", "");
        if (cleanPhone.isEmpty()) return false;

        try {
            SmsManager smsManager = null;

            // 1. Android 12+ System Service (Recommended for Android 12, 13, 14, 15)
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
                smsManager.sendTextMessage(cleanPhone, null, text, null, null);
                Log.i("SmsGatewayService", "✅ SILENT SIM SMS TRANSMITTED to " + cleanPhone);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e("SmsGatewayService", "❌ SMS Send failed: " + e.getMessage());
            return false;
        }
    }

    private void reportStatus(String msgId, String status) {
        try {
            URL url = new URL(serverUrl + "/v1/messages/" + msgId + "/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(("{\"status\":\"" + status + "\"}").getBytes());
            conn.getResponseCode();
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SIM SMS Gateway Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }
}
