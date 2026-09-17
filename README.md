# 📱 Free Mobile SIM SMS Gateway Proof of Concept (POC)

A self-hosted, **Zero-Cost Mobile SIM SMS Gateway system** built with **JavaScript (Node.js)** and an **Android Gateway Companion**, modeled after **httpSMS** (https://httpsms.com).

This system allows sending up to **200 FREE SMS/day** using a mobile phone's inserted SIM card instead of paying commercial SMS gateway fees (Twilio, AWS SNS, Fast2SMS), saving **$20 – $50 every month**!

---

## 🚀 Quick Start (One-Click Launch for Your Sir)

### Option 1: Double-Click Batch Launcher (Windows)
Double-click `start_sms_gateway.bat` in this folder:
1. Launches the Node.js JavaScript SMS Gateway Server on `http://localhost:3000`.
2. Opens the Web Dashboard UI automatically in Google Chrome / Edge.

### Option 2: Double-Click Standalone Browser Launcher
Double-click `launch_poc.html`:
- Opens the SMS Gateway Dashboard directly in any web browser without needing terminal commands or Node.js setup!

---

## 💻 Developer JavaScript SDK Usage (`httpSmsClient.js`)

This project implements the exact SDK syntax requested by your sir:

```javascript
import HttpSms from './httpSmsClient.js';

// Initialize Client (Default base URL: http://localhost:3000)
const client = new HttpSms('demo_free_sim_key');

// Send SMS via Mobile SIM Card
client.messages.postSend({
    content:   'Hello Sir! This text message was sent for FREE using our mobile SIM card gateway!',
    from:      '+919876543210', // Sender mobile phone number
    to:        '+919800012345',   // Recipient phone number
})
.then((message) => {
    console.log("✅ Message ID:", message.id); // log the ID of the sent message
})
.catch((err) => {
    console.error("❌ Error:", err.message);
});
```

To run the sample test script:
```bash
node test_send_sms.js
```

---

## 📱 How the Mobile SIM Gateway Works (Architecture)

1. **JavaScript Web & API Server (`server.js`)**:
   - Exposes REST API endpoints (`/v1/messages/send`, `/v1/messages`, `/v1/sim/status`).
   - Tracks the 200 daily free SIM SMS quota and maintains real-time delivery logs.

2. **Android Gateway Companion App (`android_app/`)**:
   - Runs on your Android mobile phone.
   - Polls pending SMS from the Node.js server.
   - Calls native Android `SmsManager.getDefault().sendTextMessage(...)` to send SMS messages directly via your inserted SIM card for **₹0.00 FREE**.

---

## 📁 Project Structure

```
FreeSMSGatewayPOC/
├── start_sms_gateway.bat         <-- Double-clickable launcher for Windows
├── launch_poc.html               <-- Standalone double-clickable browser launcher
├── server.js                     <-- JavaScript SMS Gateway Server
├── httpSmsClient.js              <-- httpSMS JavaScript SDK (Exact syntax required)
├── test_send_sms.js              <-- Sample test script matching your sir's code
├── package.json                  <-- Pre-installed dependencies
├── public/
│   ├── index.html                <-- Interactive Web Dashboard UI
│   ├── style.css                 <-- Dark Glassmorphism CSS UI
│   └── app.js                    <-- Dashboard frontend interactivity
└── android_app/                  <-- Kotlin Android SIM Companion App
    ├── AndroidManifest.xml       <-- SMS & SIM Permissions
    └── MainActivity.kt           <-- Kotlin SmsManager dispatch code
```

---

## 💰 Cost Savings Summary

| Gateway Provider | Cost Per 1,000 SMS | Monthly Cost (6,000 SMS) |
|---|---|---|
| Commercial Gateway (Twilio / AWS) | ~$10 – $20 | **$60 – $120 / month** |
| **Free SIM SMS Gateway POC** | **₹0.00 / FREE** | **$0.00 / FREE (Save $20–$50/mo!)** |
