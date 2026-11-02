# Quest Notify Bridge (Android Phone Host + Meta Quest 3 Client)

A two-part native Android/Kotlin (Jetpack Compose) workspace that forwards incoming notifications from your **Android Phone** over your local Wi-Fi network directly to your **Meta Quest 3**, displaying them both in a spatial 2D VR dashboard panel and as native floating **Horizon OS heads-up VR notification toasts** (plus optional Voice Readout while inside VR games).

---

## Project Architecture

* [`:shared`](shared/src/main/java/com/example/questnotify/shared/ProtocolModels.kt) — Shared protocol definitions:
  * **UDP Port `48210`**: Local LAN auto-discovery beacons (`DISCOVERY_BEACON` / `DISCOVERY_PROBE`) so the Quest 3 automatically finds your phone without typing IPs manually.
  * **TCP Port `48211`**: Persistent, low-latency newline-delimited JSON stream with 4-digit PIN authentication (`PAIR_REQUEST` / `PAIR_ACK`), `PING`/`PONG` keep-alive heartbeats, and real-time `NOTIFICATION_POSTED` / `NOTIFICATION_REMOVED` synchronization.
* [`:phone-host`](phone-host/src/main/java/com/example/questnotify/host/MainActivity.kt) — **Android Phone Host App** (`com.example.questnotify.host`):
  * [`PhoneNotificationListenerService`](phone-host/src/main/java/com/example/questnotify/host/PhoneNotificationListenerService.kt): Captures system-wide notifications (`StatusBarNotification`), filters out empty group summaries and optional ongoing progress bars, resolves the sender app name, and forwards payloads to the bridge service.
  * [`HostBridgeService`](phone-host/src/main/java/com/example/questnotify/host/HostBridgeService.kt): Foreground service that broadcasts UDP discovery beacons, validates the 4-digit pairing PIN, and streams notifications over TCP to all paired Quest headsets.
* [`:quest-client`](quest-client/src/main/java/com/example/questnotify/quest/QuestMainActivity.kt) — **Meta Quest 3 VR App** (`com.example.questnotify.quest`):
  * Configured for **Meta Horizon OS** (`com.oculus.intent.category.2D`, `com.oculus.supportedDevices="quest2|questpro|quest3|quest3s"`, default spatial window `920dp x 620dp`).
  * [`QuestReceiverService`](quest-client/src/main/java/com/example/questnotify/quest/QuestReceiverService.kt): Runs in the background on your Quest 3 so notifications continue arriving even when the 2D panel is minimized or while you are in a full-screen 3D VR app. Posts high-priority Horizon OS system notifications (floating VR toasts) and supports optional Text-to-Speech (TTS) voice readout.

---

## Quick Start & Installation

### 1. Open in Android Studio & Build APKs
1. Open **`C:\Users\kevin\.gemini\antigravity\scratch\quest-notify-bridge`** in **Android Studio**.
2. Let Gradle sync and build both modules:
   ```powershell
   ./gradlew :phone-host:assembleDebug :quest-client:assembleDebug
   ```

### 2. Install on Your Android Phone
Connect your Android phone via USB (with USB Debugging enabled) and install `:phone-host`:
```powershell
adb -d install -r phone-host/build/outputs/apk/debug/phone-host-debug.apk
```
1. Open **Quest Notify Host** on your phone.
2. Tap **Grant Notification Access** and enable **Quest Notify Phone Listener** in Android Settings.
3. Tap **Start Host** — note the **4-digit Pairing PIN** displayed on the card.

### 3. Sideload onto Your Meta Quest 3
Connect your Meta Quest 3 (with Developer Mode / ADB enabled) via USB-C or Wireless ADB (`sidequest` / `adb`):
```powershell
adb install -r quest-client/build/outputs/apk/debug/quest-client-debug.apk
```
1. Put on your **Meta Quest 3**, open the **App Library**, filter by **Unknown Sources**, and launch **Quest Notify (VR)**.
2. Make sure your Quest 3 and Android phone are on the **same Wi-Fi network**.
3. Your phone will appear automatically under **Discovered Phones on Wi-Fi** — click **Use IP**, enter the **4-Digit PIN** shown on your phone, and click **Pair & Connect**.
4. Tap **Send Test Notification to Quest 3** on your phone to immediately see the notification appear in the VR panel and pop up as a floating Horizon OS VR toast!

