require("dotenv").config();
const express = require("express");
const cors = require("cors");
const path = require("path");
const http = require("http");
const { WebSocketServer } = require("ws");
const fs = require("fs");
let admin = null;

try {
    admin = require("firebase-admin");
} catch (e) {
    console.warn("⚠️ firebase-admin module not loaded yet.");
}

const app = express();
const server = http.createServer(app);
const PORT = process.env.PORT || 5102;

app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, "public")));

// Log incoming requests for debugging
app.use((req, res, next) => {
    if (req.url) {
        req.url = req.url.replace(/\/+/g, "/");
    }
    if (!req.url.startsWith("/public") && !req.url.endsWith(".css") && !req.url.endsWith(".js")) {
        console.log(`📡 [${new Date().toLocaleTimeString()}] ${req.method} ${req.url}`);
    }
    next();
});

// Default Demo API Key
const API_KEY = process.env.SMS_API_KEY || "demo_free_sim_key";

/* =========================================================================
   FIREBASE CLOUD MESSAGING (FCM) INITIALIZATION
   ========================================================================= */
let isFirebaseInitialized = false;

function initFirebase() {
    if (!admin) return;

    try {
        const serviceAccountPath = process.env.FIREBASE_SERVICE_ACCOUNT_PATH || path.join(__dirname, "firebase-service-account.json");
        const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;

        if (serviceAccountJson) {
            const credentials = JSON.parse(serviceAccountJson);
            admin.initializeApp({
                credential: admin.credential.cert(credentials)
            });
            isFirebaseInitialized = true;
            console.log("🔥 Firebase Admin SDK initialized from environment JSON!");
        } else if (fs.existsSync(serviceAccountPath)) {
            const serviceAccount = require(serviceAccountPath);
            admin.initializeApp({
                credential: admin.credential.cert(serviceAccount)
            });
            isFirebaseInitialized = true;
            console.log(`🔥 Firebase Admin SDK initialized from ${serviceAccountPath}!`);
        } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
            admin.initializeApp({
                credential: admin.credential.applicationDefault()
            });
            isFirebaseInitialized = true;
            console.log("🔥 Firebase Admin SDK initialized with Application Default Credentials!");
        } else {
            console.log("ℹ️ Firebase credentials not provided yet. (To enable FCM push when app is closed, add firebase-service-account.json). WebSocket direct dispatch will be used as fallback.");
        }
    } catch (err) {
        console.error("⚠️ Failed to initialize Firebase Admin SDK:", err.message);
    }
}

initFirebase();

/* =========================================================================
   REAL-TIME DEVICE REGISTRY (NO SMS STORAGE / NO QUEUES)
   ========================================================================= */

// Map<deviceId, deviceRecord>
// deviceRecord: { id, phoneNumber, fcmToken, operator, model, batteryLevel, status, lastPing, ws }
const activeDevices = new Map();

// Active OTP Store in RAM for verification
const activeOtps = new Map();

// In-flight message dispatch result callbacks (waiting for device execution ACK)
const pendingMessageCallbacks = new Map();

function generateMsgId() {
    return "msg_" + Math.random().toString(36).substring(2, 10);
}

function generateOtpCode(length = 6) {
    let otp = "";
    for (let i = 0; i < length; i++) {
        otp += Math.floor(Math.random() * 10);
    }
    return otp;
}

/* =========================================================================
   APK DOWNLOAD ENDPOINT
   ========================================================================= */
app.get(["/download/app.apk", "/FreeSMSGateway.apk"], (req, res) => {
    const apkPath = path.join(__dirname, "public", "FreeSMSGateway.apk");
    if (fs.existsSync(apkPath)) {
        return res.download(apkPath, "FreeSMSGateway.apk");
    }
    const rootApk = path.join(__dirname, "FreeSMSGateway.apk");
    if (fs.existsSync(rootApk)) {
        return res.download(rootApk, "FreeSMSGateway.apk");
    }
    return res.status(404).send("APK file not found on server.");
});

/* =========================================================================
   WEBSOCKET REAL-TIME SERVER (DIRECT BIDIRECTIONAL PUSH CHANNEL)
   ========================================================================= */
const wss = new WebSocketServer({ server, path: "/ws" });

wss.on("connection", (ws, req) => {
    let registeredDeviceId = null;
    console.log("🌐 Android Gateway connected via WebSocket");

    ws.on("message", (rawMessage) => {
        try {
            const data = JSON.parse(rawMessage.toString());

            // 1. Device Registration / Heartbeat from Mobile App
            if (data.type === "REGISTER_DEVICE" || data.type === "HEARTBEAT") {
                const deviceId = data.deviceId || "device_" + Math.random().toString(36).substring(2, 8);
                registeredDeviceId = deviceId;

                const existingDev = activeDevices.get(deviceId) || {};

                const deviceRecord = {
                    id: deviceId,
                    phoneNumber: data.phoneNumber || existingDev.phoneNumber || "Mobile SIM",
                    fcmToken: data.fcmToken || existingDev.fcmToken || null,
                    operator: data.operator || "Cellular SIM Gateway",
                    model: data.model || "Android Phone",
                    batteryLevel: data.batteryLevel !== undefined ? data.batteryLevel : 100,
                    status: "ONLINE",
                    lastPing: new Date().toISOString(),
                    ws: ws
                };

                activeDevices.set(deviceId, deviceRecord);
                console.log(`🟢 DEVICE REGISTERED via WS: Phone = ${deviceRecord.phoneNumber} | Model = ${deviceRecord.model} [ID: ${deviceId}]`);

                ws.send(JSON.stringify({
                    type: "REGISTER_ACK",
                    success: true,
                    deviceId: deviceId,
                    phoneNumber: deviceRecord.phoneNumber,
                    fcmEnabled: isFirebaseInitialized && !!deviceRecord.fcmToken,
                    message: "Device registered successfully for direct real-time SMS dispatch."
                }));
            }

            // 2. Real-Time SMS Dispatch Execution Result from Mobile App
            if (data.type === "DISPATCH_RESULT") {
                const { msgId, status, error } = data;
                console.log(`⚡ SMS Dispatch Result for [${msgId}]: Status = ${status}`);

                if (pendingMessageCallbacks.has(msgId)) {
                    const callback = pendingMessageCallbacks.get(msgId);
                    pendingMessageCallbacks.delete(msgId);
                    callback({
                        status: status || "DELIVERED",
                        error: error || null
                    });
                }
            }

        } catch (err) {
            console.error("❌ Invalid WS Message Payload:", err.message);
        }
    });

    ws.on("close", () => {
        if (registeredDeviceId && activeDevices.has(registeredDeviceId)) {
            const dev = activeDevices.get(registeredDeviceId);
            dev.ws = null;
            // Retain device if fcmToken is available so FCM pushes still reach it!
            if (!dev.fcmToken) {
                console.log(`🔴 SIM Device Disconnected: ${registeredDeviceId}`);
                activeDevices.delete(registeredDeviceId);
            } else {
                console.log(`📱 WS closed for ${registeredDeviceId}, but FCM push remains active.`);
            }
        }
    });
});

/* =========================================================================
   MIDDLEWARE: API KEY AUTHENTICATION
   ========================================================================= */
function authenticateApiKey(req, res, next) {
    const key = req.headers["x-api-key"] || req.query.apiKey || req.body.apiKey;
    if (!key || (key !== API_KEY && key !== "demo_free_sim_key")) {
        return res.status(401).json({
            status: "error",
            success: false,
            error: "Unauthorized: Invalid or missing X-API-KEY header."
        });
    }
    next();
}

/* =========================================================================
   CORE DIRECT SMS ROUTING ENGINE (NO QUEUES / NO STORAGE)
   ========================================================================= */
async function dispatchSmsStateless({ to, content, fromDeviceNumber = null, reqHost = null }) {
    const startTime = Date.now();
    const msgId = generateMsgId();
    const timestamp = new Date().toISOString();

    // 1. Find matching target SIM device
    let targetDevice = null;
    if (fromDeviceNumber) {
        const cleanTargetFrom = fromDeviceNumber.replace(/\D/g, "");
        for (const dev of activeDevices.values()) {
            const cleanDevNum = (dev.phoneNumber || "").replace(/\D/g, "");
            if (cleanDevNum && (cleanDevNum.endsWith(cleanTargetFrom) || cleanTargetFrom.endsWith(cleanDevNum) || cleanDevNum === cleanTargetFrom)) {
                targetDevice = dev;
                break;
            }
        }
    }

    // Default to any available device with FCM token or active WS
    if (!targetDevice && activeDevices.size > 0) {
        // Prioritize devices with active WS or FCM token
        for (const dev of activeDevices.values()) {
            if ((dev.ws && dev.ws.readyState === 1) || dev.fcmToken) {
                targetDevice = dev;
                break;
            }
        }
        if (!targetDevice) {
            targetDevice = activeDevices.values().next().value;
        }
    }

    const senderSimNumber = targetDevice ? targetDevice.phoneNumber : (fromDeviceNumber || "SIM Gateway Mobile");
    let dispatchedVia = "NONE";

    // 2. DIRECT DISPATCH: Method A - Real-Time WebSocket (Immediate if app is connected)
    if (targetDevice && targetDevice.ws && targetDevice.ws.readyState === 1) {
        dispatchedVia = "WEBSOCKET_DIRECT";
        console.log(`🚀 [${msgId}] Direct WebSocket dispatch to device ${targetDevice.id} (SIM: ${targetDevice.phoneNumber})...`);

        targetDevice.ws.send(JSON.stringify({
            type: "SEND_SMS",
            msgId: msgId,
            to: to,
            content: content
        }));
    }

    // 3. DIRECT DISPATCH: Method B - FCM High-Priority Push (Wakes up phone even if app is closed/killed)
    if (targetDevice && targetDevice.fcmToken && isFirebaseInitialized) {
        dispatchedVia = dispatchedVia === "WEBSOCKET_DIRECT" ? "WS_AND_FCM" : "FCM_HIGH_PRIORITY_PUSH";
        console.log(`🚀 [${msgId}] Sending High-Priority FCM Push Message to device token ${targetDevice.fcmToken.substring(0, 15)}...`);

        try {
            const fcmPayload = {
                token: targetDevice.fcmToken,
                data: {
                    type: "SEND_SMS",
                    msgId: msgId,
                    to: to,
                    content: content,
                    serverUrl: reqHost || `http://localhost:${PORT}`
                },
                android: {
                    priority: "high"
                }
            };

            admin.messaging().send(fcmPayload)
                .then(response => {
                    console.log(`✅ [${msgId}] FCM Push successfully delivered to Google Cloud: ${response}`);
                })
                .catch(err => {
                    console.error(`❌ [${msgId}] FCM Push failed:`, err.message);
                });
        } catch (fcmErr) {
            console.error(`❌ [${msgId}] FCM Error:`, fcmErr.message);
        }
    }

    if (!targetDevice) {
        console.log(`⚠️ SMS Request [${msgId}] to ${to} received, but NO mobile phone device is registered!`);
        const durationSec = ((Date.now() - startTime) / 1000).toFixed(1) + " seconds";
        return {
            success: false,
            status: "NO_DEVICE_CONNECTED",
            messageId: msgId,
            mobileNumber: to,
            message: content,
            sentViaSim: senderSimNumber,
            dispatchMethod: "NONE",
            deliveryTime: durationSec,
            note: "⚠️ No Android Gateway device is currently registered. Open Free SMS Gateway app on your phone and tap Register Gateway.",
            timestamp: timestamp
        };
    }

    const durationSec = ((Date.now() - startTime) / 1000).toFixed(1) + " seconds";
    return {
        success: true,
        status: "DISPATCHED",
        messageId: msgId,
        mobileNumber: to,
        message: content,
        sentViaSim: senderSimNumber,
        dispatchMethod: dispatchedVia,
        deliveryTime: durationSec,
        timestamp: timestamp
    };
}

/* =========================================================================
   PUBLIC REST API ENDPOINTS
   ========================================================================= */

app.get("/v1/sim/detect-device", (req, res) => {
    let activeDev = null;
    if (activeDevices.size > 0) {
        activeDev = activeDevices.values().next().value;
    }

    return res.json({
        status: "success",
        data: {
            connected: !!activeDev,
            device: activeDev ? activeDev.model : "Android Mobile SIM Gateway",
            details: activeDev ? (activeDev.fcmToken ? "FCM High-Priority Push Ready" : "WebSocket Active") : "No device connected",
            fcmPushEnabled: isFirebaseInitialized && !!(activeDev && activeDev.fcmToken),
            statusText: activeDev ? "ONLINE (Zero Polling / Direct Push)" : "OFFLINE",
            simNumber: activeDev ? activeDev.phoneNumber : "Mobile Number Missing"
        }
    });
});

app.get(["/v1/messages/send", "/api/send-sms"], (req, res) => {
    return res.json({
        status: "info",
        service: "Free SIM SMS Gateway API Endpoint",
        requestMethod: "HTTP POST Required",
        instructions: "Send HTTP POST request with JSON payload: { \"mobileNumber\": \"+919876543210\", \"message\": \"Your SMS text here\" }"
    });
});

const handleSendSms = async (req, res) => {
    try {
        const recipientPhone = req.body.to || req.body.mobileNumber || req.body.phone;
        const messageText = req.body.content || req.body.message || req.body.text || "Hello from SMS Gateway";
        const fromSim = req.body.from;

        if (!recipientPhone) {
            return res.status(400).json({
                status: "error",
                message: "Missing required parameters: 'to' (or 'mobileNumber') and 'content' (or 'message') must be provided."
            });
        }

        const host = req.protocol + "://" + req.get("host");

        const result = await dispatchSmsStateless({
            to: recipientPhone,
            content: messageText,
            fromDeviceNumber: fromSim,
            reqHost: host
        });

        const newMessageData = {
            id: result.messageId,
            content: messageText,
            from: result.sentViaSim,
            to: recipientPhone,
            status: result.status,
            cost: "₹0.00 (Free SIM)",
            dispatchMethod: result.dispatchMethod,
            timestamp: result.timestamp
        };

        return res.status(result.success ? 200 : 503).json({
            success: result.success,
            status: result.status,
            messageId: result.messageId,
            mobileNumber: recipientPhone,
            messageSent: messageText,
            sentViaSim: result.sentViaSim,
            dispatchMethod: result.dispatchMethod,
            deliveryTime: result.deliveryTime,
            data: newMessageData,
            timestamp: result.timestamp
        });

    } catch (err) {
        console.error("❌ SMS API Error:", err.message);
        return res.status(400).json({
            status: "error",
            message: err.message
        });
    }
};

app.post("/v1/messages/send", handleSendSms);
app.post("/api/send-sms", handleSendSms);

app.post("/v1/otp/send", async (req, res) => {
    try {
        const recipientPhone = req.body.mobileNumber || req.body.to;
        const appName = req.body.appName || "App";
        const expiryMinutes = req.body.expiryMinutes || 5;

        if (!recipientPhone) {
            return res.status(400).json({
                status: "error",
                message: "Missing required parameter: 'mobileNumber' (or 'to')."
            });
        }

        const otpCode = generateOtpCode(6);
        const expiresAt = Date.now() + (expiryMinutes * 60 * 1000);
        
        activeOtps.set(recipientPhone.replace(/\s+/g, ""), {
            code: otpCode,
            expiresAt: expiresAt
        });

        const smsContent = `Your ${appName} verification OTP code is: ${otpCode}. Valid for ${expiryMinutes} minutes. Do not share with anyone.`;
        const host = req.protocol + "://" + req.get("host");

        const result = await dispatchSmsStateless({
            to: recipientPhone,
            content: smsContent,
            reqHost: host
        });

        return res.status(200).json({
            status: "success",
            message: "OTP dispatched successfully to mobile device",
            data: {
                otpCodeSent: otpCode,
                to: recipientPhone,
                content: smsContent,
                dispatchMethod: result.dispatchMethod,
                timestamp: result.timestamp
            }
        });

    } catch (err) {
        return res.status(400).json({
            status: "error",
            message: err.message
        });
    }
});

app.post("/v1/otp/verify", (req, res) => {
    const recipientPhone = req.body.mobileNumber || req.body.to;
    const otpCode = req.body.otpCode || req.body.otp;

    if (!recipientPhone || !otpCode) {
        return res.status(400).json({
            status: "error",
            message: "Missing parameters: 'mobileNumber' and 'otpCode'."
        });
    }

    const cleanPhone = recipientPhone.replace(/\s+/g, "");
    const otpRecord = activeOtps.get(cleanPhone);

    if (!otpRecord) {
        return res.status(400).json({
            status: "error",
            message: "No active OTP found for this phone number. Request a new OTP."
        });
    }

    if (Date.now() > otpRecord.expiresAt) {
        activeOtps.delete(cleanPhone);
        return res.status(400).json({
            status: "error",
            message: "OTP code has expired. Please request a new OTP."
        });
    }

    if (otpRecord.code !== otpCode.toString().trim()) {
        return res.status(400).json({
            status: "error",
            message: "Invalid OTP code entered."
        });
    }

    activeOtps.delete(cleanPhone);

    return res.status(200).json({
        status: "success",
        message: "Mobile phone number successfully verified!"
    });
});

app.get("/v1/devices", (req, res) => {
    const devicesList = [];
    for (const dev of activeDevices.values()) {
        devicesList.push({
            deviceId: dev.id,
            phoneNumber: dev.phoneNumber,
            hasFcmToken: !!dev.fcmToken,
            fcmTokenPreview: dev.fcmToken ? dev.fcmToken.substring(0, 15) + "..." : null,
            hasWebSocket: !!(dev.ws && dev.ws.readyState === 1),
            operator: dev.operator,
            model: dev.model,
            batteryLevel: dev.batteryLevel,
            status: dev.status,
            lastPing: dev.lastPing
        });
    }

    return res.status(200).json({
        status: "success",
        count: devicesList.length,
        firebaseConfigured: isFirebaseInitialized,
        pollingDeprecated: true,
        devices: devicesList
    });
});

/* =========================================================================
   DEVICE REGISTRATION & STATUS CALLBACK ENDPOINTS
   ========================================================================= */

// Direct status callback from mobile device after SMS is sent natively via SIM
app.post(["/v1/messages/:id/status", "/v1/devices/message-status"], (req, res) => {
    const msgId = req.params.id || req.body.msgId;
    const status = req.body.status || "DELIVERED";
    const error = req.body.error || null;

    console.log(`✅ Mobile Device Transmitted SMS via SIM! Message ID: [${msgId}] Status: ${status}${error ? " Error: " + error : ""}`);

    if (pendingMessageCallbacks.has(msgId)) {
        const callback = pendingMessageCallbacks.get(msgId);
        pendingMessageCallbacks.delete(msgId);
        callback({ status: status, error: error });
    }

    return res.status(200).json({ status: "success" });
});

// Device Registration endpoint (Supports FCM push token registration)
app.all(["/v1/devices/register", "/register"], (req, res) => {
    const payload = req.method === "GET" ? req.query : (req.body || {});
    const deviceId = payload.deviceId || req.query.deviceId || "device_" + Math.random().toString(36).substring(2, 8);
    const phoneNumber = payload.phoneNumber || req.query.phoneNumber || "Mobile SIM";
    const fcmToken = payload.fcmToken || req.query.fcmToken || null;
    const operator = payload.operator || req.query.operator || "SIM Carrier";
    const model = payload.model || req.query.model || "Android Phone";
    const batteryLevel = payload.batteryLevel !== undefined ? payload.batteryLevel : 100;

    const existingDev = activeDevices.get(deviceId) || {};

    const devRecord = {
        id: deviceId,
        phoneNumber: phoneNumber,
        fcmToken: fcmToken || existingDev.fcmToken || null,
        operator: operator,
        model: model,
        batteryLevel: batteryLevel,
        status: "ONLINE",
        lastPing: new Date().toISOString(),
        ws: existingDev.ws || null
    };

    activeDevices.set(deviceId, devRecord);
    console.log(`📱 DEVICE REGISTERED: ${devRecord.phoneNumber} (${devRecord.model}) | FCM: ${devRecord.fcmToken ? "YES" : "NO"} [ID: ${deviceId}]`);

    return res.status(200).json({
        status: "success",
        deviceId: deviceId,
        fcmConfigured: isFirebaseInitialized,
        hasFcmToken: !!devRecord.fcmToken,
        message: "Device registered successfully for direct real-time SMS dispatch."
    });
});

app.post("/v1/devices/unregister", (req, res) => {
    const { deviceId, phoneNumber } = req.body;
    if (deviceId && activeDevices.has(deviceId)) {
        activeDevices.delete(deviceId);
    }
    if (phoneNumber) {
        for (const [id, dev] of activeDevices.entries()) {
            if (dev.phoneNumber === phoneNumber) {
                activeDevices.delete(id);
            }
        }
    }
    console.log(`🔴 SIM DEVICE UNREGISTERED: ${phoneNumber || deviceId}`);
    return res.status(200).json({ status: "success", message: "Device unregistered successfully." });
});

app.get("/v1/fcm/status", (req, res) => {
    return res.status(200).json({
        firebaseInitialized: isFirebaseInitialized,
        activeDevicesCount: activeDevices.size,
        devicesWithFcmToken: Array.from(activeDevices.values()).filter(d => !!d.fcmToken).length,
        instructions: !isFirebaseInitialized
            ? "Place your Firebase Service Account JSON as 'firebase-service-account.json' in server root or set FIREBASE_SERVICE_ACCOUNT_JSON env var to enable FCM push."
            : "Firebase is fully configured and ready for High-Priority FCM push wakeups."
    });
});

app.get("/v1/system/health", (req, res) => {
    return res.status(200).json({
        status: "OK",
        service: "Free SMS Gateway Real-Time API Server",
        activeDevicesCount: activeDevices.size,
        fcmPushEnabled: isFirebaseInitialized,
        pollingDisabled: true,
        noSmsStorage: true,
        timestamp: new Date().toISOString()
    });
});

/* =========================================================================
   START SERVER
   ========================================================================= */
server.listen(PORT, async () => {
    console.log(`
    =================================================================
    🚀 FREE SMS GATEWAY REAL-TIME DIRECT API SERVER ACTIVE
    =================================================================
    📡 Base API Server URL : http://localhost:${PORT}
    🔥 FCM Push Support   : ${isFirebaseInitialized ? "ENABLED ✅" : "WAITING FOR CREDENTIALS ℹ️"}
    🚫 2s Polling Loops    : ELIMINATED (Direct Push Only)
    🚫 SMS Server Storage  : NONE (Direct Mobile Transmission)
    🌐 Direct Send Endpoint: POST http://localhost:${PORT}/v1/messages/send
    🔍 Device Status       : GET  http://localhost:${PORT}/v1/sim/detect-device
    🔑 Developer API Key  : demo_free_sim_key
    =================================================================
    `);
});