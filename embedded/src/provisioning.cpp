#include "provisioning.h"

#include <DNSServer.h>
#include <WebServer.h>
#include <WiFi.h>

#include "config.h"
#include "config_store.h"
#include "sensor_data.h"
#include "system_utils.h"

static DNSServer s_dns;
static WebServer s_server(80);
static String s_networkOptions;
static String s_apSsid;

static void publishState(bool active, const char* ssid, const IPAddress& ip) {
    dataLock();
    g_sensorData.provisioning = active;
    strlcpy(g_sensorData.ap_ssid, ssid, sizeof(g_sensorData.ap_ssid));
    strlcpy(g_sensorData.ap_ip, ip.toString().c_str(), sizeof(g_sensorData.ap_ip));
    dataUnlock();
}

static String pageHtml(bool saved) {
    NetConfig net = netConfig();
    String body;
    body.reserve(2200);
    body += F("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
              "<title>AQSS setup</title><style>"
              "body{font-family:system-ui,sans-serif;margin:0;background:#0f172a;color:#e2e8f0}"
              "main{max-width:420px;margin:0 auto;padding:24px}"
              "h1{font-size:20px;margin:0 0 4px}p{color:#94a3b8;font-size:13px;margin:0 0 20px}"
              "label{display:block;font-size:12px;color:#94a3b8;margin:14px 0 4px}"
              "input{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #334155;"
              "background:#1e293b;color:#e2e8f0;font-size:15px}"
              "button{width:100%;margin-top:22px;padding:12px;border:0;border-radius:8px;background:#22c55e;"
              "color:#052e16;font-size:15px;font-weight:600}"
              ".ok{background:#14532d;color:#bbf7d0;padding:10px;border-radius:8px;margin-bottom:16px}"
              "h2{font-size:13px;color:#64748b;text-transform:uppercase;letter-spacing:.08em;margin:26px 0 0}"
              "</style><main><h1>AQSS node setup</h1>"
              "<p>Connect this node to Wi-Fi and point it at the MQTT broker.</p>");
    if (saved) {
        body += F("<div class=ok>Saved. The node is rebooting…</div>");
    }
    body += F("<form method=post action=/save>"
              "<h2>Wi-Fi</h2>"
              "<label>Network name (SSID)</label><input name=ssid list=nets required value=\"");
    body += net.wifi_ssid;
    body += F("\"><datalist id=nets>");
    body += s_networkOptions;
    body += F("</datalist>"
              "<label>Password</label><input name=wpass type=password value=\"");
    body += net.wifi_pass;
    body += F("\"><h2>MQTT broker</h2>"
              "<p style=\"font-size:12px;color:#94a3b8\">Fixed in this build: ");
    body += MQTT_SERVER;
    body += F(":");
    body += String(MQTT_PORT);
    body += F(" as ");
    body += MQTT_USER;
    body += F("</p><label>Broker password</label><input name=mpass type=password value=\"");
    body += net.mqtt_pass;
    body += F("\"><button>Save and reboot</button></form></main>");
    return body;
}

static void handleRoot() {
    s_server.send(200, "text/html", pageHtml(false));
}

static void handleSave() {
    String ssid = s_server.arg("ssid");
    String mpass = s_server.arg("mpass");
    ssid.trim();
    if (ssid.isEmpty()) {
        s_server.send(400, "text/html", pageHtml(false));
        return;
    }
    netSet(ssid.c_str(), s_server.arg("wpass").c_str(), mpass.c_str());

    AQSS_LOG("provisioning: saved ssid='%s' broker_password_len=%u (target %s:%d)",
             ssid.c_str(), (unsigned)mpass.length(), MQTT_SERVER, MQTT_PORT);
    s_server.send(200, "text/html", pageHtml(true));
    vTaskDelay(pdMS_TO_TICKS(1200));
    ESP.restart();
}

void provisioningRun(bool retryStation) {
    s_apSsid = String(AP_SSID_PREFIX) + String((uint32_t)(ESP.getEfuseMac() & 0xFFFF), HEX);

    // AP + STA at once: the setup network stays available while the node keeps retrying the
    // configured Wi-Fi in the background.
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(s_apSsid.c_str(), AP_PASSWORD);
    const IPAddress ip = WiFi.softAPIP();

    // Scan once so the form can offer the visible networks.
    const int found = WiFi.scanNetworks();
    for (int i = 0; i < found; i++) {
        s_networkOptions += "<option value=\"" + WiFi.SSID(i) + "\">";
    }

    s_dns.start(53, "*", ip);
    s_server.on("/", handleRoot);
    s_server.on("/save", handleSave);
    s_server.onNotFound(handleRoot);
    s_server.begin();

    NetConfig net = netConfig();
    dataLock();
    strlcpy(g_sensorData.wifi_target, net.wifi_ssid, sizeof(g_sensorData.wifi_target));
    dataUnlock();

    publishState(true, s_apSsid.c_str(), ip);
    AQSS_LOG("provisioning: join '%s' (pass %s) then open http://%s", s_apSsid.c_str(), AP_PASSWORD,
             ip.toString().c_str());
    AQSS_LOG("provisioning: stays open until saved (network %s)",
             retryStation ? "is retried in the background" : "must be entered");

    // Serve the form and keep trying the configured network; the AP closes the moment it joins.
    uint32_t lastAttemptMs = 0;
    for (;;) {
        aqssWdtFeed();
        s_server.handleClient();
        s_dns.processNextRequest();

        if (retryStation && (uint32_t)(millis() - lastAttemptMs) > STA_RETRY_MS) {
            lastAttemptMs = millis();
            net = netConfig();
            if (net.wifi_ssid[0] != '\0' && !WiFi.isConnected()) {
                WiFi.begin(net.wifi_ssid, net.wifi_pass);
            }
        }
        if (retryStation && WiFi.isConnected() && WiFi.localIP() != IPAddress(0, 0, 0, 0)) {
            AQSS_LOG("provisioning: station joined %s, closing the AP",
                     WiFi.localIP().toString().c_str());
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }

    s_server.stop();
    s_dns.stop();
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_STA);
    publishState(false, "", IPAddress());
}
