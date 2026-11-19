# Ring Shield Overlay (Phase 1 PoC)

Ultra-low-latency Ring doorbell live video overlay for **Nvidia Shield Pro** (Android TV), built on the **Official Ring Partner API (WHEP + Webhooks)**, a tiny LAN/home-server **Node.js/TypeScript relay**, and a lightweight **Kotlin + `stream-webrtc-android` (`arm64-v8a`)** Android TV client.

> **Status:** Phase 1 Proof of Concept & Latency Benchmarking Harness.  
> See [docs/feasibility-assessment.md](docs/feasibility-assessment.md) for the full technical feasibility assessment, repository source-code analysis, and latency budget.

---

## Architecture

```
Ring Doorbell (2nd Gen Battery, 1080p H.264)
        │
        ▼
Ring Cloud / Amazon Vision API (api.amazonvision.com)
   ├─ Webhooks (button_press / motion_detected) ──► Cloudflare Tunnel ──► Home Server Relay ──(LAN WS <1 ms)──► Shield Pro
   ├─ WHEP SDP Offer/Answer                     ◄── Direct WAN ────────── Home Server Relay ◄─(LAN HTTP <1 ms)─ Shield Pro
   └─ Live Video Media (WebRTC SRTP H.264)      ═══════════════════════ Direct Ring ↔ Shield Pro ═════════════► TextureView Overlay
```

### Key Low-Latency & Shield-Specific Design Choices
- **Direct WebRTC media** (Ring ↔ Shield Pro)—zero transcoding, zero RTSP/HLS/WebView hops.
- **`TextureView`-backed WebRTC renderer** (`TextureViewRenderer` on `org.webrtc.EglRenderer`) instead of `SurfaceView`, so the overlay composites reliably over Netflix, YouTube, and Dolby Vision/HDR playback without punching a transparent hole.
- **Zero audio interruption** — audio transceiver defaults to `INACTIVE` / muted with no `AudioTrack` output so foreground playback never pauses or ducks.
- **Motion-Triggered Pipeline Pre-Warming** (`MOTION_PREWARM` mode) — on `motion_detected`, the Shield pre-creates the `PeerConnection` and finishes STUN ICE gathering locally with **zero extra doorbell wake-ups, zero battery drain, zero blue LED, and zero button-suppression risk**. When `button_press` arrives, the already-gathered SDP offer is POSTed to WHEP in `<1 ms`.
- **Fast ICE gathering** — resolves as soon as the first `srflx` candidate arrives (capped at `250 ms` by default) instead of waiting 3 s for full ICE completion.
- **TWCC SDP stripping** — removes `a=rtcp-fb:* transport-cc` to avoid Ring's send-side startup resolution probing.

---

## Quick Start (5-Minute Ring Playground Test)

Follow [docs/ring-portal-setup.md](docs/ring-portal-setup.md) to get a short-lived **Ring Developer Playground** token (no app registration wait) or set up your permanent **Private Use App**.

### 1. Run the Relay
```bash
cd relay
cp .env.example .env
# Paste RING_ACCESS_TOKEN from https://developer.amazon.com/ring/console/playground
npm install
npm run build
npm start
```
Verify device discovery:
```bash
curl http://localhost:8787/api/devices
```

### 2. Build the Shield Pro PoC APK (via Docker — no JDK/Android SDK required on Windows)
```bash
docker build -f android-poc/Dockerfile.build -t ring-shield-builder android-poc
docker run --rm -v "${PWD}/android-poc/out:/out" ring-shield-builder
```
The compiled `arm64-v8a` APK is written to `android-poc/out/app-debug.apk`.

### 3. Sideload & Grant Overlay Permission on Nvidia Shield Pro
```bash
adb connect <SHIELD_IP>:5555
adb install -r android-poc/out/app-debug.apk
adb shell appops set com.builtbymac.ringshield SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.builtbymac.ringshield
```

Push your PoC config (replace `<RELAY_IP>` and `<DEVICE_ID>`):
```bash
adb shell "cat << 'EOF' > /sdcard/Android/data/com.builtbymac.ringshield/files/config.json
{
  \"relayUrl\": \"http://<RELAY_IP>:8787\",
  \"deviceId\": \"<DEVICE_ID>\",
  \"mode\": \"MOTION_PREWARM\",
  \"overlayDurationSec\": 20,
  \"overlayWidthPx\": 640,
  \"overlayHeightPx\": 360,
  \"softwareDecoder\": false,
  \"stripTwcc\": true,
  \"iceGatherBudgetMs\": 250
}
EOF"
```

Trigger a synthetic test event from your PC/phone:
```bash
curl -X POST http://<RELAY_IP>:8787/api/test/trigger \
  -H "Content-Type: application/json" \
  -d '{"event_type": "button_press"}'
```

### 4. Pull & Analyze Benchmark Results
```bash
adb pull /sdcard/Android/data/com.builtbymac.ringshield/files/benchmarks.jsonl benchmarks/
python scripts/analyze_benchmarks.py benchmarks/benchmarks.jsonl
```
