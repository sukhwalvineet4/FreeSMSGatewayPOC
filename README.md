# 📱 Free Mobile SIM SMS Gateway (Direct Real-Time Push via FCM & WebSocket)

A high-performance, self-hosted **SIM SMS Gateway Backend & Android Companion App** built with Node.js & Firebase Cloud Messaging (FCM). It turns any Android smartphone into an SMS gateway to send messages for ₹0.00 using your SIM card.

---

## ⚡ Key Improvements (No Storage & No 2s Polling)

1. **No Server SMS Storage / Memory Queues:** Messages are never stored in server arrays or pending queues. When an API call comes in, the server directly dispatches the SMS instruction to the mobile phone.
2. **Zero 2-Second Polling Loops:** Polling is eliminated to protect battery life and prevent server overhead.
3. **FCM (Firebase Cloud Messaging) High-Priority Wakeup:** Uses FCM High-Priority Data Messages so the mobile app wakes up and sends cellular SMS via SIM **even when the app is completely closed or the phone is in sleep/Doze mode**.
4. **WebSocket Instant Dispatch (Dual-Mode):** Fast direct WebSocket dispatch is used when the app is actively connected, with FCM ensuring 100% background reliability.

---

## 🚀 Quick Start

### 1. Install & Run Server
```bash
npm install
npm start
```
Default Server URL: `http://localhost:5102` (or set `PORT` in environment).

---

## 📡 API Endpoints

### 1. Send SMS (Direct Dispatch)
- **Endpoint:** `POST /v1/messages/send` (or `/api/send-sms`)
- **Headers:** `Content-Type: application/json`
- **Body:**
```json
{
  "to": "+919876543210",
  "content": "Hello! Your OTP verification code is 482910."
}
```

### 2. Send OTP (Direct Dispatch)
- **Endpoint:** `POST /v1/otp/send`
- **Body:**
```json
{
  "mobileNumber": "+919876543210",
  "appName": "AranyaSetu",
  "expiryMinutes": 5
}
```

### 3. Verify OTP
- **Endpoint:** `POST /v1/otp/verify`
- **Body:**
```json
{
  "mobileNumber": "+919876543210",
  "otpCode": "482910"
}
```

### 4. Check Connected Devices & FCM Status
- **Endpoint:** `GET /v1/devices`
- **Endpoint:** `GET /v1/fcm/status`

---

## 🔥 Firebase Setup (For Background Wakeups When App is Closed)

1. **Backend Service Account:**
   - Go to [Firebase Console](https://console.firebase.google.com/) -> Project Settings -> Service Accounts -> Generate new private key.
   - Save the JSON file as `firebase-service-account.json` in the project root directory (or set `FIREBASE_SERVICE_ACCOUNT_JSON` environment variable).

2. **Android App Config:**
   - Place your `google-services.json` inside [`android_app/app/google-services.json`](./android_app/app/google-services.json).
