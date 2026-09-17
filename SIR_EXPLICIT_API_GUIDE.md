# 📱 API Integration Guide for Sir's Signup System

Official documentation for integrating the **Free SIM SMS Gateway API** into your Application Signup & Notification pipeline.

---

## ⚡ API Specifications

- **Endpoint URL**: `POST http://<YOUR_SERVER_IP>:3000/api/send-sms`  *(or `/v1/messages/send`)*
- **Header**: `X-API-KEY: demo_free_sim_key`
- **Content-Type**: `application/json`
- **Delivery Guarantee**: Dispatch completed via mobile SIM card in **< 10 seconds**.

---

## 📩 Request Format (Payload Sent by Sir's Server)

When a user signs up on your website/app, send a HTTP POST request with this JSON body:

```json
{
  "mobileNumber": "+919876543210",
  "message": "Welcome to our Application! Your signup verification OTP code is 482910."
}
```

---

## 📤 Response Format (Returned to Sir's Server)

```json
{
  "success": true,
  "status": "DELIVERED",
  "messageId": "msg_haacwsa0",
  "mobileNumber": "+919876543210",
  "message": "Welcome to our Application! Your signup verification OTP code is 482910.",
  "sentViaSim": "+918155858353",
  "deliveryTime": "1.2 seconds",
  "timestamp": "2026-09-17T05:43:00.593Z"
}
```

---

## 💻 Code Examples for Sir's Project

### 1. Node.js (Express / Async API Call)

```javascript
async function sendSignupSmsToUser(userPhone, messageText) {
    const response = await fetch("http://localhost:3000/api/send-sms", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "X-API-KEY": "demo_free_sim_key"
        },
        body: JSON.stringify({
            mobileNumber: userPhone,
            message: messageText
        })
    });

    const result = await response.json();
    console.log("SMS Delivery Result:", result);
    return result;
}

// Example Usage on New User Signup:
sendSignupSmsToUser("+919876543210", "Your signup OTP is 482910");
```

---

### 2. Python (Django / Flask / FastAPI)

```python
import requests

def send_signup_sms(mobile_number: str, message: str):
    url = "http://localhost:3000/api/send-sms"
    headers = {
        "Content-Type": "application/json",
        "X-API-KEY": "demo_free_sim_key"
    }
    payload = {
        "mobileNumber": mobile_number,
        "message": message
    }
    
    response = requests.post(url, json=payload, headers=headers)
    return response.json()

# Example Call:
res = send_signup_sms("+919876543210", "Your signup OTP code is 482910")
print(res)
```

---

### 3. PHP (Laravel / Core PHP Website Signup)

```php
<?php
function sendSignupSms($mobileNumber, $message) {
    $apiUrl = "http://localhost:3000/api/send-sms";
    $apiKey = "demo_free_sim_key";

    $payload = array(
        "mobileNumber" => $mobileNumber,
        "message" => $message
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

// Usage:
$result = sendSignupSms("+919876543210", "Your signup OTP code is 482910");
print_r($result);
?>
```

---

### 4. cURL (Terminal / Postman)

```bash
curl -X POST "http://localhost:3000/api/send-sms" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: demo_free_sim_key" \
  -d '{
    "mobileNumber": "+919876543210",
    "message": "Your signup verification code is 482910"
  }'
```
