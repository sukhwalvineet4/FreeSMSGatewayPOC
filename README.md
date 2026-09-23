# 📱 Free Mobile SIM SMS Gateway API Server (Firebase FCM Powered)

A lightweight, robust **SIM SMS Gateway Backend** built with Node.js, Express, and **Firebase Cloud Messaging (FCM)**. It enables your Android smartphone to act as an SMS gateway to send cellular messages for ₹0.00 using your SIM card, with reliable background delivery even when the phone is locked, asleep (Doze mode), or the app is killed.

---

## ⚡ Architecture: Firebase FCM + REST Callback

```
  ┌───────────────────────┐
  │   Node.js Server      │
  │   (Job Created)       │
  └───────────┬───────────┘
              │
              │ High-Priority Data Push (FCM)
              ▼
  ┌───────────────────────┐
  │   Firebase FCM        │
  └───────────┬───────────┘
              │
              │ Wakes phone & delivers payload
              ▼
  ┌───────────────────────┐
  │   Android Phone App   │
  │   (FCMService)        │
  │           ↓           │
  │   SmsManager.send()   │
  └───────────┬───────────┘
              │
              │ POST /v1/jobs/:jobId/result
              ▼
  ┌───────────────────────┐
  │   Node.js Server      │
  │   status = SENT       │
  └───────────────────────┘
```

---

## 🚀 Setup Guide

### 1. Firebase Project Setup (One-time)
1. Go to the [Firebase Console](https://console.firebase.google.com/) and create a project.
2. Add an Android app with package name: **`com.freesmsgateway.poc`**.
3. Download **`google-services.json`** and place it in:
   ```
   android_app/app/google-services.json
   ```
4. In Firebase Console, go to **Project Settings** -> **Service Accounts** -> click **Generate new private key**.
5. Save the downloaded JSON file as **`serviceAccountKey.json`** in the project root:
   ```
   FreeSMSGatewayPOC/serviceAccountKey.json
   ```

### 2. Run the Node.js Server
```bash
npm install
npm start
```
Server runs by default on: `http://localhost:5102` (or set `PORT` environment variable).

### 3. Connect Android Phone
1. Build or install the app on your Android phone.
2. Open the app; it will automatically fetch its FCM Device Token.
3. Enter your Node Server URL (e.g., `http://192.168.1.100:5102` or your public domain) and tap **REGISTER GATEWAY**.

---

## 📡 API Endpoints

### 1. Send SMS
- **Endpoint:** `POST /v1/messages/send`
- **Headers:** `Content-Type: application/json`
- **Body:**
```json
{
  "to": "+919913161524",
  "content": "Hello! Your OTP verification code is 482910."
}
```

### 2. Check Connected Gateway Devices
- **Endpoint:** `GET /v1/devices`

### 3. Check SMS Jobs
- **Endpoint:** `GET /v1/jobs`
- **Endpoint:** `GET /v1/jobs/:jobId`

### 4. Send OTP
- **Endpoint:** `POST /v1/otp/send`
- **Body:**
```json
{
  "mobileNumber": "+919913161524",
  "appName": "AranyaSetu",
  "expiryMinutes": 5
}
```

### 5. Verify OTP
- **Endpoint:** `POST /v1/otp/verify`
- **Body:**
```json
{
  "mobileNumber": "+919913161524",
  "otpCode": "482910"
}
```
