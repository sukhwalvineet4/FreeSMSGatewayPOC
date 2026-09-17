package com.freesmsgateway.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

public class SmsBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && "com.freesmsgateway.poc.SEND_SMS".equals(intent.getAction())) {
            String to = intent.getStringExtra("to");
            String content = intent.getStringExtra("content");
            if (to != null && content != null) {
                sendSilentSms(context, to, content);
            }
        }
    }

    private void sendSilentSms(Context context, String phone, String text) {
        String cleanPhone = phone.replaceAll("[^\\d+]", "");
        if (cleanPhone.isEmpty()) return;

        try {
            SmsManager smsManager = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    smsManager = context.getSystemService(SmsManager.class);
                } catch (Exception ignored) {}
            }
            if (smsManager == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    int subId = SmsManager.getDefaultSmsSubscriptionId();
                    if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                    }
                } catch (Exception ignored) {}
            }
            if (smsManager == null) {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                smsManager.sendTextMessage(cleanPhone, null, text, null, null);
                Log.i("SmsBroadcastReceiver", "✅ SILENT BROADCAST SMS SENT TO " + cleanPhone);
            }
        } catch (Exception e) {
            Log.e("SmsBroadcastReceiver", "❌ Broadcast SMS failed: " + e.getMessage());
        }
    }
}
