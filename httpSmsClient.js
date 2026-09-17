/**
 * Free SIM SMS Gateway - Developer JavaScript SDK Client
 * Real-time Stateless Gateway Client matching standard SMS SDK syntax
 */

class HttpSms {
    constructor(apiKey = "demo_free_sim_key", baseUrl = "http://localhost:3000") {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replace(/\/+$/, "");

        this.messages = {
            postSend: async ({ to, content, from }) => {
                const url = `${this.baseUrl}/v1/messages/send`;
                const response = await fetch(url, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        "X-API-KEY": this.apiKey
                    },
                    body: JSON.stringify({ to, content, from })
                });

                if (!response.ok) {
                    const errObj = await response.json();
                    throw new Error(errObj.error || `HTTP ${response.status}: Failed to send SMS`);
                }

                return await response.json();
            }
        };

        this.otp = {
            send: async ({ to, appName }) => {
                const url = `${this.baseUrl}/v1/otp/send`;
                const response = await fetch(url, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        "X-API-KEY": this.apiKey
                    },
                    body: JSON.stringify({ to, appName })
                });
                return await response.json();
            },
            verify: async ({ to, otpCode }) => {
                const url = `${this.baseUrl}/v1/otp/verify`;
                const response = await fetch(url, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        "X-API-KEY": this.apiKey
                    },
                    body: JSON.stringify({ to, otpCode })
                });
                return await response.json();
            }
        };
    }
}

export default HttpSms;
