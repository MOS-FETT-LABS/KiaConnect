# Kia Access — Project Plan

> Remote Kia Connect control from the Pebble Time 2: lock/unlock, remote engine
> start with climate presets, vehicle status — via the unofficial Kia Connect
> API. Watchapp + Android companion following the PEBBLEDEV app archetype
> (`docs/archetype.md`). Standalone: no third-party server, bridge, or helper
> infrastructure — works with just the Pebble app and Android app.
> **Safety-relevant: controls a real vehicle.**

## 0. What the user wants (source: screenshots in sandbox `PEBBLEDEV/KiaConnect/`)

Kia Access app for a **Sportage Hybrid** (US, Kia Connect subscription active).
The watch UI should mirror the official app's button language:

**Screen 1 — Home** (from `Screenshot_20261124_135630`):
- Vehicle name ("My SPORTAGE HYBRID")
- Est. Range (281 mi) with fuel bar
- Status card: "Vehicle is locked — Check vehicle status" + Last Updated
- 4 big buttons: **Start Engine · Lock · Unlock · More**

**Screen 2 — Climate presets** (from `Screenshot_20261124_135648`):
- **Start Cool (68° F)** — 10 minutes
- **Start Warm (80° F)** — 10 minutes, Accessories
- **Start Custom (LOW)** — 10 minutes, Accessories (opens temperature/duration picker)

## 1. The unofficial API (researched 2026-11-24)

Reference implementation: **hyundai_kia_connect_api** v5.4.1
(github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api — Python, active,
drives the kia_uvo Home Assistant integration). Facts that matter for USA Kia:

- Base URL: `https://api.owners.kia.com/apigw/v1/`
- Login: username + password (+ **PIN possibly required for USA** — pass and
  handle both), token = `sid` header, per-vehicle `vinkey` header.
- Client identity: generated device_id + clientuuid (uuid5 of a random device
  id, like the iOS app).
- **TLS quirk:** Kia USA needs a legacy cipher setup — Python lib patches
  requests with `SECLEVEL=1` TLSv1.2 context. Kotlin must do the same (OkHttp
  custom `ConnectionSpec` with permissive cipher list; watch for failures).
- Remote actions (async, 1–2 min to reach SUCCESS — poll the transaction):
  - lock: `rems/door/lock` · unlock: `rems/door/unlock`
  - climate/engine start: `start_climate` (temp range **62–83°F**, defrost,
    seat/steering heat, duration; starts the ICE engine on hybrid in USA)
  - stop climate
- Status: cached vehicle data (odometer, fuel range, lock state, last updated);
  **force update** wakes the car — heavily rate-limited.
- **Rate limits:** excessive calls → temporary blocks (up to ~3h) and 12V
  battery drain. Conservative cadence mandatory.
- USA support status: lock/unlock ✔, start/stop climate ✔, update ✔.
- Open questions (verify at build time): whether the "stamp" generation used by
  older libs is needed for USA endpoints; exact login payload shape; 2FA
  prompts on login.

## 2. Architecture

```
[ Watch: C app (emery) — status card + big action buttons + confirmations ]
        |  AppMessage (KIA_MESSAGE_KEY_*, new UUID)
[ Phone: Android companion (Kotlin) ]
        |  HTTPS (Kia Connect API, legacy ciphers, token session)
[ Kia Connect cloud ]  →  vehicle
```

- **No pkjs network path.** All Kia traffic is companion-side (credentials and
  long sessions must live on the phone; pkjs can't hold them safely). The
  watchapp may still ship a tiny pkjs for settings sync (runtime/vehicle
  selection), but zero API calls.
- Companion is a **new, independent app**: package
  `com.mosfettlabs.kiacompanion`, own repo, own UUID, own PebbleKit listener
  service (KiaPebbleListenerService). Shares no code or identity with other
  apps in this workspace.
- Protocol: its own KIA_MESSAGE_KEY_* contract, shared between the C side and
  the companion (KIA_STATUS_REQUEST / KIA_NAME / KIA_LOCKED / KIA_ACTION /
  KIA_ACTION_RESULT / KIA_PROGRESS etc.).

## 3. Watch UI (emery, 200×228, colors matching Kia Access)

- **Home screen** (modeled on screenshot 1):
  - Top: vehicle name (scrollable/shrunk if long)
  - Middle: **Range** big number + unit, fuel bar below
  - Status card: lock icon + "Locked"/"Unlocked" + last-updated time
  - Action bar / buttons: **Start · Lock · Unlock · More** (4 round icon
    buttons in a 2×2 grid at the bottom; icons drawn as PDC vectors)
- **Start menu** (from Start or More): list like screenshot 2:
  - Start Cool (68° F) — 10 minutes
  - Start Warm (80° F) — 10 minutes, Accessories
  - Start Custom (LOW) → temperature picker (62–83°F) + duration (5/10/15/20 min)
  - Stop Engine/Climate
  - Hazard lights (if supported), Force refresh (guarded)
- **Safety flow (user-approved interaction, 2026-11-24):** every function has a
  **vector icon** and fires on a **4-second hold** on its button (touch or
  button-bar):
  - While holding: progress ring fills around the icon; releasing early cancels
    silently.
  - On hold completion: **haptic pulse** (watch vibration patterns) + progress card "Locking… (Kia takes
    1–2 minutes)".
  - Unlock additionally keeps the full 4s hold requirement (no single-press
    path ever).
  - When the transaction finishes, the progress card updates ("Locked ✓" /
    error reason) and the home status card refreshes (last-updated time).
- Action pending state: progress card with live transaction status, app stays
  alive (AppMessage keepalive) until the result lands.

## 3b. Touch UI (user requirement 2026-11-24)

Emery has a touchscreen and PebbleOS exposes a raw touch service to C apps
(reference: coredevices/pebble-browser-app `src/c/touch_input.{c,h}` —
`touch_service_subscribe` with `TouchEvent` phases Touchdown /
PositionUpdate / Liftoff). This app uses touch natively:

- Tappable button grid (Start/Lock/Unlock/More) and tappable preset rows with
  pressed-state highlight.
- Scroll on the preset/More menu via drag; edge swipes for Back (like the
  browser app).
- All button-bar click config still works as the fallback.

## 3c. Pattern lock (user requirement 2026-11-24)

The watch controlling the car must not be usable by anyone who picks it up, so
the app is gated behind a drawn pattern:

- **Gate screen on app open:** 3×3 dot grid; user traces the pattern with the
  finger (raw touch stream); ≥4 dots required; finger lift = submit.
- **Wrong pattern:** shake feedback + retry. After **5 consecutive failures**,
  lockout cooldown (5 min, doubling per extra failure) — status still visible,
  actions disabled.
- **Storage:** only a salted hash of the dot sequence lives on the watch
  (Pebble persistent storage). Salt + hash set during enrollment. This is a
  deterrent, not cryptography (threat model: snatch-and-press, not firmware
  attacks) — stated openly in the plan and the app's About screen.
- **Enrollment:** primary path = companion app (draw the pattern on the phone
  UI, it sends salted hash + salt over AppMessage, watch persists). Fallback =
  on-watch enrollment (draw twice to confirm) if no companion around.
- **Re-lock:** configurable; default re-locks when the app exits to the
  launcher (and on watch lock screen). Status screen stays readable after
  unlock only.
- The gate runs BEFORE the home screen renders any action buttons; Back during
  the gate exits the app.

## 4. Companion (Kotlin)

- `KiaSession` — login, token persistence (encrypted prefs), refresh, PIN flow
  if demanded.
- `KiaApi` — OkHttp with legacy `ConnectionSpec` (TLS compat port of
  KiaSSLAdapter), endpoints: login, vehicles/status, lock/unlock,
  start_climate/stop, force update; transaction status polling.
- Settings UI (single-activity, plain Android views): Kia username + password + optional
  PIN, default climate preset, vehicle selection (multi-vehicle later), and a
  **"Watch test status"** button  for diagnostics.
- Rate-limit guardrails: local cooldowns (no force refresh more than once per
  N hours; action spacing ≥ 30s), explicit "vehicle may be asleep" states,
  blackout window option (10PM–6AM force-refresh off like kia_uvo default).

## 5. Milestones

- **K0 — done 2026-11-24.** Plan + UI spec from screenshots; API research
  captured here. Reference screenshots: `PEBBLEDEV/KiaConnect/` (sandbox).
- **K1 — done 2026-11-24.** Companion auth PROVEN live: login + OTP flow
  (uppercase SMS/EMAIL notifytype) + vehicles + cached status on device.
  Key Cloudflare finding: the gate wants the full reference header set
  **including `date`** and no duplicate Host — plain TLS (even the phone's
  Conscrypt) passes with that; the Kotlin port and the JVM probe
  (`tools/KiaTlsProbe.java`) both prove it. Webview and Brick-bridge detours
  were abandoned (user wants a fully standalone app). Reference vendored at
  `vendor/hyundai_kia_connect_api` @ acd9ea5.
- **K2 — done 2026-11-24 (first pass).** Watchapp (`app/`, new UUID
  e024c06c-…) + companion PebbleKit listener (`KiaPebbleListenerService`,
  pebblekit2 1.2.0). Message keys are SDK-generated (10000+1*index); C and
  Kotlin both use 10000–10007. Watch sends STATUS_REQUEST on open; companion
  auto-relogs-in from stored credentials and pushes name/locked/fuel/odometer/
  updated. Touch taps on the 4-button grid + Select = refresh are wired
  (buttons are stubs until K3).

- **K2b — pattern gate.** 3×3 pattern unlock (raw touch), hash persistence,
  enrollment via companion UI, lockout, re-lock policy (§3c).
- **K3 — remote actions.** Lock/unlock + climate presets with the confirm
  flow, transaction polling, pending/result cards.
- **K4 — climate custom picker** (temp + duration), hazard/panic, force
  refresh with guardrails.
- **K5 — hardening.** Rate-limit guards, error cards, background service
  lifecycle (stay connected screen-off), release signing, repo publish.

## 6. Risks

- TLS compat on Kia USA (known quirk — solve first, in K1).
- PIN requirement on USA accounts (lib says "potentially USA").
- API drift: unofficial endpoints change; keep the vendored reference diffable.
- 12V battery / rate-limit discipline (UI must make force-refresh feel scarce).
- Anti-abuse: Kia could flag automated logins (device-id mimicry of the iOS
  client is what the lib does).

## 7. Sources

- github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api (v5.4.1, 2026-11-21)
- github.com/Hyundai-Kia-Connect/kia_uvo (README service matrix)
- KiaUvoApiUSA.py (endpoints, TLS patch, headers) — read raw from master
