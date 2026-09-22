package com.freesmsgateway.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Optional Lightweight Foreground Service
 * Keeps the SMS Gateway active in foreground if desired without polling.
 * All dispatches are pushed via FCM or WebSocket in real-time.
 */
public class SmsGatewayService extends Service {

    private static final String CHANNEL_ID = "SIM_GATEWAY_SERVICE";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Notification notification = builder
                    .setContentTitle("🟢 Free SIM SMS Gateway Active")
                    .setContentText("FCM Push & Direct Dispatch Active (Zero Polling)")
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

        return START_STICKY;
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
}
