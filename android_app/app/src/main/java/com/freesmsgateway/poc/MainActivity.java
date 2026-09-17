package com.freesmsgateway.poc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQ_CODE = 101;
    private EditText serverUrlInput;
    private Button startGatewayBtn;
    private TextView statusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 60, 50, 60);

        TextView title = new TextView(this);
        title.setText("📱 Free SIM SMS Gateway");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 30);

        TextView subtitle = new TextView(this);
        subtitle.setText("Enter Node.js Server URL (e.g. http://192.168.1.100:3000):");
        subtitle.setPadding(0, 0, 0, 15);

        serverUrlInput = new EditText(this);
        serverUrlInput.setHint("http://192.168.1.100:3000");

        SharedPreferences prefs = getSharedPreferences("GatewayPrefs", MODE_PRIVATE);
        String savedUrl = prefs.getString("server_url", "http://192.168.1.100:3000");
        serverUrlInput.setText(savedUrl);

        startGatewayBtn = new Button(this);
        startGatewayBtn.setText("🚀 START BACKGROUND SILENT GATEWAY");

        statusTextView = new TextView(this);
        statusTextView.setText("Status: Gateway Stopped");
        statusTextView.setPadding(0, 30, 0, 0);

        layout.addView(title);
        layout.addView(subtitle);
        layout.addView(serverUrlInput);
        layout.addView(startGatewayBtn);
        layout.addView(statusTextView);

        setContentView(layout);

        requestPermissionsIfNeeded();

        startGatewayBtn.setOnClickListener(v -> {
            String url = serverUrlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter server URL", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit().putString("server_url", url).apply();

            try {
                Intent serviceIntent = new Intent(this, SmsGatewayService.class);
                serviceIntent.putExtra("SERVER_URL", url);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                statusTextView.setText("🟢 STATUS: SIM GATEWAY RUNNING SILENTLY IN BACKGROUND!");
                Toast.makeText(this, "🟢 Background SIM Gateway Started!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                statusTextView.setText("❌ Failed to start service: " + e.getMessage());
                Toast.makeText(this, "Error starting gateway: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> permList = new java.util.ArrayList<>();
            permList.add(Manifest.permission.SEND_SMS);
            permList.add(Manifest.permission.READ_PHONE_STATE);
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
}
