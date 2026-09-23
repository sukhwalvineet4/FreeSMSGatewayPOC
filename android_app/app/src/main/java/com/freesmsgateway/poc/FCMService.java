package com.freesmsgateway.poc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enterprise-grade Firebase Cloud Messaging Service.
 * Features:
 * - Dual-SIM slot selection
 * - Carrier sent & delivery receipts via PendingIntent
 * - Multi-part message splitting
 * - Real-time carrier error diagnosis
 */
public class FCMService extends FirebaseMessagingService {

    public static final String[] DEFAULT_URLS = new String[] {
        "https://api.aranyasetu.org",
        "http://192.168.29.54:5102",
        "http://115.124.117.226:5102"
    };

    private static final String TAG = "FCMService";
    private static final java.util.Set<String> processedJobs = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "🔥 New FCM Token: " + token);

        SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();

        if (prefs.getBoolean("is_active", false)) {
            String serverUrl = prefs.getString("server_url", DEFAULT_URLS[0]);
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                serverUrl = DEFAULT_URLS[0];
            }
            MainActivity.registerDeviceWithServer(this, serverUrl.trim(), token);
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.i(TAG, "📩 FCM Message Received from: " + remoteMessage.getFrom());

        SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
        boolean isActive = prefs.getBoolean("is_active", false);

        Map<String, String> data = remoteMessage.getData();
        if (data.isEmpty()) {
            return;
        }

        String jobId = data.get("jobId");
        if (jobId == null) jobId = data.get("msgId");

        if (jobId != null) {
            if (processedJobs.contains(jobId)) {
                Log.w(TAG, "Duplicate FCM Job ignored: " + jobId);
                return;
            }
            processedJobs.add(jobId);
            if (processedJobs.size() > 500) {
                processedJobs.clear();
            }
        }

        String serverUrl = data.get("serverUrl");
        if (serverUrl != null && !serverUrl.trim().isEmpty()) {
            serverUrl = serverUrl.trim();
            prefs.edit().putString("server_url", serverUrl).apply();
        } else {
            serverUrl = prefs.getString("server_url", DEFAULT_URLS[0]);
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                serverUrl = DEFAULT_URLS[0];
            }
        }

        if (!isActive) {
            Log.w(TAG, "Gateway STOPPED on device. Rejecting job: " + jobId);
            if (jobId != null) {
                reportJobResult(serverUrl, jobId, "FAILED", "Gateway stopped on device");
            }
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeSMSGateway:WakeLock");
            wakeLock.acquire(25000);
        }

        try {
            String to = data.get("to");
            if (to == null) to = data.get("phoneNumber");
            if (to == null) to = data.get("phone");

            String content = data.get("content");
            if (content == null) content = data.get("message");
            if (content == null) content = data.get("text");

            int simSlot = -1;
            if (data.containsKey("simSlot")) {
                try {
                    simSlot = Integer.parseInt(data.get("simSlot"));
                } catch (Exception ignored) {}
            }
            if (simSlot < 0) {
                simSlot = prefs.getInt("selected_sim_slot", 0);
            }

            if (to == null || content == null) {
                if (jobId != null) {
                    reportJobResult(serverUrl, jobId, "FAILED", "Missing phone or message body");
                }
                return;
            }

            // Keep server updated with device and SIM info
            String token = prefs.getString("fcm_token", null);
            MainActivity.registerDeviceWithServer(getApplicationContext(), serverUrl, token != null ? token : "topic_subscriber");

            Log.i(TAG, "🚀 Sending cellular SMS for Job [" + jobId + "] to " + to + " (SIM Slot: " + simSlot + ")");
            sendSimSmsWithTracking(serverUrl, jobId, to, content, simSlot);

        } catch (Exception e) {
            Log.e(TAG, "Error handling FCM message: " + e.getMessage(), e);
            if (jobId != null) {
                reportJobResult(serverUrl, jobId, "FAILED", e.getMessage());
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    /**
     * Dispatches SMS with carrier result tracking via PendingIntent on ApplicationContext.
     */
    private void sendSimSmsWithTracking(String serverUrl, String jobId, String phone, String text, int preferredSimSlot) {
        String cleanPhone = phone.replaceAll("[^\\d+]", "");
        if (cleanPhone.isEmpty()) {
            reportJobResult(serverUrl, jobId, "FAILED", "Invalid phone number format");
            return;
        }

        final Context appContext = getApplicationContext();
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = (pm != null)
                ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeSMSGateway:SmsSentLock_" + jobId)
                : null;
        if (wakeLock != null) {
            wakeLock.acquire(30000);
        }

        try {
            SmsManager smsManager = getSmsManagerForSlot(preferredSimSlot);
            if (smsManager == null) {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                reportJobResult(serverUrl, jobId, "FAILED", "No active SIM card available on device");
                return;
            }

            String sentAction = "com.freesmsgateway.poc.SMS_SENT_" + jobId;
            Intent sentIntent = new Intent(sentAction);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(appContext, 0, sentIntent, flags);

            // Register dynamic receiver ON APPLICATION CONTEXT (never leaks with Service)
            final BroadcastReceiver sentReceiver = new BroadcastReceiver() {
                private boolean unregistered = false;

                @Override
                public void onReceive(Context context, Intent intent) {
                    synchronized (this) {
                        if (!unregistered) {
                            unregistered = true;
                            try {
                                appContext.unregisterReceiver(this);
                            } catch (Exception ignored) {}
                        }
                    }

                    int resultCode = getResultCode();
                    String status;
                    String error = null;

                    switch (resultCode) {
                        case Activity.RESULT_OK:
                            status = "SENT";
                            break;
                        case SmsManager.RESULT_ERROR_NO_SERVICE:
                            status = "FAILED";
                            error = "No Cellular Service (Tower unreachable)";
                            break;
                        case SmsManager.RESULT_ERROR_RADIO_OFF:
                            status = "FAILED";
                            error = "Cellular Radio Off / Airplane Mode";
                            break;
                        case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
                            status = "FAILED";
                            error = "Carrier Daily SMS Limit Exceeded";
                            break;
                        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                        default:
                            status = "FAILED";
                            error = "Carrier Generic Failure (Insufficient Balance/SIM Locked)";
                            break;
                    }

                    Log.i(TAG, "📡 Carrier Result for Job [" + jobId + "]: " + status + (error != null ? " (" + error + ")" : ""));
                    String senderSim = getSenderNumberForSlot(preferredSimSlot);
                    String carrier = getCarrierForSlot(preferredSimSlot);
                    reportJobResult(serverUrl, jobId, status, error, senderSim, carrier);

                    if (wakeLock != null && wakeLock.isHeld()) {
                        try { wakeLock.release(); } catch (Exception ignored) {}
                    }
                }
            };

            IntentFilter filter = new IntentFilter(sentAction);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(sentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(sentReceiver, filter);
            }

            // Safety watchdog timeout: unregister receiver after 30 seconds if cell tower doesn't reply
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    appContext.unregisterReceiver(sentReceiver);
                } catch (Exception ignored) {}
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Exception ignored) {}
                }
            }, 30000);

            ArrayList<String> parts = smsManager.divideMessage(text);
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                sentIntents.add(pi);
                for (int i = 1; i < parts.size(); i++) sentIntents.add(null);
                smsManager.sendMultipartTextMessage(cleanPhone, null, parts, sentIntents, null);
            } else {
                smsManager.sendTextMessage(cleanPhone, null, text, pi, null);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ sendSimSmsWithTracking Exception: " + e.getMessage(), e);
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception ignored) {}
            }
            reportJobResult(serverUrl, jobId, "FAILED", e.getMessage(), null);
        }
    }

    private String getSenderNumberForSlot(int targetSlot) {
        if (targetSlot < 0) {
            SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            targetSlot = prefs.getInt("selected_sim_slot", 0);
        }

        try {
            SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            String slotPhone = prefs.getString("sim_phone_number_slot_" + targetSlot, "").trim();
            if (!slotPhone.isEmpty() && slotPhone.matches(".*\\d.*")) {
                return slotPhone;
            }
            if (targetSlot == prefs.getInt("selected_sim_slot", -1)) {
                String activePhone = prefs.getString("sim_phone_number", "").trim();
                if (!activePhone.isEmpty() && activePhone.matches(".*\\d.*")) {
                    return activePhone;
                }
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null && checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    SubscriptionInfo sub = sm.getActiveSubscriptionInfoForSimSlotIndex(targetSlot);
                    if (sub != null) {
                        String num = null;
                        if (Build.VERSION.SDK_INT >= 33) {
                            try {
                                num = sm.getPhoneNumber(sub.getSubscriptionId());
                            } catch (Exception ignored) {}
                        }
                        if (num == null || num.isEmpty()) {
                            try {
                                num = sub.getNumber();
                            } catch (Exception ignored) {}
                        }
                        if (num != null && !num.isEmpty() && num.matches(".*\\d.*")) return num.trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return "SIM " + (targetSlot + 1);
    }

    /**
     * Obtains the SmsManager instance for the specified SIM slot.
     */
    private SmsManager getSmsManagerForSlot(int targetSlot) {
        Log.i(TAG, "🔍 Resolving SmsManager for SIM Slot: " + targetSlot);
        if (targetSlot < 0) {
            SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            targetSlot = prefs.getInt("selected_sim_slot", 0);
        }

        int resolvedSubId = -1;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    try {
                        SubscriptionInfo directInfo = sm.getActiveSubscriptionInfoForSimSlotIndex(targetSlot);
                        if (directInfo != null) {
                            resolvedSubId = directInfo.getSubscriptionId();
                            Log.i(TAG, "Found SubscriptionInfo for slot " + targetSlot + ": SubId=" + resolvedSubId + ", Carrier=" + directInfo.getCarrierName());
                        }
                    } catch (Exception ignored) {}

                    if (resolvedSubId < 0) {
                        List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                        if (subs != null) {
                            for (SubscriptionInfo sub : subs) {
                                if (sub.getSimSlotIndex() == targetSlot) {
                                    resolvedSubId = sub.getSubscriptionId();
                                    Log.i(TAG, "Matched active sub in list for slot " + targetSlot + ": SubId=" + resolvedSubId + ", Carrier=" + sub.getCarrierName());
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error querying SubscriptionManager for slot " + targetSlot + ": " + e.getMessage());
            }
        }

        if (resolvedSubId < 0) {
            SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            if (targetSlot == prefs.getInt("selected_sim_slot", -1)) {
                resolvedSubId = prefs.getInt("selected_sub_id", -1);
                if (resolvedSubId >= 0) {
                    Log.i(TAG, "Using saved selected_sub_id: " + resolvedSubId);
                }
            }
        }

        if (resolvedSubId >= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    SmsManager base = getSystemService(SmsManager.class);
                    if (base != null) {
                        Log.i(TAG, "🚀 Successfully created SmsManager (API 31+) for SubId " + resolvedSubId + " (SIM Slot " + targetSlot + ")");
                        return base.createForSubscriptionId(resolvedSubId);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "createForSubscriptionId error: " + e.getMessage());
                }
            }
            try {
                Log.i(TAG, "🚀 Successfully created SmsManager for SubId " + resolvedSubId + " (SIM Slot " + targetSlot + ")");
                return SmsManager.getSmsManagerForSubscriptionId(resolvedSubId);
            } catch (Exception e) {
                Log.w(TAG, "getSmsManagerForSubscriptionId error: " + e.getMessage());
            }
        }

        Log.w(TAG, "⚠️ Could not resolve SubId for SIM slot " + targetSlot + "! Falling back to default SmsManager.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SmsManager sm = getSystemService(SmsManager.class);
                if (sm != null) return sm;
            } catch (Exception ignored) {}
        }
        return SmsManager.getDefault();
    }

    private String getCarrierForSlot(int targetSlot) {
        if (targetSlot < 0) {
            SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            targetSlot = prefs.getInt("selected_sim_slot", 0);
        }

        try {
            SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            String savedCarrier = prefs.getString("sim_carrier_slot_" + targetSlot, "");
            if (!savedCarrier.isEmpty() && !savedCarrier.contains("SIM ")) {
                return savedCarrier;
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null && checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    SubscriptionInfo sub = sm.getActiveSubscriptionInfoForSimSlotIndex(targetSlot);
                    if (sub != null && sub.getCarrierName() != null && !sub.getCarrierName().toString().isEmpty()) {
                        return sub.getCarrierName().toString().trim();
                    }
                    List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (SubscriptionInfo s : subs) {
                            if (s.getSimSlotIndex() == targetSlot) {
                                if (s.getCarrierName() != null && !s.getCarrierName().toString().isEmpty()) {
                                    return s.getCarrierName().toString().trim();
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String simOp = tm.getSimOperatorName();
                if (simOp != null && !simOp.trim().isEmpty()) return simOp.trim();
                String netOp = tm.getNetworkOperatorName();
                if (netOp != null && !netOp.trim().isEmpty()) return netOp.trim();
            }
        } catch (Exception ignored) {}
        return "SIM " + (targetSlot + 1);
    }

    private void reportJobResult(String serverUrl, String jobId, String status, String error) {
        reportJobResult(serverUrl, jobId, status, error, null, null);
    }

    private void reportJobResult(String serverUrl, String jobId, String status, String error, String senderNumber) {
        reportJobResult(serverUrl, jobId, status, error, senderNumber, null);
    }

    private void reportJobResult(String serverUrl, String jobId, String status, String error, String senderNumber, String carrier) {
        if (jobId == null) return;
        if (serverUrl == null || serverUrl.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
            serverUrl = prefs.getString("server_url", DEFAULT_URLS[0]);
            if (serverUrl == null || serverUrl.isEmpty()) {
                serverUrl = DEFAULT_URLS[0];
            }
        }

        final String targetUrl = serverUrl;
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("jobId", jobId);
                payload.put("msgId", jobId);
                payload.put("status", status);
                if (error != null) payload.put("error", error);
                if (senderNumber != null) {
                    payload.put("senderMobileNumber", senderNumber);
                }
                if (carrier != null) {
                    payload.put("sentViaSim", carrier);
                    payload.put("carrier", carrier);
                }

                int resp = HttpHelper.postJson(targetUrl + "/v1/jobs/" + jobId + "/result", payload.toString());
                if (resp != 200) {
                    for (String candidate : DEFAULT_URLS) {
                        if (!candidate.equals(targetUrl)) {
                            int altResp = HttpHelper.postJson(candidate + "/v1/jobs/" + jobId + "/result", payload.toString());
                            if (altResp == 200) {
                                getSharedPreferences("GatewayPrefs", MODE_PRIVATE).edit().putString("server_url", candidate).apply();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }
}
