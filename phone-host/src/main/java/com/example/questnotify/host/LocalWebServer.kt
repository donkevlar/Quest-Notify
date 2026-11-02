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

data class EncryptedBundle(
    val salt: String,
    val iv: String,
    val ciphertext: String,
    val tag: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("salt", salt)
        put("iv", iv)
        put("ciphertext", ciphertext)
        put("tag", tag)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): EncryptedBundle = EncryptedBundle(
            salt = obj.optString("salt", ""),
            iv = obj.optString("iv", ""),
            ciphertext = obj.optString("ciphertext", ""),
            tag = obj.optString("tag", "")
        )
    }
}

/**
 * Universal CTR stream cipher with SHA-256 MAC authentication.
 * 100% portable across Java and pure JavaScript without requiring
 * secure context (HTTPS) or window.crypto.subtle in local network browsers.
 */
object WebCryptoHelper {
    fun encrypt(plaintext: String, pin: String): EncryptedBundle {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(8).also { SecureRandom().nextBytes(it) }

        val md = MessageDigest.getInstance("SHA-256")
        val key = md.digest(salt + pin.trim().toByteArray(StandardCharsets.UTF_8))

        val ptBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        val ctBytes = cryptCtr(ptBytes, key, iv)

        val mdTag = MessageDigest.getInstance("SHA-256")
        mdTag.update(key)
        val tagBytes = mdTag.digest(ctBytes)

        return EncryptedBundle(
            salt = bytesToHex(salt),
            iv = bytesToHex(iv),
            ciphertext = bytesToHex(ctBytes),
            tag = bytesToHex(tagBytes)
        )
    }

    fun decrypt(bundle: EncryptedBundle, pin: String): String {
        val salt = hexToBytes(bundle.salt)
        val iv = hexToBytes(bundle.iv)
        val ctBytes = hexToBytes(bundle.ciphertext)

        val md = MessageDigest.getInstance("SHA-256")
        val key = md.digest(salt + pin.trim().toByteArray(StandardCharsets.UTF_8))

        val mdTag = MessageDigest.getInstance("SHA-256")
        mdTag.update(key)
        val expectedTag = bytesToHex(mdTag.digest(ctBytes))

        if (expectedTag != bundle.tag) {
            throw SecurityException("Authentication tag mismatch or invalid PIN")
        }

        val ptBytes = cryptCtr(ctBytes, key, iv)
        return String(ptBytes, StandardCharsets.UTF_8)
    }

    private fun cryptCtr(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var blockIndex = 0
        var offset = 0
        val md = MessageDigest.getInstance("SHA-256")
        while (offset < data.size) {
            val counterBytes = byteArrayOf(
                ((blockIndex ushr 24) and 0xFF).toByte(),
                ((blockIndex ushr 16) and 0xFF).toByte(),
                ((blockIndex ushr 8) and 0xFF).toByte(),
                (blockIndex and 0xFF).toByte()
            )
            md.reset()
            md.update(key)
            md.update(iv)
            val blockHash = md.digest(counterBytes)
            var i = 0
            while (i < 32 && offset < data.size) {
                out[offset] = (data[offset].toInt() xor blockHash[i].toInt()).toByte()
                i++
                offset++
            }
            blockIndex++
        }
        return out
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
      padding: 10px 24px;
      font-weight: 700;
      font-size: 15px;
      border-radius: 8px;
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
    /* PIN Screen */
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
      max-width: 420px;
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
      font-size: 32px;
      font-weight: 800;
      letter-spacing: 12px;
      text-align: center;
      width: 200px;
      padding: 10px 0;
      margin: 0 auto 16px auto;
      display: block;
    }
    .pin-input:focus {
      border-color: var(--primary);
      outline: none;
    }
    .pin-error {
      color: var(--danger);
      font-size: 13px;
      font-weight: 600;
      margin-top: 14px;
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
      <div class="badge-secure">🔒 PIN-Encrypted</div>
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
          Notifications are encrypted with your 4-digit PIN using SHA-256 CTR encryption.
          Enter the PIN displayed on your phone's Quest Notify Host screen to decrypt and view alerts.
        </p>
        <input type="password" id="pin-input" class="pin-input" maxlength="4" placeholder="••••" autofocus inputmode="numeric">
        <div>
          <button class="btn-primary" onclick="submitPin()">Unlock Dashboard</button>
        </div>
        <div class="pin-error" id="pin-error">Incorrect PIN. Decryption failed.</div>
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

    // Pure JS SHA-256 implementation - runs everywhere without requiring window.crypto.subtle
    function sha256Bytes(bytes) {
      function rightRotate(value, amount) { return (value >>> amount) | (value << (32 - amount)); }
      const mathPow = Math.pow;
      const maxWord = mathPow(2, 32);
      let i, j;
      const words = [];
      const bitLength = bytes.length * 8;
      let hash = [];
      const k = [];
      let primeCounter = 0;
      const isComposite = {};
      for (let candidate = 2; primeCounter < 64; candidate++) {
        if (!isComposite[candidate]) {
          for (i = 0; i < 313; i += candidate) isComposite[i] = candidate;
          hash[primeCounter] = (mathPow(candidate, .5) * maxWord) | 0;
          k[primeCounter++] = (mathPow(candidate, 1/3) * maxWord) | 0;
        }
      }
      hash = hash.slice(0, 8);
      for (i = 0; i < bytes.length; i++) {
        words[i >> 2] |= (bytes[i] & 0xff) << (24 - ((i % 4) * 8));
      }
      words[bitLength >> 5] |= 0x80 << (24 - (bitLength % 32));
      words[(((bitLength + 64) >> 9) << 4) + 15] = bitLength;
      for (j = 0; j < words.length; j += 16) {
        const w = words.slice(j, j + 16);
        const oldHash = hash.slice(0);
        for (i = 0; i < 64; i++) {
          const w15 = w[i - 15], w2 = w[i - 2];
          const s0 = rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3);
          const s1 = rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10);
          w[i] = (i < 16) ? (w[i] || 0) : ((w[i - 16] + s0 + w[i - 7] + s1) | 0);
          const s1_maj = rightRotate(hash[0], 2) ^ rightRotate(hash[0], 13) ^ rightRotate(hash[0], 22);
          const maj = (hash[0] & hash[1]) ^ (hash[0] & hash[2]) ^ (hash[1] & hash[2]);
          const t2 = (s1_maj + maj) | 0;
          const s0_ch = rightRotate(hash[4], 6) ^ rightRotate(hash[4], 11) ^ rightRotate(hash[4], 25);
          const ch = (hash[4] & hash[5]) ^ (~hash[4] & hash[6]);
          const t1 = (hash[7] + s0_ch + ch + k[i] + w[i]) | 0;
          hash = [(t1 + t2) | 0].concat(hash);
          hash[4] = (hash[4] + t1) | 0;
          hash.pop();
        }
        for (i = 0; i < 8; i++) hash[i] = (hash[i] + oldHash[i]) | 0;
      }
      const out = new Uint8Array(32);
      for (i = 0; i < 8; i++) {
        out[i * 4] = (hash[i] >> 24) & 0xff;
        out[i * 4 + 1] = (hash[i] >> 16) & 0xff;
        out[i * 4 + 2] = (hash[i] >> 8) & 0xff;
        out[i * 4 + 3] = hash[i] & 0xff;
      }
      return out;
    }

    function strToBytes(str) {
      const enc = [];
      for (let i = 0; i < str.length; i++) {
        let code = str.charCodeAt(i);
        if (code < 128) enc.push(code);
        else if (code < 2048) { enc.push(192 | (code >> 6), 128 | (code & 63)); }
        else { enc.push(224 | (code >> 12), 128 | ((code >> 6) & 63), 128 | (code & 63)); }
      }
      return new Uint8Array(enc);
    }

    function bytesToStr(bytes) {
      let str = '';
      let i = 0;
      while (i < bytes.length) {
        let b = bytes[i++];
        if (b < 128) str += String.fromCharCode(b);
        else if (b < 224) { str += String.fromCharCode(((b & 31) << 6) | (bytes[i++] & 63)); }
        else { str += String.fromCharCode(((b & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63)); }
      }
      return str;
    }

    function hexToBytes(hex) {
      const b = new Uint8Array(hex.length / 2);
      for (let i = 0; i < hex.length; i += 2) b[i / 2] = parseInt(hex.substr(i, 2), 16);
      return b;
    }

    function bytesToHex(b) {
      return Array.from(b).map(x => x.toString(16).padStart(2, '0')).join('');
    }

    function concatBytes(a, b) {
      const c = new Uint8Array(a.length + b.length);
      c.set(a, 0); c.set(b, a.length);
      return c;
    }

    function cryptCtr(dataBytes, keyBytes, ivBytes) {
      const out = new Uint8Array(dataBytes.length);
      let blockIndex = 0;
      let offset = 0;
      while (offset < dataBytes.length) {
        const counterBytes = new Uint8Array([
          (blockIndex >> 24) & 0xff, (blockIndex >> 16) & 0xff,
          (blockIndex >> 8) & 0xff, blockIndex & 0xff
        ]);
        const blockHash = sha256Bytes(concatBytes(concatBytes(keyBytes, ivBytes), counterBytes));
        for (let i = 0; i < 32 && offset < dataBytes.length; i++, offset++) {
          out[offset] = dataBytes[offset] ^ blockHash[i];
        }
        blockIndex++;
      }
      return out;
    }

    function decryptPayload(bundle, pin) {
      const salt = hexToBytes(bundle.salt);
      const iv = hexToBytes(bundle.iv);
      const ctBytes = hexToBytes(bundle.ciphertext);
      const key = sha256Bytes(concatBytes(salt, strToBytes(pin.trim())));

      const expectedTag = bytesToHex(sha256Bytes(concatBytes(key, ctBytes)));
      if (expectedTag !== bundle.tag) {
        throw new Error("Authentication tag mismatch: Invalid PIN");
      }

      const ptBytes = cryptCtr(ctBytes, key, iv);
      const jsonStr = bytesToStr(ptBytes);
      return JSON.parse(jsonStr);
    }

    function encryptPayload(dataObj, pin) {
      const salt = new Uint8Array(16);
      for (let i = 0; i < 16; i++) salt[i] = Math.floor(Math.random() * 256);
      const iv = new Uint8Array(8);
      for (let i = 0; i < 8; i++) iv[i] = Math.floor(Math.random() * 256);

      const key = sha256Bytes(concatBytes(salt, strToBytes(pin.trim())));
      const jsonStr = JSON.stringify(dataObj);
      const ptBytes = strToBytes(jsonStr);
      const ctBytes = cryptCtr(ptBytes, key, iv);
      const tag = bytesToHex(sha256Bytes(concatBytes(key, ctBytes)));

      return {
        salt: bytesToHex(salt),
        iv: bytesToHex(iv),
        ciphertext: bytesToHex(ctBytes),
        tag: tag
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
        if (!resp.ok) throw new Error("Server error HTTP " + resp.status);
        const encryptedBundle = await resp.json();
        const decryptedList = decryptPayload(encryptedBundle, pin);

        // Success! Save PIN
        activePin = pin;
        sessionStorage.setItem("qnotify_pin", pin);
        showDashboard();
        renderNotifications(decryptedList);
      } catch (err) {
        console.error("PIN Decryption error:", err);
        sessionStorage.removeItem("qnotify_pin");
        errorDiv.textContent = "Incorrect PIN. Decryption failed. Please check the PIN on your phone.";
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
        const list = decryptPayload(encryptedBundle, activePin);
        renderNotifications(list);
      } catch (err) {
        console.warn("Polling decryption failed:", err);
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
        const payload = encryptPayload({ action: "dismiss", key: key }, activePin);
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
        const payload = encryptPayload({ action: "clear_all" }, activePin);
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

    const pinInput = document.getElementById("pin-input");
    pinInput.addEventListener("keydown", function(e) {
      if (e.key === "Enter") submitPin();
    });
    pinInput.addEventListener("input", function(e) {
      if (this.value.length === 4) submitPin();
    });

    if (activePin && activePin.length === 4) {
      pinInput.value = activePin;
      submitPin();
    }
  </script>
</body>
</html>
    """.trimIndent()
}
