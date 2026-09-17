package com.freesmsgateway.poc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Free SMS Gateway - Dynamic Android SIM Companion Application
 * 
 * Features:
 * - Bypasses localtunnel splash screens automatically.
 * - Clean ON / OFF Gateway Toggle Button.
 */
class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101

    private lateinit var statusTextView: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var simNumberInput: EditText
    private lateinit var oneClickConnectButton: Button
    private lateinit var simDetailsTextView: TextView

    private var isGatewayConnected = false
    private var registeredDeviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        simNumberInput = findViewById(R.id.simNumberInput)
        oneClickConnectButton = findViewById(R.id.oneClickConnectButton)
        simDetailsTextView = findViewById(R.id.simDetailsTextView)

        val detectedNumber = getSimPhoneNumber()
        if (detectedNumber.isNotEmpty()) {
            simNumberInput.setText(detectedNumber)
        }

        // 1-Click Toggle Connection ON / OFF Handler
        oneClickConnectButton.setOnClickListener {
            if (!isGatewayConnected) {
                requestPermissionsAndConnectGateway()
            } else {
                disconnectGateway()
            }
        }
    }

    private fun requestPermissionsAndConnectGateway() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            connectSimGatewayToCentralServer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Permissions Granted! Connecting SIM Gateway...", Toast.LENGTH_SHORT).show()
                connectSimGatewayToCentralServer()
            } else {
                Toast.makeText(this, "⚠️ SMS Permissions are required to operate as an SMS Gateway", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * TURN GATEWAY ON -> Register to Central Server
     */
    private fun connectSimGatewayToCentralServer() {
        val serverUrl = serverUrlInput.text.toString().trim()
        val userSimNumber = simNumberInput.text.toString().trim()

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Please enter a valid Central Server URL", Toast.LENGTH_SHORT).show()
            return
        }

        if (userSimNumber.isEmpty()) {
            Toast.makeText(this, "Please enter your SIM Mobile Number", Toast.LENGTH_SHORT).show()
            return
        }

        val carrierName = getSimCarrierName()
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val batteryPct = getBatteryPercentage()

        simDetailsTextView.text = "📱 SIM: $userSimNumber ($carrierName) | Device: $deviceModel | Battery: $batteryPct%"
        statusTextView.text = "🟢 CONNECTING TO SERVER..."

        thread {
            try {
                val registerUrl = URL("$serverUrl/v1/devices/register")
                val conn = registerUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")
                conn.setRequestProperty("User-Agent", "FreeSIMSMSGatewayApp/1.0")
                conn.doOutput = true
                conn.connectTimeout = 6000

                val reqBody = JSONObject()
                reqBody.put("phoneNumber", userSimNumber)
                reqBody.put("operator", carrierName)
                reqBody.put("model", deviceModel)
                reqBody.put("batteryLevel", batteryPct)

                val os: OutputStream = conn.outputStream
                os.write(reqBody.toString().toByteArray())
                os.flush()
                os.close()

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val resObj = JSONObject(reader.readText())
                    reader.close()

                    registeredDeviceId = resObj.optString("deviceId")

                    runOnUiThread {
                        isGatewayConnected = true
                        oneClickConnectButton.text = "🔴 DISABLE GATEWAY (TURN OFF)"
                        statusTextView.text = "🟢 ONLINE & ACTIVE ($userSimNumber Ready)"
                        Toast.makeText(this@MainActivity, "🟢 Gateway Connected & Turned ON!", Toast.LENGTH_SHORT).show()
                    }

                    startStatelessPollingEngine(serverUrl, registeredDeviceId!!)

                } else {
                    runOnUiThread {
                        statusTextView.text = "🔴 Connection Failed (HTTP ${conn.responseCode})"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    statusTextView.text = "🔴 Connection Error: ${e.message}"
                }
            }
        }
    }

    /**
     * TURN GATEWAY OFF -> Unregister from Central Server
     */
    private fun disconnectGateway() {
        isGatewayConnected = false
        val serverUrl = serverUrlInput.text.toString().trim()
        val deviceId = registeredDeviceId

        thread {
            try {
                if (serverUrl.isNotEmpty() && deviceId != null) {
                    val unregUrl = URL("$serverUrl/v1/devices/unregister")
                    val conn = unregUrl.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")
                    conn.setRequestProperty("User-Agent", "FreeSIMSMSGatewayApp/1.0")
                    conn.doOutput = true

                    val reqBody = JSONObject()
                    reqBody.put("deviceId", deviceId)
                    reqBody.put("phoneNumber", simNumberInput.text.toString().trim())

                    val os: OutputStream = conn.outputStream
                    os.write(reqBody.toString().toByteArray())
                    os.flush()
                    os.close()
                    conn.responseCode
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        registeredDeviceId = null
        runOnUiThread {
            oneClickConnectButton.text = "⚡ ALLOW PERMISSIONS & CONNECT GATEWAY (TURN ON)"
            statusTextView.text = "🔴 GATEWAY DISCONNECTED (OFF)"
            Toast.makeText(this@MainActivity, "🔴 Gateway Turned OFF Successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStatelessPollingEngine(serverUrl: String, deviceId: String) {
        thread {
            while (isGatewayConnected) {
                try {
                    val pollUrl = URL("$serverUrl/v1/devices/$deviceId/pending")
                    val conn = pollUrl.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")
                    conn.setRequestProperty("User-Agent", "FreeSIMSMSGatewayApp/1.0")
                    conn.connectTimeout = 4000

                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val resObj = JSONObject(reader.readText())
                        reader.close()

                        val pendingTasks = resObj.optJSONArray("data")
                        if (pendingTasks != null) {
                            for (i in 0 until pendingTasks.length()) {
                                val task = pendingTasks.getJSONObject(i)
                                val msgId = task.getString("msgId")
                                val recipientTo = task.getString("to")
                                val smsContent = task.getString("content")

                                val isSuccess = sendNativeSmsViaSim(recipientTo, smsContent)
                                reportDispatchStatus(serverUrl, msgId, if (isSuccess) "DELIVERED" else "FAILED")
                            }
                        }
                    }

                    Thread.sleep(2000)

                } catch (e: Exception) {
                    try { Thread.sleep(3000) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun sendNativeSmsViaSim(toPhone: String, textMessage: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(textMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(toPhone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(toPhone, null, textMessage, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun reportDispatchStatus(serverUrl: String, msgId: String, status: String) {
        try {
            val statusUrl = URL("$serverUrl/v1/devices/message-status")
            val conn = statusUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")
            conn.setRequestProperty("User-Agent", "FreeSIMSMSGatewayApp/1.0")
            conn.doOutput = true

            val body = JSONObject()
            body.put("msgId", msgId)
            body.put("status", status)

            val os: OutputStream = conn.outputStream
            os.write(body.toString().toByteArray())
            os.flush()
            os.close()

            conn.responseCode
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSimPhoneNumber(): String {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val num = tm.line1Number
                if (!num.isNullOrEmpty()) num else ""
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun getSimCarrierName(): String {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val name = tm.networkOperatorName
            if (!name.isNullOrEmpty()) name else "SIM Carrier"
        } catch (e: Exception) {
            "SIM Carrier"
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            95
        }
    }
}
