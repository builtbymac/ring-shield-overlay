# Ring Developer Portal & Relay Setup Guide

This project supports **two authentication modes** so you can benchmark on your Nvidia Shield Pro immediately while your permanent Private Use App is set up.

---

## Path A: 5-Minute Test with Ring Developer Playground (No App Registration Wait)

1. Open the **Ring Developer Playground**: <https://developer.amazon.com/ring/console/playground>
2. Sign in with your Ring / Amazon account and click **Generate Token**.
3. Copy the short-lived access token (~30 minutes) into `relay/.env`:
   ```env
   PORT=8787
   RING_ACCESS_TOKEN=eyJ...
   ```
4. Start the relay (`npm run dev` or `docker compose up -d`).
5. Discover your doorbell's `ava1.ring.device.*` ID:
   ```bash
   curl http://localhost:8787/api/devices
   ```
6. Put that `deviceId` into the Shield's `config.json` and trigger live WHEP overlays via `POST /api/test/trigger`!

> **Note:** Playground tokens expire after ~30 minutes and do not deliver live Ring webhooks (use `/api/test/trigger` to test overlay rendering, Dolby Vision HDR compatibility, and WHEP startup speed).

---

## Path B: Permanent Production Mode (Private Use App + Webhooks)

1. Register at the **Ring Developer Portal**: <https://developer.ring.com/> (requires Amazon Developer identity verification).
2. Create a **Private Use App** (no public Ring Appstore certification required).
3. Collect your credentials from the portal:
   - `RING_CLIENT_ID`
   - `RING_CLIENT_SECRET`
   - `RING_HMAC_KEY` (HMAC Signature Key for webhook verification)
4. Expose your home server relay's `/api/webhook` endpoint over HTTPS using a **Cloudflare Tunnel**:
   - In Cloudflare Zero Trust → Networks → Tunnels, create a tunnel pointing `https://ring-hook.<yourdomain>/api/webhook` to `http://relay:8787`.
   - Restrict public tunnel routing so **only** `/api/webhook` (and `/health` if desired) is exposed externally; keep `/api/whep/*`, `/api/devices`, `/api/test/*`, and `/ws` strictly on your local LAN!
5. Configure your Webhook URL in the Ring Developer Portal (`https://ring-hook.<yourdomain>/api/webhook`).
6. Complete the Ring OAuth account linking flow (staging test user or your linked account) to obtain `RING_REFRESH_TOKEN`.
7. Update `relay/.env` (remove `RING_ACCESS_TOKEN` — do not set both):
   ```env
   PORT=8787
   RING_CLIENT_ID=...
   RING_CLIENT_SECRET=...
   RING_REFRESH_TOKEN=...
   RING_HMAC_KEY=...
   RING_DEVICE_ID=ava1.ring.device....
   RELAY_LAN_TOKEN=choose-a-random-local-secret
   ```
8. Start both relay and tunnel:
   ```bash
   docker compose --profile tunnel up -d
   ```
