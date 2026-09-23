const express = require("express");
const cors = require("cors");
const path = require("path");
const http = require("http");
const fs = require("fs");
const admin = require("firebase-admin");
const { WebSocketServer } = require("ws");

const app = express();
const server = http.createServer(app);
const PORT = process.env.PORT || 5102;
const DOMAIN_SERVER_URL = process.env.DOMAIN_SERVER_URL || "https://api.aranyasetu.org";
const LOCAL_SERVER_URL = process.env.LOCAL_SERVER_URL || "http://192.168.29.54:5102";
const PUBLIC_SERVER_URL = process.env.PUBLIC_SERVER_URL || "http://115.124.117.226:5102";
const DATA_FILE = path.join(__dirname, "jobs_data.json");
const DEVICES_FILE = path.join(__dirname, "devices.json");
if (fs.existsSync(DATA_FILE)) { try { fs.unlinkSync(DATA_FILE); } catch (ignored) {} }
if (fs.existsSync(DEVICES_FILE)) { try { fs.unlinkSync(DEVICES_FILE); } catch (ignored) {} }

app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, "public")));

// Request logger
app.use((req, res, next) => {
    if (!req.url.startsWith("/public") && !req.url.endsWith(".css") && !req.url.endsWith(".js")) {
        console.log(`📡 [${new Date().toLocaleTimeString()}] ${req.method} ${req.url}`);
    }
    next();
});

/* =========================================================================
   FIREBASE ADMIN SDK (FCM)
   ========================================================================= */
let firebaseInitialized = false;
const keyPath = process.env.FIREBASE_SERVICE_ACCOUNT || path.join(__dirname, "serviceAccountKey.json");

if (fs.existsSync(keyPath)) {
    try {
        const serviceAccount = require(keyPath);
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        });
        firebaseInitialized = true;
        console.log(`🔥 [Firebase] Admin SDK initialized from: ${path.basename(keyPath)}`);
    } catch (err) {
        console.error("❌ [Firebase] Initialization failed:", err.message);
    }
} else {
    console.log(`⚠️  [Firebase] 'serviceAccountKey.json' not found. FCM in simulation mode.`);
}

/* =========================================================================
   WEBSOCKET SERVER FOR ZERO-POLLING LIVE FOREGROUND STATUS
   ========================================================================= */
const wss = new WebSocketServer({ server, path: "/ws" });

wss.on("connection", (ws) => {
    console.log("🌐 Companion App connected via WebSocket (Live Status Active)");
    ws.send(JSON.stringify({ type: "STATUS", status: "CONNECTED", requestRegister: true }));

    ws.on("message", (raw) => {
        try {
            const msg = JSON.parse(raw);
            if (msg.type === "REGISTER" && msg.fcmToken) {
                const deviceId = msg.deviceId || "dev_" + Math.random().toString(36).substring(2, 8);
                const simCards = msg.simCards || [];
                const selectedSimSlot = msg.selectedSimSlot !== undefined ? parseInt(msg.selectedSimSlot) : 0;
                let phoneNumber = msg.phoneNumber || "Active SIM Gateway";
                let operator = msg.operator || "SIM Gateway";
                if (simCards.length > 0) {
                    const sim = simCards.find(s => s.slot === selectedSimSlot) || simCards[0];
                    if (sim.number && /\d/.test(sim.number)) {
                        phoneNumber = sim.number;
                    }
                    if (sim.carrier) {
                        operator = sim.carrier;
                    }
                }
                activeDevices.set(deviceId, {
                    id: deviceId,
                    deviceId: deviceId,
                    fcmToken: msg.fcmToken,
                    phoneNumber: phoneNumber,
                    model: msg.model || "Android Phone",
                    operator: operator,
                    batteryLevel: msg.batteryLevel !== undefined ? msg.batteryLevel : 100,
                    networkType: msg.networkType || "Unknown",
                    simCards: simCards,
                    selectedSimSlot: selectedSimSlot,
                    status: "ONLINE",
                    lastPing: new Date().toISOString()
                });
                console.log(`📱 Device registered via WebSocket: ${deviceId} (${phoneNumber}) | SIM Slot: ${selectedSimSlot}`);
                ws.send(JSON.stringify({ type: "REGISTERED", deviceId, fcmRegistered: true, selectedSimSlot }));
            }
        } catch (e) {
            console.error("⚠️ Invalid WebSocket message payload:", e.message);
        }
    });

    ws.on("close", () => {
        console.log("🔴 Companion App WebSocket closed");
    });

    ws.on("error", (err) => {
        console.error("⚠️ Companion App WebSocket error:", err.message);
    });
});

/* =========================================================================
   IN-MEMORY DATA STORES (Zero disk files, pure in-memory)
   ========================================================================= */
const activeDevices = new Map();
const smsJobs = new Map();
const activeOtps = new Map();
const pendingJobCallbacks = new Map();
let lastKnownSenderNumber = process.env.SENDER_NUMBER || null;
let lastKnownCarrier = process.env.CARRIER_NAME || null;

function generateJobId() {
    return "job_" + Date.now() + "_" + Math.random().toString(36).substring(2, 7);
}

function generateOtp(len = 6) {
    let otp = "";
    for (let i = 0; i < len; i++) otp += Math.floor(Math.random() * 10);
    return otp;
}

/* =========================================================================
   CARRIER ANTI-BAN RATE LIMITER (1.5s Spacing per SIM)
   ========================================================================= */
let lastDispatchTime = 0;
const MIN_DISPATCH_INTERVAL_MS = 1500; // 1.5s telecom safe delay

async function enforceCarrierPacing() {
    const elapsed = Date.now() - lastDispatchTime;
    if (elapsed < MIN_DISPATCH_INTERVAL_MS) {
        await new Promise((r) => setTimeout(r, MIN_DISPATCH_INTERVAL_MS - elapsed));
    }
    lastDispatchTime = Date.now();
}

/* =========================================================================
   MULTI-DEVICE LOAD BALANCING (ROUND-ROBIN)
   ========================================================================= */
let roundRobinIndex = 0;

function selectTargetDevice(fromNumber = null, simSlot = -1) {
    const devices = Array.from(activeDevices.values()).filter((d) => !!d.fcmToken);
    if (devices.length === 0) return null;

    if (fromNumber) {
        const cleanFrom = fromNumber.replace(/\D/g, "");
        for (const dev of devices) {
            const devPhone = (dev.phoneNumber || "").replace(/\D/g, "");
            if (devPhone.endsWith(cleanFrom) || cleanFrom.endsWith(devPhone)) {
                return dev;
            }
        }
    }

    // Round-robin distribution across multiple connected phones
    const device = devices[roundRobinIndex % devices.length];
    roundRobinIndex = (roundRobinIndex + 1) % devices.length;
    return device;
}

/* =========================================================================
   FCM SMS DISPATCH ENGINE
   ========================================================================= */
async function dispatchSmsJob({
    to,
    content,
    from = null,
    simSlot = -1,
    webhookUrl = null,
    serverUrl = null,
    waitForReceipt = false,
    timeoutMs = 2000
}) {
    const startTime = Date.now();
    const jobId = generateJobId();

    const targetDevice = selectTargetDevice(from, simSlot);
    const anyDevice = targetDevice || (activeDevices.size > 0 ? Array.from(activeDevices.values())[0] : null);

    let senderSim = from || (anyDevice ? anyDevice.phoneNumber : null);
    if (!senderSim || !/\d/.test(senderSim) || senderSim === "Active SIM Gateway" || senderSim === "None") {
        if (anyDevice && anyDevice.simCards && anyDevice.simCards.length > 0) {
            const slotIdx = simSlot >= 0 ? simSlot : 0;
            const sim = anyDevice.simCards[slotIdx] || anyDevice.simCards[0];
            if (sim.number && /\d/.test(sim.number)) {
                senderSim = sim.number;
            }
        }
    }
    if (!senderSim || !/\d/.test(senderSim)) {
        senderSim = process.env.SENDER_NUMBER || senderSim || "SIM Device";
    }

    let effectiveSlot = simSlot;
    if (effectiveSlot < 0) {
        if (targetDevice && targetDevice.selectedSimSlot !== undefined) {
            effectiveSlot = targetDevice.selectedSimSlot;
        } else if (anyDevice && anyDevice.selectedSimSlot !== undefined) {
            effectiveSlot = anyDevice.selectedSimSlot;
        }
    }

    let carrierName = (anyDevice && anyDevice.operator) ? anyDevice.operator : (lastKnownCarrier || "SIM Carrier");
    if (anyDevice && anyDevice.simCards && anyDevice.simCards.length > 0) {
        const slotIdx = effectiveSlot >= 0 ? effectiveSlot : 0;
        const sim = anyDevice.simCards.find(s => s.slot === slotIdx) || anyDevice.simCards[0];
        if (sim.carrier) {
            carrierName = sim.carrier;
        }
        if (sim.number && /\d/.test(sim.number)) {
            senderSim = sim.number;
        }
    }
    if (!carrierName || carrierName === "SIM Gateway" || carrierName === "Cellular Carrier") {
        carrierName = lastKnownCarrier || "SIM Carrier";
    }

    const job = {
        id: jobId,
        jobId: jobId,
        deviceId: targetDevice ? targetDevice.id : (anyDevice ? anyDevice.id : null),
        fcmToken: targetDevice ? targetDevice.fcmToken : null,
        senderMobileNumber: senderSim,
        to: to,
        content: content,
        simSlot: effectiveSlot,
        webhookUrl: webhookUrl || null,
        status: "PENDING",
        sentViaSim: carrierName,
        error: null,
        createdAt: new Date().toISOString(),
        dispatchedAt: null,
        completedAt: null
    };

    smsJobs.set(jobId, job);

    if (!firebaseInitialized) {
        job.status = "DISPATCHED";
        job.dispatchedAt = new Date().toISOString();
        return {
            success: true,
            status: "DISPATCHED_SIMULATED",
            jobId: jobId,
            messageId: jobId,
            senderMobileNumber: senderSim,
            mobileNumber: to,
            message: content,
            sentViaSim: carrierName,
            deliveryTime: "0.1 seconds",
            note: "Simulated mode: Add serviceAccountKey.json for real FCM dispatch."
        };
    }

    // Anti-ban spacing
    await enforceCarrierPacing();

    const fcmPayload = {
        data: {
            type: "SEND_SMS",
            action: "SEND_SMS",
            jobId: jobId,
            to: to,
            content: content,
            simSlot: String(effectiveSlot),
            serverUrl: serverUrl || DOMAIN_SERVER_URL,
            domainServerUrl: DOMAIN_SERVER_URL,
            localServerUrl: LOCAL_SERVER_URL,
            publicServerUrl: PUBLIC_SERVER_URL
        },
        android: {
            priority: "high"
        }
    };

    if (targetDevice && targetDevice.fcmToken) {
        fcmPayload.token = targetDevice.fcmToken;
    } else {
        fcmPayload.topic = "sms_gateway";
    }

    try {
        await admin.messaging().send(fcmPayload);
        job.status = "DISPATCHED";
        job.dispatchedAt = new Date().toISOString();
        console.log(`⚡ FCM Push Sent for Job [${jobId}] via ${fcmPayload.topic ? `Topic ('${fcmPayload.topic}')` : `Device Token`} (SIM Slot: ${job.simSlot})`);
    } catch (err) {
        job.status = "FAILED";
        job.error = err.message;
        console.error(`❌ FCM Error for Job [${jobId}]:`, err.message);
        return {
            success: false,
            status: "FCM_FAILED",
            jobId: jobId,
            senderMobileNumber: senderSim,
            mobileNumber: to,
            error: err.message
        };
    }

    // Fast asynchronous return mode (Default: returns immediately in ~0.2 - 0.4s!)
    if (!waitForReceipt) {
        const duration = ((Date.now() - startTime) / 1000).toFixed(1) + " seconds";
        return {
            success: true,
            status: "DISPATCHED",
            jobId: jobId,
            messageId: jobId,
            senderMobileNumber: senderSim,
            mobileNumber: to,
            message: content,
            sentViaSim: carrierName,
            deliveryTime: duration,
            note: "FCM Topic push dispatched to phone. Cellular transmission continuing in background."
        };
    }

    // Synchronous mode with timeout
    return new Promise((resolve) => {
        const timer = setTimeout(() => {
            if (pendingJobCallbacks.has(jobId)) {
                pendingJobCallbacks.delete(jobId);
                const duration = ((Date.now() - startTime) / 1000).toFixed(1) + " seconds";
                const finalSender = job.senderMobileNumber || senderSim;
                resolve({
                    success: true,
                    status: job.status === "SENT" ? "SENT" : "DISPATCHED",
                    jobId: jobId,
                    messageId: jobId,
                    senderMobileNumber: finalSender,
                    mobileNumber: to,
                    message: content,
                    sentViaSim: job.sentViaSim || carrierName,
                    deliveryTime: duration,
                    note: "FCM Topic push dispatched to phone. Cellular transmission continuing in background."
                });
            }
        }, timeoutMs);

        pendingJobCallbacks.set(jobId, (result) => {
            clearTimeout(timer);
            const duration = ((Date.now() - startTime) / 1000).toFixed(1) + " seconds";
            const finalSender = result.senderMobileNumber || job.senderMobileNumber || senderSim;
            const finalCarrier = result.carrier || job.sentViaSim || carrierName;
            resolve({
                success: result.status === "SENT",
                status: result.status || "SENT",
                jobId: jobId,
                messageId: jobId,
                senderMobileNumber: finalSender,
                mobileNumber: to,
                message: content,
                sentViaSim: finalCarrier,
                deliveryTime: duration,
                error: result.error
            });
        });
    });
}

/* =========================================================================
   TRIGGER WEBHOOK CALLER NOTIFICATION
   ========================================================================= */
function notifyWebhook(job) {
    if (!job.webhookUrl) return;

    const payload = {
        event: job.status === "SENT" ? "sms.sent" : "sms.failed",
        jobId: job.id,
        to: job.to,
        status: job.status,
        sentViaSim: job.sentViaSim,
        error: job.error,
        completedAt: job.completedAt
    };

    fetch(job.webhookUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
    }).catch((err) => console.log(`⚠️ Webhook delivery failed for [${job.id}]:`, err.message));
}

/* =========================================================================
   DEVICE REGISTRATION WITH TELEMETRY & DUAL-SIM
   ========================================================================= */
app.all(["/v1/devices/register", "/register"], (req, res) => {
    const data = req.method === "GET" ? req.query : (req.body || {});
    const deviceId = data.deviceId || "dev_" + Math.random().toString(36).substring(2, 8);
    const fcmToken = data.fcmToken || data.token || null;
    const selectedSimSlot = data.selectedSimSlot !== undefined ? parseInt(data.selectedSimSlot) : 0;
    const simCards = data.simCards || [];
    let phoneNumber = data.phoneNumber || "Active SIM Gateway";
    let operator = data.operator || "SIM Gateway";

    if (simCards.length > 0) {
        const sim = simCards.find(s => s.slot === selectedSimSlot) || simCards[0];
        if (sim.number && /\d/.test(sim.number)) {
            phoneNumber = sim.number;
        }
        if (sim.carrier) {
            operator = sim.carrier;
        }
    }

    activeDevices.set(deviceId, {
        id: deviceId,
        deviceId: deviceId,
        fcmToken: fcmToken,
        phoneNumber: phoneNumber,
        model: data.model || "Android Phone",
        operator: operator,
        batteryLevel: data.batteryLevel !== undefined ? data.batteryLevel : 100,
        networkType: data.networkType || "Unknown",
        simCards: simCards,
        selectedSimSlot: selectedSimSlot,
        status: "ONLINE",
        lastPing: new Date().toISOString()
    });

    if (phoneNumber && /\d/.test(phoneNumber)) {
        lastKnownSenderNumber = phoneNumber;
    }

    console.log(`📱 Device registered: ${deviceId} (${phoneNumber}) | SIM Slot: ${selectedSimSlot} | Battery: ${data.batteryLevel}% | Net: ${data.networkType}`);
    return res.json({ status: "success", deviceId, fcmRegistered: !!fcmToken });
});

app.post("/v1/devices/unregister", (req, res) => {
    const { deviceId, phoneNumber } = req.body;
    if (deviceId) activeDevices.delete(deviceId);
    if (phoneNumber) {
        for (const [id, dev] of activeDevices.entries()) {
            if (dev.phoneNumber === phoneNumber) activeDevices.delete(id);
        }
    }
    console.log(`🔴 Device unregistered: ${deviceId || phoneNumber}`);
    return res.json({ status: "success" });
});

app.post(["/v1/devices/number", "/v1/devices/set-number", "/v1/devices/phone-number"], (req, res) => {
    const number = req.body.number || req.body.phoneNumber || req.body.phone;
    if (!number) {
        return res.status(400).json({ status: "error", message: "Missing 'phoneNumber' or 'number' in request body." });
    }
    for (const [id, dev] of activeDevices.entries()) {
        dev.phoneNumber = number;
    }
    lastKnownSenderNumber = number;
    console.log(`📱 Gateway sender number configured: ${number}`);
    return res.json({ status: "success", phoneNumber: number, message: "Gateway sender mobile number updated." });
});

/* =========================================================================
   STATUS CALLBACK FROM ANDROID
   ========================================================================= */
const handleResult = (req, res) => {
    const jobId = req.params.jobId || req.body.jobId || req.body.msgId;
    const status = req.body.status || "SENT";
    const error = req.body.error || null;
    const senderMobileNumber = req.body.senderMobileNumber || req.body.senderNumber || req.body.fromNumber || null;
    const carrier = req.body.carrier || req.body.sentViaSim || req.body.operator || lastKnownCarrier;

    if (senderMobileNumber && /\d/.test(senderMobileNumber)) {
        lastKnownSenderNumber = senderMobileNumber;
    }
    if (carrier && !/\d{5,}/.test(carrier)) {
        lastKnownCarrier = carrier;
    }

    console.log(`✅ [Carrier Result] Job [${jobId}] => ${status}${error ? ` (${error})` : ""}${senderMobileNumber ? ` | SIM: ${senderMobileNumber}` : ""}${carrier ? ` (${carrier})` : ""}`);

    if (jobId && smsJobs.has(jobId)) {
        const job = smsJobs.get(jobId);
        job.status = status;
        job.error = error;
        if (senderMobileNumber) {
            job.senderMobileNumber = senderMobileNumber;
        }
        if (carrier && !/\d{5,}/.test(carrier)) {
            job.sentViaSim = carrier;
        }
        job.completedAt = new Date().toISOString();
        notifyWebhook(job);
    }

    if (jobId && pendingJobCallbacks.has(jobId)) {
        pendingJobCallbacks.get(jobId)({ status, error, senderMobileNumber, carrier });
        pendingJobCallbacks.delete(jobId);
    }

    return res.json({ status: "success", jobId, jobStatus: status, senderMobileNumber, sentViaSim: carrier });
};

app.post("/v1/jobs/:jobId/result", handleResult);
app.post(["/v1/devices/message-status", "/v1/messages/:id/status"], handleResult);

/* =========================================================================
   SEND SMS API (Supports Dual-SIM slot & Webhooks)
   ========================================================================= */
const handleSend = async (req, res) => {
    const to = req.body.to || req.body.mobileNumber || req.body.phone;
    const content = req.body.content || req.body.message || req.body.text;
    const from = req.body.senderMobileNumber || req.body.from || req.body.senderNumber || req.body.fromNumber;
    const simSlot = req.body.simSlot !== undefined ? parseInt(req.body.simSlot) : -1;
    const webhookUrl = req.body.webhookUrl || req.body.callbackUrl;
    const waitForReceipt = req.body.waitForReceipt === true || req.body.wait === true || req.query.wait === "true";
    const timeoutMs = req.body.timeoutMs ? parseInt(req.body.timeoutMs) : 2000;

    if (!to || !content) {
        return res.status(400).json({ status: "error", message: "Missing 'to' or 'content'." });
    }

    const host = req.get("host") || "";
    const protocol = req.headers["x-forwarded-proto"] || req.protocol || "http";
    let serverUrl = req.body.serverUrl;
    if (!serverUrl) {
        if (host.includes("aranyasetu.org")) {
            serverUrl = DOMAIN_SERVER_URL;
        } else if (host.includes("115.124.117.226")) {
            serverUrl = PUBLIC_SERVER_URL;
        } else if (host.includes("192.168.29.54")) {
            serverUrl = LOCAL_SERVER_URL;
        } else if (host.includes("localhost") || host.includes("127.0.0.1")) {
            serverUrl = LOCAL_SERVER_URL;
        } else {
            serverUrl = `${protocol}://${host}`;
        }
    }

    const result = await dispatchSmsJob({
        to: to,
        content: content,
        from: from,
        simSlot: simSlot,
        webhookUrl: webhookUrl,
        serverUrl: serverUrl,
        waitForReceipt: waitForReceipt,
        timeoutMs: timeoutMs
    });

    return res.status(result.success ? 200 : 400).json(result);
};

app.post(["/v1/messages/send", "/api/send-sms"], handleSend);

/* =========================================================================
   OTP API
   ========================================================================= */
app.post("/v1/otp/send", async (req, res) => {
    const to = req.body.mobileNumber || req.body.to;
    const appName = req.body.appName || "App";
    const expiry = req.body.expiryMinutes || 5;

    if (!to) return res.status(400).json({ status: "error", message: "Missing 'mobileNumber'." });

    const code = generateOtp(6);
    activeOtps.set(to.replace(/\s+/g, ""), { code, expiresAt: Date.now() + expiry * 60000 });

    const text = `Your ${appName} verification code is: ${code}. Valid for ${expiry} mins.`;
    const result = await dispatchSmsJob({ to, content: text });

    return res.json({ status: "success", data: { otpCodeSent: code, to, jobId: result.jobId } });
});

app.post("/v1/otp/verify", (req, res) => {
    const to = (req.body.mobileNumber || req.body.to || "").replace(/\s+/g, "");
    const code = req.body.otpCode || req.body.otp;

    const record = activeOtps.get(to);
    if (!record) return res.status(400).json({ status: "error", message: "No active OTP." });
    if (Date.now() > record.expiresAt) {
        activeOtps.delete(to);
        return res.status(400).json({ status: "error", message: "OTP expired." });
    }
    if (record.code !== String(code).trim()) {
        return res.status(400).json({ status: "error", message: "Invalid OTP." });
    }

    activeOtps.delete(to);
    return res.json({ status: "success", message: "Phone number verified!" });
});

/* =========================================================================
   INSPECTION & HEALTH
   ========================================================================= */
app.get("/v1/devices", (req, res) => {
    return res.json({
        status: "success",
        count: activeDevices.size,
        devices: Array.from(activeDevices.values()).map((d) => ({
            deviceId: d.id,
            phoneNumber: d.phoneNumber,
            model: d.model,
            batteryLevel: d.batteryLevel,
            networkType: d.networkType,
            simCards: d.simCards,
            status: d.status,
            fcmRegistered: !!d.fcmToken,
            lastPing: d.lastPing
        }))
    });
});

app.get("/v1/sim/detect-device", (req, res) => {
    const dev = activeDevices.values().next().value;
    return res.json({
        status: "success",
        data: {
            connected: !!dev,
            device: dev ? dev.model : "None",
            batteryLevel: dev ? dev.batteryLevel : null,
            networkType: dev ? dev.networkType : null,
            simCards: dev ? dev.simCards : [],
            statusText: dev ? "ONLINE (FCM)" : "OFFLINE",
            simNumber: dev ? dev.phoneNumber : "None"
        }
    });
});

app.get("/v1/jobs/:jobId", (req, res) => {
    const job = smsJobs.get(req.params.jobId);
    if (!job) return res.status(404).json({ status: "error", message: "Job not found." });
    return res.json({ status: "success", job });
});

app.get("/v1/jobs", (req, res) => {
    return res.json({
        status: "success",
        count: smsJobs.size,
        jobs: Array.from(smsJobs.values()).reverse().slice(0, 50)
    });
});

app.get("/v1/system/health", (req, res) => {
    return res.json({
        status: "OK",
        firebaseLive: firebaseInitialized,
        activeDevices: activeDevices.size,
        totalJobs: smsJobs.size,
        timestamp: new Date().toISOString()
    });
});

/* =========================================================================
   APK DOWNLOAD
   ========================================================================= */
app.get(["/download/app.apk", "/FreeSMSGateway.apk"], (req, res) => {
    const apkPaths = [
        path.join(__dirname, "FreeSMSGateway.apk"),
        path.join(__dirname, "public", "FreeSMSGateway.apk"),
        path.join(__dirname, "android_app", "app", "build", "outputs", "apk", "release", "app-release.apk")
    ];
    for (const p of apkPaths) {
        if (fs.existsSync(p)) return res.download(p, "FreeSMSGateway.apk");
    }
    return res.status(404).send("APK file not found on server.");
});

/* =========================================================================
   START
   ========================================================================= */
server.listen(PORT, () => {
    console.log(`
    =================================================================
    🚀 FREE SIM FCM SMS GATEWAY SERVER ACTIVE (ENTERPRISE EDITION)
    =================================================================
    📡 URL            : http://localhost:${PORT}
    🔥 Firebase Admin : ${firebaseInitialized ? "INITIALIZED (LIVE)" : "PENDING"}
    🌐 WebSocket      : ws://localhost:${PORT}/ws
    📥 Download APK   : http://localhost:${PORT}/download/app.apk
    📤 Send SMS API   : POST http://localhost:${PORT}/v1/messages/send
    📱 Devices List   : GET  http://localhost:${PORT}/v1/devices
    📋 Health         : GET  http://localhost:${PORT}/v1/system/health
    =================================================================
    `);
});