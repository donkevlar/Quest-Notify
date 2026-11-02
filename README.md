# Quest Notify (Quest-Notify)
### Wireless Android Phone Notification Bridge for Meta Quest 3 & Quest 3S

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Meta%20Horizon%20OS-00E5FF?style=flat-square)](https://github.com/donkevlar/Quest-Notify)
[![Language](https://img.shields.io/badge/Language-Kotlin%20%2F%20Jetpack%20Compose-7C4DFF?style=flat-square)](https://kotlinlang.org/)
[![Devices](https://img.shields.io/badge/Supported%20Headsets-Quest%203%20%7C%20Quest%203S%20%7C%20Quest%20Pro-66BB6A?style=flat-square)](https://meta.com)
[![AI Assisted](https://img.shields.io/badge/Developed%20with-AI%20Assistance-blueviolet?style=flat-square)](https://github.com/donkevlar/Quest-Notify)

> [!NOTE]
> **AI Disclosure**: This repository was developed with the assistance of Artificial Intelligence (AI pair-programming with Google DeepMind's Antigravity / Gemini models) in collaboration with [@donkevlar](https://github.com/donkevlar). Architecture, features, and code were collaboratively engineered, reviewed, and iteratively refined.

**Quest Notify** streams your Android phone notifications over your local Wi-Fi network directly into your **Meta Quest 3** and **Meta Quest 3S**. Never take off your VR headset to check your phone again—notifications appear as native floating **Horizon OS heads-up VR toasts** inside games, in a spatial 2D dashboard panel, and via optional **Voice Readout (TTS)**.

---

## Key Features

- **Floating VR Heads-Up Alerts**: Displays incoming notifications as native Horizon OS system popups that hover non-intrusively in your peripheral vision while playing any VR game or experience.
- **Rich App Icon Streaming**: Extracted in real time from your phone (WhatsApp, Slack, Messages, Discord, Gmail, Instagram, etc.), scaled, and rendered in full color both on the VR toast banner and in the spatial feed.
- **24-Hour Persistent Auto-Connect**: Enter your 4-digit PIN once. The phone host securely remembers pairing credentials for 24 hours, automatically reconnecting whenever you put on your headset or detect your phone on Wi-Fi without typing a thing.
- **Two-Way Dismissal Sync ("Mark as Read" / "Acknowledge")**:
  - Tap **"Mark as Read"** directly on the floating VR toast or **"Acknowledge"** in the Quest dashboard to dismiss the alert in VR **and** immediately clear it from your phone's Android notification shade.
- **Granular App Notification Filtering**:
  - **All Apps (Blocklist Mode)**: Forwards everything while letting you mute noisy apps.
  - **Selected Apps Only (Allowlist Mode)**: Forwards only the apps you explicitly choose (e.g., only Slack + WhatsApp).
  - Built-in search, system app icons, and batch "Allow All" / "Block All" controls.
- **In-VR Quick Mute**: Silence unwanted apps directly from the Quest 2D panel without leaving your headset.
- **Voice Readout (TTS)**: Optional text-to-speech reads sender and message contents through your Quest speakers so you don't even have to look away from your game.
- **Local LAN Zero-Cloud Architecture**: Runs 100% on your local Wi-Fi network using lightweight UDP broadcast discovery and encrypted TCP JSON streams. No cloud accounts, external servers, or internet access required.
- **Responsive Layout for Quest 3 & Quest 3S**: Optimized dual-pane spatial 2D window with independent vertical scrolling, preventing UI clipping even on resized panels.

---

## Project Structure

```
Quest-Notify/
├── :shared/          # Common network protocol, UDP beacons, TCP JSON packets & models
├── :phone-host/       # Android Phone host application (NotificationListenerService & bridge)
└── :quest-client/     # Meta Quest 3 & 3S VR client (Background receiver & 2D spatial Compose UI)
```

### Network Architecture
- **UDP Port `48210`**: Auto-discovery beacons (`DISCOVERY_BEACON` & `DISCOVERY_PROBE`) allow your Quest to detect your phone on the local network automatically.
- **TCP Port `48211`**: Low-latency, newline-delimited JSON stream handling pairing authentication, keep-alive heartbeats, notification posting, and two-way dismissal sync (`NOTIFICATION_ACK`).

---

## Installation & Setup

### 1. Build APKs in Android Studio
1. Open this repository in **Android Studio**.
2. Build both APK modules:
   ```bash
   ./gradlew :phone-host:assembleDebug :quest-client:assembleDebug
   ```

### 2. Install on Your Android Phone
Connect your phone via USB with USB Debugging enabled:
```bash
adb -d install -r phone-host/build/outputs/apk/debug/phone-host-debug.apk
```
1. Open **Quest Notify Host** on your phone.
2. Tap **Grant Notification Access** and enable the listener in Android Settings.
3. Tap **Start Host** — your phone's IP and 24-hour pairing PIN will display.
4. *(Optional)* Tap **Manage Apps** to customize which apps are allowed to forward notifications.

### 3. Sideload onto Your Meta Quest 3 / 3S
Connect your Quest via USB-C or Wireless ADB (SideQuest / standard ADB):
```bash
adb install -r quest-client/build/outputs/apk/debug/quest-client-debug.apk
```
1. Put on your Quest, open **App Library** → filter by **Unknown Sources** → launch **Quest Notify (VR)**.
2. Make sure your phone and Quest are connected to the same Wi-Fi.
3. Your phone will appear under **Discovered Phones on Wi-Fi** — click **Use IP**, enter the 4-digit PIN, and click **Pair & Connect**.
4. Once paired, you can close the Quest window; the background receiver service keeps notifications streaming continuously in VR.

---

## Privacy & Security

- **100% Offline & Local**: Your notification data never leaves your home Wi-Fi network.
- **PIN Protected**: Unpaired devices cannot eavesdrop on notifications without the time-limited 4-digit PIN.
- **Filtered at the Source**: Blocked apps are ignored locally on the phone before reaching the network.

---

## License

Created by [donkevlar](https://github.com/donkevlar). Licensed under the [MIT License](LICENSE).
