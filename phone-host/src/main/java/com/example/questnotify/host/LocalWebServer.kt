package com.example.questnotify.host

import android.util.Log
import com.example.questnotify.shared.ProtocolConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedBundle(
    val salt: String,
    val iv: String,
    val ciphertext: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("salt", salt)
        put("iv", iv)
        put("ciphertext", ciphertext)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): EncryptedBundle = EncryptedBundle(
            salt = obj.optString("salt", ""),
            iv = obj.optString("iv", ""),
            ciphertext = obj.optString("ciphertext", "")
        )
    }
}

object WebCryptoHelper {
    fun encrypt(plaintext: String, pin: String): EncryptedBundle {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val keyBytes = md.digest(pin.trim().toByteArray(StandardCharsets.UTF_8))
        val key = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertextWithTag = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        return EncryptedBundle(
            salt = bytesToHex(salt),
            iv = bytesToHex(iv),
            ciphertext = bytesToHex(ciphertextWithTag)
        )
    }

    fun decrypt(bundle: EncryptedBundle, pin: String): String {
        val salt = hexToBytes(bundle.salt)
        val iv = hexToBytes(bundle.iv)
        val ciphertextWithTag = hexToBytes(bundle.ciphertext)

        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val keyBytes = md.digest(pin.trim().toByteArray(StandardCharsets.UTF_8))
        val key = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plaintextBytes = cipher.doFinal(ciphertextWithTag)
        return String(plaintextBytes, StandardCharsets.UTF_8)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

class LocalWebServer(
    private val onActionDismiss: (String) -> Unit
) {
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(port: Int = ProtocolConstants.HTTP_WEB_PORT) {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            Log.d("LocalWebServer", "Local web server listening on port $port")

            acceptJob = serverScope.launch {
                while (isActive && isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalWebServer", "Failed to start LocalWebServer on port $port", e)
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        Log.d("LocalWebServer", "Local web server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            var contentLength = 0
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
                val lower = headerLine.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                String(buf, 0, readTotal)
            } else ""

            when {
                method == "OPTIONS" -> {
                    sendCorsPreflight(writer)
                }
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    sendHtmlResponse(writer, WebDashboardHtml.CONTENT)
                }
                method == "GET" && path == "/api/notifications" -> {
                    handleGetNotifications(writer)
                }
                method == "POST" && path == "/api/action" -> {
                    handlePostAction(writer, body)
                }
                else -> {
                    sendResponse(writer, 404, "Not Found", "text/plain", "Not Found")
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleGetNotifications(writer: BufferedWriter) {
        val currentPin = HostBridgeRepository.getValidPairingPin()
        val history = HostBridgeRepository.forwardedHistory.value

        val jsonArray = JSONArray()
        for (item in history) {
            jsonArray.put(item.toJsonObject())
        }
        val plaintext = jsonArray.toString()

        val encrypted = WebCryptoHelper.encrypt(plaintext, currentPin)
        val responseJson = encrypted.toJsonObject().toString()
        sendResponse(writer, 200, "OK", "application/json; charset=utf-8", responseJson)
    }

    private fun handlePostAction(writer: BufferedWriter, body: String) {
        val currentPin = HostBridgeRepository.getValidPairingPin()
        try {
            val reqJson = JSONObject(body)
            val bundle = EncryptedBundle.fromJsonObject(reqJson)
            val decryptedPlaintext = WebCryptoHelper.decrypt(bundle, currentPin)

            val actionObj = JSONObject(decryptedPlaintext)
            val action = actionObj.optString("action", "")
            val key = actionObj.optString("key", "")

            when (action) {
                "dismiss" -> {
                    if (key.isNotEmpty()) {
                        onActionDismiss(key)
                    }
                }
                "clear_all" -> {
                    HostBridgeRepository.clearHistory()
                }
            }

            val successJson = JSONObject().apply { put("success", true) }.toString()
            sendResponse(writer, 200, "OK", "application/json; charset=utf-8", successJson)
        } catch (e: Exception) {
            val errJson = JSONObject().apply {
                put("error", "Decryption failed or invalid PIN")
                put("success", false)
            }.toString()
            sendResponse(writer, 401, "Unauthorized", "application/json; charset=utf-8", errJson)
        }
    }

    private fun sendCorsPreflight(writer: BufferedWriter) {
        writer.write("HTTP/1.1 204 No Content\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.write("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        writer.write("Access-Control-Max-Age: 86400\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("\r\n")
        writer.flush()
    }

    private fun sendHtmlResponse(writer: BufferedWriter, html: String) {
        sendResponse(writer, 200, "OK", "text/html; charset=utf-8", html)
    }

    private fun sendResponse(
        writer: BufferedWriter,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $statusCode $statusText\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bodyBytes.size}\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        writer.write("Cache-Control: no-store, no-cache, must-revalidate\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(body)
        writer.flush()
    }
}

object WebDashboardHtml {
    val CONTENT: String = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Quest Notify — Web Dashboard</title>
  <style>
    :root {
      --bg: #0A0E17;
      --surface: #141B26;
      --surface-card: #1A2232;
      --border: #233044;
      --primary: #4FC3F7;
      --accent: #81C784;
      --danger: #EF5350;
      --text: #ECEFF1;
      --text-muted: #90A4AE;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background-color: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }
    header {
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      padding: 14px 20px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      position: sticky;
      top: 0;
      z-index: 100;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .brand-icon {
      width: 34px;
      height: 34px;
      background: linear-gradient(135deg, #0288D1, #00E5FF);
      border-radius: 9px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: bold;
      color: #fff;
      font-size: 18px;
    }
    .brand-title {
      font-size: 17px;
      font-weight: 700;
      color: #fff;
    }
    .badge-secure {
      font-size: 11px;
      font-weight: 600;
      background: rgba(129, 199, 132, 0.15);
      color: var(--accent);
      border: 1px solid rgba(129, 199, 132, 0.35);
      padding: 3px 8px;
      border-radius: 20px;
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
    .header-actions {
      display: flex;
      gap: 8px;
    }
    button {
      cursor: pointer;
      font-family: inherit;
      border: none;
      outline: none;
      border-radius: 8px;
      transition: all 0.15s ease;
    }
    .btn-secondary {
      background: var(--surface-card);
      color: var(--text-muted);
      border: 1px solid var(--border);
      padding: 6px 12px;
      font-size: 13px;
      font-weight: 500;
    }
    .btn-secondary:hover {
      background: var(--border);
      color: #fff;
    }
    .btn-primary {
      background: var(--primary);
      color: #0A0E17;
      padding: 10px 20px;
      font-weight: 700;
      font-size: 14px;
    }
    .btn-primary:hover {
      filter: brightness(1.1);
    }
    .btn-dismiss {
      background: rgba(239, 83, 80, 0.15);
      color: #EF5350;
      border: 1px solid rgba(239, 83, 80, 0.4);
      padding: 6px 12px;
      font-size: 12px;
      font-weight: 600;
      border-radius: 6px;
    }
    .btn-dismiss:hover {
      background: #EF5350;
      color: #fff;
    }
    main {
      flex: 1;
      max-width: 760px;
      width: 100%;
      margin: 0 auto;
      padding: 20px;
    }
    /* PIN Modal */
    #pin-screen {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 60vh;
      text-align: center;
    }
    .pin-box {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 32px 24px;
      max-width: 400px;
      width: 100%;
      box-shadow: 0 12px 32px rgba(0,0,0,0.5);
    }
    .pin-shield {
      font-size: 42px;
      margin-bottom: 12px;
    }
    .pin-title {
      font-size: 20px;
      font-weight: 700;
      margin-bottom: 8px;
    }
    .pin-desc {
      font-size: 13px;
      color: var(--text-muted);
      line-height: 1.5;
      margin-bottom: 24px;
    }
    .pin-input {
      background: var(--bg);
      border: 2px solid var(--border);
      border-radius: 10px;
      color: var(--primary);
      font-size: 28px;
      font-weight: 800;
      letter-spacing: 12px;
      text-align: center;
      width: 180px;
      padding: 10px 0;
      margin: 0 auto 20px auto;
      display: block;
    }
    .pin-input:focus {
      border-color: var(--primary);
      outline: none;
    }
    .pin-error {
      color: var(--danger);
      font-size: 13px;
      font-weight: 500;
      margin-top: 12px;
      display: none;
    }
    /* Notification Feed */
    #feed-screen {
      display: none;
    }
    .feed-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
    }
    .feed-count {
      font-size: 14px;
      color: var(--text-muted);
      font-weight: 500;
    }
    .cards-container {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .card {
      background: var(--surface-card);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 16px;
      display: flex;
      gap: 14px;
      transition: transform 0.2s ease, opacity 0.2s ease;
    }
    .card.dismissing {
      opacity: 0;
      transform: translateX(30px);
    }
    .card-icon {
      width: 44px;
      height: 44px;
      border-radius: 10px;
      flex-shrink: 0;
      background: #263238;
      display: flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      font-weight: bold;
      color: var(--primary);
    }
    .card-icon img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    .card-content {
      flex: 1;
      min-width: 0;
    }
    .card-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 4px;
    }
    .card-app {
      font-size: 13px;
      font-weight: 600;
      color: var(--primary);
      text-transform: capitalize;
    }
    .card-time {
      font-size: 11px;
      color: var(--text-muted);
    }
    .card-title {
      font-size: 15px;
      font-weight: 700;
      color: #fff;
      margin-bottom: 4px;
      word-break: break-word;
    }
    .card-text {
      font-size: 13px;
      color: #CFD8DC;
      line-height: 1.45;
      word-break: break-word;
      margin-bottom: 8px;
    }
    .card-footer {
      display: flex;
      justify-content: flex-end;
      align-items: center;
      gap: 8px;
    }
    .empty-state {
      text-align: center;
      padding: 60px 20px;
      color: var(--text-muted);
    }
    .empty-icon {
      font-size: 48px;
      margin-bottom: 12px;
      opacity: 0.6;
    }
    .empty-text {
      font-size: 15px;
      font-weight: 500;
    }
  </style>
</head>
<body>

  <header>
    <div class="brand">
      <div class="brand-icon">Q</div>
      <div>
        <div class="brand-title">Quest Notify Webview</div>
      </div>
      <div class="badge-secure">🔒 AES-GCM Encrypted</div>
    </div>
    <div class="header-actions" id="header-actions" style="display: none;">
      <button class="btn-secondary" onclick="fetchNotifications()">↻ Refresh</button>
      <button class="btn-secondary" onclick="clearAllNotifications()">Clear All</button>
      <button class="btn-secondary" onclick="lockDashboard()">Lock</button>
    </div>
  </header>

  <main>
    <!-- PIN Screen -->
    <div id="pin-screen">
      <div class="pin-box">
        <div class="pin-shield">🔐</div>
        <h2 class="pin-title">Enter 4-Digit Security PIN</h2>
        <p class="pin-desc">
          Notifications are protected with hardware-accelerated AES-GCM encryption.
          Enter the PIN displayed in Quest Notify Host on your phone to decrypt and manage alerts.
        </p>
        <input type="password" id="pin-input" class="pin-input" maxlength="4" placeholder="••••" autofocus inputmode="numeric">
        <div>
          <button class="btn-primary" onclick="submitPin()">Unlock Dashboard</button>
        </div>
        <div class="pin-error" id="pin-error">Incorrect PIN or decryption failed.</div>
      </div>
    </div>

    <!-- Feed Screen -->
    <div id="feed-screen">
      <div class="feed-header">
        <div class="feed-count" id="feed-count">0 Active Notifications</div>
      </div>
      <div class="cards-container" id="cards-container">
        <!-- Rendered items -->
      </div>
    </div>
  </main>

  <script>
    let activePin = sessionStorage.getItem("qnotify_pin") || "";
    let pollInterval = null;

    function hexToBytes(hex) {
      const bytes = new Uint8Array(hex.length / 2);
      for (let i = 0; i < bytes.length; i++) {
        bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
      }
      return bytes;
    }

    function bytesToHex(bytes) {
      return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
    }

    async function deriveAesKey(saltBytes, pin) {
      const enc = new TextEncoder();
      const pinBytes = enc.encode(pin.trim());
      const combined = new Uint8Array(saltBytes.length + pinBytes.length);
      combined.set(saltBytes);
      combined.set(pinBytes, saltBytes.length);
      const hash = await crypto.subtle.digest('SHA-256', combined);
      return await crypto.subtle.importKey('raw', hash, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
    }

    async function decryptPayload(bundle, pin) {
      const salt = hexToBytes(bundle.salt);
      const iv = hexToBytes(bundle.iv);
      const ct = hexToBytes(bundle.ciphertext);
      const key = await deriveAesKey(salt, pin);
      const decryptedBuffer = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: iv, tagLength: 128 },
        key,
        ct
      );
      const dec = new TextDecoder();
      return JSON.parse(dec.decode(decryptedBuffer));
    }

    async function encryptPayload(dataObj, pin) {
      const enc = new TextEncoder();
      const plaintext = enc.encode(JSON.stringify(dataObj));
      const salt = new Uint8Array(16);
      crypto.getRandomValues(salt);
      const iv = new Uint8Array(12);
      crypto.getRandomValues(iv);
      const key = await deriveAesKey(salt, pin);
      const ciphertextBuffer = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv: iv, tagLength: 128 },
        key,
        plaintext
      );
      return {
        salt: bytesToHex(salt),
        iv: bytesToHex(iv),
        ciphertext: bytesToHex(new Uint8Array(ciphertextBuffer))
      };
    }

    async function submitPin() {
      const pin = document.getElementById("pin-input").value.trim();
      const errorDiv = document.getElementById("pin-error");
      errorDiv.style.display = "none";

      if (pin.length !== 4) {
        errorDiv.textContent = "Please enter a 4-digit PIN.";
        errorDiv.style.display = "block";
        return;
      }

      try {
        const resp = await fetch("/api/notifications");
        if (!resp.ok) throw new Error("Server error " + resp.status);
        const encryptedBundle = await resp.json();
        const decryptedList = await decryptPayload(encryptedBundle, pin);

        // Success! Save PIN
        activePin = pin;
        sessionStorage.setItem("qnotify_pin", pin);
        showDashboard();
        renderNotifications(decryptedList);
      } catch (err) {
        errorDiv.textContent = "Incorrect PIN. Decryption failed.";
        errorDiv.style.display = "block";
      }
    }

    function showDashboard() {
      document.getElementById("pin-screen").style.display = "none";
      document.getElementById("feed-screen").style.display = "block";
      document.getElementById("header-actions").style.display = "flex";
      startPolling();
    }

    function lockDashboard() {
      activePin = "";
      sessionStorage.removeItem("qnotify_pin");
      stopPolling();
      document.getElementById("pin-input").value = "";
      document.getElementById("pin-error").style.display = "none";
      document.getElementById("feed-screen").style.display = "none";
      document.getElementById("header-actions").style.display = "none";
      document.getElementById("pin-screen").style.display = "flex";
    }

    async function fetchNotifications() {
      if (!activePin) return;
      try {
        const resp = await fetch("/api/notifications");
        if (!resp.ok) return;
        const encryptedBundle = await resp.json();
        const list = await decryptPayload(encryptedBundle, activePin);
        renderNotifications(list);
      } catch (err) {
        // PIN might have expired or changed
        console.warn("Decryption failed:", err);
      }
    }

    function startPolling() {
      stopPolling();
      pollInterval = setInterval(fetchNotifications, 2500);
    }

    function stopPolling() {
      if (pollInterval) clearInterval(pollInterval);
      pollInterval = null;
    }

    function renderNotifications(items) {
      const countEl = document.getElementById("feed-count");
      const container = document.getElementById("cards-container");
      countEl.textContent = items.length + " Active Notification" + (items.length === 1 ? "" : "s");

      if (items.length === 0) {
        container.innerHTML = `
          <div class="empty-state">
            <div class="empty-icon">🔕</div>
            <div class="empty-text">No active forwarded notifications</div>
            <p style="font-size: 13px; margin-top: 6px;">Incoming phone alerts will appear here in real time.</p>
          </div>
        `;
        return;
      }

      container.innerHTML = "";
      items.forEach(item => {
        const card = document.createElement("div");
        card.className = "card";
        card.id = "card-" + btoa(item.key).replace(/=/g, "");

        const iconHtml = item.iconBase64 && item.iconBase64.length > 20
          ? `<img src="data:image/png;base64,` + item.iconBase64 + `" alt="icon" />`
          : (item.appName ? item.appName[0].toUpperCase() : "A");

        const dateStr = new Date(item.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

        card.innerHTML = `
          <div class="card-icon">` + iconHtml + `</div>
          <div class="card-content">
            <div class="card-top">
              <span class="card-app">` + escapeHtml(item.appName) + `</span>
              <span class="card-time">` + dateStr + `</span>
            </div>
            <div class="card-title">` + escapeHtml(item.title) + `</div>
            ` + (item.text ? `<div class="card-text">` + escapeHtml(item.text) + `</div>` : "") + `
            <div class="card-footer">
              <button class="btn-dismiss" onclick="dismissNotification('` + escapeJsString(item.key) + `')">
                ✓ Dismiss from Phone
              </button>
            </div>
          </div>
        `;
        container.appendChild(card);
      });
    }

    async function dismissNotification(key) {
      const cardEl = document.getElementById("card-" + btoa(key).replace(/=/g, ""));
      if (cardEl) cardEl.classList.add("dismissing");

      try {
        const payload = await encryptPayload({ action: "dismiss", key: key }, activePin);
        const resp = await fetch("/api/action", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });
        if (resp.ok) {
          setTimeout(fetchNotifications, 200);
        }
      } catch (err) {
        console.error("Failed to dismiss:", err);
      }
    }

    async function clearAllNotifications() {
      if (!confirm("Clear all active notifications from the phone?")) return;
      try {
        const payload = await encryptPayload({ action: "clear_all" }, activePin);
        await fetch("/api/action", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });
        fetchNotifications();
      } catch (err) {
        console.error("Failed to clear all:", err);
      }
    }

    function escapeHtml(str) {
      if (!str) return "";
      return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
    }

    function escapeJsString(str) {
      return (str || "").replace(/\\/g, "\\\\").replace(/'/g, "\\'");
    }

    // Auto-login if session pin exists
    document.getElementById("pin-input").addEventListener("keydown", function(e) {
      if (e.key === "Enter") submitPin();
    });

    if (activePin && activePin.length === 4) {
      document.getElementById("pin-input").value = activePin;
      submitPin();
    }
  </script>
</body>
</html>
    """.trimIndent()
}

