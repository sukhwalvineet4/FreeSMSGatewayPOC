# 📱 Free SIM SMS Gateway - Developer API Documentation

Official REST API integration guide for incorporating **Free Mobile SIM SMS & OTP Dispatch** into your application's **User Signup System**, **OTP Verification**, and **Transactional Notifications**.

---

## ⚡ Overview & Architecture

This API connects directly to an active **Android Mobile SIM Gateway**.
- **Zero Cost**: Dispatches SMS using inserted mobile SIM card plans.
- **Global Reach**: Server and Mobile Gateway connect over the global internet (works 1000+ KM away).
- **100% Stateless & Real-Time**: Neither the server nor the mobile device stores customer database records locally. Incoming API requests trigger real-time SMS dispatch and return immediate execution results (`SUCCESS` / `FAILED`).

---

## 🔑 Authentication

All API requests must include your API Key in the `X-API-KEY` HTTP header.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `X-API-KEY` | Header String | Yes | Your unique developer API Key (Default demo key: `demo_free_sim_key`). |

---

## 🚀 API Endpoints

### 1. Send SMS (`POST /v1/messages/send`)

Sends a custom text message to any mobile phone number via the connected mobile SIM Gateway.

#### Request Headers:
```http
Content-Type: application/json
X-API-KEY: demo_free_sim_key
```

#### Request Body (JSON):
```json
{
  "to": "+919876543210",
  "content": "Hello! Your verification code for signing up is 482910."
}
```

#### Response (200 OK):
```json
{
  "success": true,
  "status": "DELIVERED",
  "messageId": "msg_98231a4f",
  "recipient": "+919876543210",
  "sentViaSim": "+919924804021",
  "content": "Hello! Your verification code for signing up is 482910.",
  "timestamp": "2026-09-17T11:05:05.000Z"
}
```

---

### 2. Send Signup OTP (`POST /v1/otp/send`)

Generates a 6-digit random verification OTP, sends it via SMS, and holds it in temporary RAM for verification.

#### Request Body (JSON):
```json
{
  "to": "+919800012345",
  "appName": "MyNewApplication",
  "expiryMinutes": 5
}
```

#### Response (200 OK):
```json
{
  "success": true,
  "status": "DELIVERED",
  "messageId": "msg_f3298a01",
  "recipient": "+919800012345",
  "sentViaSim": "+919924804021",
  "content": "Your MyNewApplication Signup verification OTP code is: 482910. Valid for 5 minutes. Do not share with anyone.",
  "otpCodeSent": "482910",
  "timestamp": "2026-09-17T11:05:05.000Z"
}
```

---

### 3. Verify Signup OTP (`POST /v1/otp/verify`)

Verifies the OTP entered by the user during signup.

#### Request Body (JSON):
```json
{
  "to": "+919800012345",
  "otpCode": "482910"
}
```

#### Response (200 OK):
```json
{
  "success": true,
  "status": "VERIFIED",
  "message": "Mobile phone number successfully verified for signup!"
}
```

---

### 4. Get Active SIM Devices (`GET /v1/devices`)

Returns all online Android mobile SIM gateways registered to the central server.

#### Response (200 OK):
```json
{
  "success": true,
  "count": 1,
  "devices": [
    {
      "deviceId": "dev_8921fa",
      "phoneNumber": "+91 9924804021",
      "operator": "Jio 4G / Vivo SIM Gateway",
      "model": "Vivo T1 5G",
      "batteryLevel": 96,
      "status": "ONLINE",
      "lastPing": "2026-09-17T11:05:05.000Z"
    }
  ]
}
```

---

## 💻 Ready-to-Use Integration Code Snippets

### Node.js / Express Integration (Backend)

```javascript
import fetch from "node-fetch";

const GATEWAY_API_URL = "http://localhost:3000/v1/messages/send";
const API_KEY = "demo_free_sim_key";

export async function sendSignupSms(userPhoneNumber, otpCode) {
    try {
        const response = await fetch(GATEWAY_API_URL, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-API-KEY": API_KEY
            },
            body: JSON.stringify({
                to: userPhoneNumber,
                content: `Your verification OTP is: ${otpCode}`
            })
        });

        const result = await response.json();
        console.log("✅ SMS Response:", result);
        return result;

    } catch (error) {
        console.error("❌ Failed to send SMS:", error.message);
        throw error;
    }
}
```

---

### Python Integration (Django / Flask / FastAPI)

```python
import requests

API_URL = "http://localhost:3000/v1/messages/send"
API_KEY = "demo_free_sim_key"

def send_signup_sms(phone_number: str, message_text: str):
    headers = {
        "Content-Type": "application/json",
        "X-API-KEY": API_KEY
    }
    payload = {
        "to": phone_number,
        "content": message_text
    }
    
    response = requests.post(API_URL, json=payload, headers=headers)
    return response.json()

# Example Usage:
res = send_signup_sms("+919876543210", "Welcome to our application! Your OTP is 482910")
print(res)
```

---

### PHP Integration (Laravel / Core PHP Website Signup)

```php
<?php
function sendSignupSms($recipientPhone, $otpCode) {
    $apiUrl = "http://localhost:3000/v1/messages/send";
    $apiKey = "demo_free_sim_key";

    $payload = array(
        "to" => $recipientPhone,
        "content" => "Your signup verification code is " . $otpCode
    );

    $ch = curl_init($apiUrl);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, array(
        'Content-Type: application/json',
        'X-API-KEY: ' . $apiKey
    ));
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($payload));

    $response = curl_exec($ch);
    curl_close($ch);

    return json_decode($response, true);
}

// Call function
$result = sendSignupSms("+919876543210", "482910");
print_r($result);
?>
```

---

### cURL Terminal Command

```bash
curl -X POST "http://localhost:3000/v1/messages/send" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: demo_free_sim_key" \
  -d '{
    "to": "+919876543210",
    "content": "Your signup verification code is 482910"
  }'
```
