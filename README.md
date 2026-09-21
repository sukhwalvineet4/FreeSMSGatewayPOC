# 📱 Free Mobile SIM SMS Gateway API Server

A lightweight, self-hosted **SIM SMS Gateway Backend** built with Node.js & Express. It turns any Android smartphone into an SMS gateway to send messages for ₹0.00 using your SIM card.

---

## 🚀 Quick Start

### 1. Install & Run
```bash
npm install
npm start
```
Server runs by default on: `http://localhost:3000`

---

## 📡 API Endpoints

### 1. Send SMS
- **Endpoint:** `POST /v1/messages/send` (or `/api/send-sms`)
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

### 3. Send OTP
- **Endpoint:** `POST /v1/otp/send`
- **Body:**
```json
{
  "mobileNumber": "+919913161524",
  "appName": "AranyaSetu",
  "expiryMinutes": 5
}
```

### 4. Verify OTP
- **Endpoint:** `POST /v1/otp/verify`
- **Body:**
```json
{
  "mobileNumber": "+919913161524",
  "otpCode": "482910"
}
```

---

## 📱 Android Companion App
The companion APK is located at [`FreeSMSGateway.apk`](./FreeSMSGateway.apk).
Install this APK on your Android phone with the SIM card inserted, set the server URL to your backend endpoint, and tap **Connect**.
