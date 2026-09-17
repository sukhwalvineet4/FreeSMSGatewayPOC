const API_URL = "http://localhost:3000/api/send-sms";
const API_KEY = "demo_free_sim_key";

console.log("🚀 Testing Sir's Custom API Payload (mobileNumber & message)...");

async function testSirApi() {
    try {
        const response = await fetch(API_URL, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-API-KEY": API_KEY
            },
            body: JSON.stringify({
                mobileNumber: "+919876543210",
                message: "Hello Sir! Your user signup OTP code is 482910. Sent via mobile SIM in < 10 seconds!"
            })
        });

        const data = await response.json();

        console.log("=================================================");
        console.log("✅ SIR'S API RESPONSE RECEIVED IN < 10 SECONDS!");
        console.log("=================================================");
        console.log("API JSON Response:", JSON.stringify(data, null, 2));
        console.log("=================================================");

    } catch (err) {
        console.error("❌ API Test Error:", err.message);
    }
}

testSirApi();
