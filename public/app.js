document.addEventListener("DOMContentLoaded", () => {
    
    const deviceCardsContainer = document.getElementById("deviceCardsContainer");
    const refreshDevicesBtn = document.getElementById("refreshDevicesBtn");
    const senderSimSelect = document.getElementById("senderSimSelect");
    const senderSimPhoneInput = document.getElementById("senderSimPhoneInput");

    const tabSendSmsBtn = document.getElementById("tabSendSmsBtn");
    const tabSendOtpBtn = document.getElementById("tabSendOtpBtn");
    const sendSmsForm = document.getElementById("sendSmsForm");
    const sendOtpForm = document.getElementById("sendOtpForm");

    const apiResponseJson = document.getElementById("apiResponseJson");
    const responseStatusBadge = document.getElementById("responseStatusBadge");

    const codeSnippetBox = document.getElementById("codeSnippetBox");
    const copyCodeBtn = document.getElementById("copyCodeBtn");

    let activeLang = "node";

    // 1. Fetch & Display Connected Global SIM Devices
    async function loadConnectedDevices() {
        try {
            const res = await fetch("/v1/devices");
            const data = await res.json();

            if (data.devices) {
                renderDeviceCards(data.devices);
                populateSenderSimDropdown(data.devices);
            }
        } catch (err) {
            console.error("Failed to load devices:", err);
            deviceCardsContainer.innerHTML = `
                <div class="device-card offline">
                    <i class="fa-solid fa-triangle-exclamation font-red"></i>
                    <div>
                        <strong>Server Connection Error</strong>
                        <p style="font-size:12px; color:#f87171;">Ensure Node.js server is running on port 3000</p>
                    </div>
                </div>
            `;
        }
    }

    function populateSenderSimDropdown(devices) {
        if (!senderSimSelect) return;
        senderSimSelect.innerHTML = `<option value="">Choose Online Phone</option>`;

        devices.forEach(dev => {
            const opt = document.createElement("option");
            opt.value = dev.phoneNumber;
            opt.textContent = `📱 ${dev.phoneNumber} (${dev.model})`;
            senderSimSelect.appendChild(opt);
        });

        // Sync dropdown selection with text input box
        senderSimSelect.addEventListener("change", () => {
            if (senderSimSelect.value) {
                senderSimPhoneInput.value = senderSimSelect.value;
            }
        });
    }

    function renderDeviceCards(devices) {
        if (!devices || devices.length === 0) {
            deviceCardsContainer.innerHTML = `<p style="color:#94a3b8; font-size:13px;">No mobile SIM devices connected currently. Please connect Android App.</p>`;
            return;
        }

        deviceCardsContainer.innerHTML = devices.map(dev => `
            <div class="device-card ${dev.status === 'ONLINE' ? 'online' : 'offline'}">
                <div class="device-card-header">
                    <span class="badge-status ${dev.status === 'ONLINE' ? 'online' : 'offline'}">
                        <i class="fa-solid fa-circle"></i> ${dev.status}
                    </span>
                    <span style="font-size:12px; color:#94a3b8;"><i class="fa-solid fa-battery-three-quarters"></i> ${dev.batteryLevel}%</span>
                </div>
                <div class="device-phone">
                    <i class="fa-solid fa-sim-card font-green"></i> ${dev.phoneNumber}
                </div>
                <div class="device-meta">
                    <span><strong>Operator:</strong> ${dev.operator}</span>
                    <span><strong>Model:</strong> ${dev.model}</span>
                </div>
                <div style="font-size:11px; color:#64748b; margin-top:6px;">
                    <i class="fa-solid fa-globe"></i> Connected via Global Network (ID: ${dev.deviceId})
                </div>
            </div>
        `).join("");
    }

    refreshDevicesBtn.addEventListener("click", loadConnectedDevices);
    loadConnectedDevices();
    setInterval(loadConnectedDevices, 5000); // Auto-refresh device status every 5s

    // 2. Tab Switchers
    tabSendSmsBtn.addEventListener("click", () => {
        tabSendSmsBtn.classList.add("active");
        tabSendOtpBtn.classList.remove("active");
        sendSmsForm.classList.remove("hidden");
        sendOtpForm.classList.add("hidden");
    });

    tabSendOtpBtn.addEventListener("click", () => {
        tabSendOtpBtn.classList.add("active");
        tabSendSmsBtn.classList.remove("active");
        sendOtpForm.classList.remove("hidden");
        sendSmsForm.classList.add("hidden");
    });

    // 3. Dispatch SMS Form Submit
    sendSmsForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const to = document.getElementById("recipientPhone").value.trim();
        const content = document.getElementById("smsContent").value.trim();
        const from = senderSimPhoneInput.value.trim() || senderSimSelect.value.trim();
        const apiKey = document.getElementById("apiKeyInput").value.trim();

        const dispatchBtn = document.getElementById("dispatchSmsBtn");
        dispatchBtn.disabled = true;
        dispatchBtn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Dispatching SMS...`;

        try {
            const res = await fetch("/v1/messages/send", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-API-KEY": apiKey
                },
                body: JSON.stringify({ to, content, from })
            });

            const responseData = await res.json();

            responseStatusBadge.classList.remove("hidden");
            responseStatusBadge.textContent = `${res.status} ${res.statusText}`;
            responseStatusBadge.style.background = res.ok ? "#065f46" : "#991b1b";

            // Display JSON Response including "content"
            apiResponseJson.textContent = JSON.stringify(responseData, null, 2);

        } catch (err) {
            apiResponseJson.textContent = JSON.stringify({ success: false, error: err.message }, null, 2);
        } finally {
            dispatchBtn.disabled = false;
            dispatchBtn.innerHTML = `<i class="fa-solid fa-bolt"></i> Send SMS Now 🚀`;
        }
    });

    // 4. Dispatch OTP Form Submit
    sendOtpForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const to = document.getElementById("otpPhone").value.trim();
        const appName = document.getElementById("appNameInput").value.trim();
        const apiKey = document.getElementById("apiKeyInput").value.trim();

        const dispatchOtpBtn = document.getElementById("dispatchOtpBtn");
        dispatchOtpBtn.disabled = true;
        dispatchOtpBtn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Generating OTP...`;

        try {
            const res = await fetch("/v1/otp/send", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-API-KEY": apiKey
                },
                body: JSON.stringify({ to, appName })
            });

            const responseData = await res.json();

            responseStatusBadge.classList.remove("hidden");
            responseStatusBadge.textContent = `${res.status} ${res.statusText}`;
            apiResponseJson.textContent = JSON.stringify(responseData, null, 2);

            if (responseData.data && responseData.data.otpCodeSent) {
                document.getElementById("verifyOtpCode").value = responseData.data.otpCodeSent;
            }

        } catch (err) {
            apiResponseJson.textContent = JSON.stringify({ success: false, error: err.message }, null, 2);
        } finally {
            dispatchOtpBtn.disabled = false;
            dispatchOtpBtn.innerHTML = `<i class="fa-solid fa-shield-halved"></i> Send Signup Verification OTP 🔒`;
        }
    });

    // Verify OTP Button
    document.getElementById("verifyOtpBtn").addEventListener("click", async () => {
        const to = document.getElementById("otpPhone").value.trim();
        const otpCode = document.getElementById("verifyOtpCode").value.trim();
        const apiKey = document.getElementById("apiKeyInput").value.trim();

        try {
            const res = await fetch("/v1/otp/verify", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-API-KEY": apiKey
                },
                body: JSON.stringify({ to, otpCode })
            });

            const responseData = await res.json();
            apiResponseJson.textContent = JSON.stringify(responseData, null, 2);
        } catch (err) {
            apiResponseJson.textContent = JSON.stringify({ success: false, error: err.message }, null, 2);
        }
    });

    // 5. Integration Code Generators for Sir
    const codeSnippets = {
        node: `// Node.js Express / Fetch Example for Sir's Signup System
const API_URL = "http://localhost:3000/api/send-sms";
const API_KEY = "demo_free_sim_key";

async function sendSignupSms(userPhone, messageText) {
    const response = await fetch(API_URL, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "X-API-KEY": API_KEY
        },
        body: JSON.stringify({
            mobileNumber: userPhone,
            message: messageText
        })
    });

    const data = await response.json();
    console.log("SMS Delivery Result:", data);
    return data;
}`,

        python: `# Python requests Example for Sir's App Backend
import requests

API_URL = "http://localhost:3000/api/send-sms"
API_KEY = "demo_free_sim_key"

def send_signup_sms(mobile_number, message):
    payload = {
        "mobileNumber": mobile_number,
        "message": message
    }
    headers = {
        "Content-Type": "application/json",
        "X-API-KEY": API_KEY
    }
    
    response = requests.post(API_URL, json=payload, headers=headers)
    return response.json()

# Usage
res = send_signup_sms("+919876543210", "Your signup OTP code is: 482910")
print(res)`,

        curl: `# cURL Command Example for Terminal / Postman
curl -X POST "http://localhost:3000/api/send-sms" \\
  -H "Content-Type: application/json" \\
  -H "X-API-KEY: demo_free_sim_key" \\
  -d '{
    "mobileNumber": "+919876543210",
    "message": "Your signup verification OTP code is 482910"
  }'`,

        php: `<?php
// PHP cURL Example for Website Signup Page
$apiUrl = "http://localhost:3000/api/send-sms";
$apiKey = "demo_free_sim_key";

$data = array(
    "mobileNumber" => "+919876543210",
    "message" => "Your signup verification OTP is 482910"
);

$ch = curl_init($apiUrl);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
curl_setopt($ch, CURLOPT_HTTPHEADER, array(
    'Content-Type: application/json',
    'X-API-KEY: ' . $apiKey
));
curl_setopt($ch, CURLOPT_POST, true);
curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($data));

$response = curl_exec($ch);
curl_close($ch);

echo $response;
?>`
    };

    function updateCodeView() {
        codeSnippetBox.textContent = codeSnippets[activeLang];
    }

    document.querySelectorAll(".code-tab-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".code-tab-btn").forEach(b => b.classList.remove("active"));
            btn.classList.add("active");
            activeLang = btn.dataset.lang;
            updateCodeView();
        });
    });

    copyCodeBtn.addEventListener("click", () => {
        navigator.clipboard.writeText(codeSnippets[activeLang]);
        copyCodeBtn.innerHTML = `<i class="fa-solid fa-check"></i> Copied!`;
        setTimeout(() => {
            copyCodeBtn.innerHTML = `<i class="fa-solid fa-copy"></i> Copy Code`;
        }, 2000);
    });

    updateCodeView();
});
