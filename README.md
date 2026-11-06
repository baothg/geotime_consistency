# geotime_consistency

Flutter plugin (Android) that detects time-spoofing and location-spoofing
and verifies the user is inside an allowed radius — for check-in /
attendance-style features.

**Android only. iOS is not implemented yet** (the Dart API is designed so
an iOS implementation can be added without breaking changes).

## What it checks

| Check | How | Result |
|---|---|---|
| Network | `NET_CAPABILITY_VALIDATED` + a real request to your trusted endpoint | `noNetwork` gate — everything else requires it |
| Root | `su` binaries, `test-keys`, Magisk/SuperSU packages, `which su` | `failureReasons: [rooted]` |
| Server time | Reads the `Date` header of an HTTPS request to **your** endpoint; latency-corrected via `elapsedRealtime` midpoint | `failureReasons: [timeSpoofed]` when \|device − server\| > tolerance |
| Clock changed mid-session | Boot anchor `wallClock − elapsedRealtime` persisted in SharedPreferences; `elapsedRealtime` can't be set by the user | `failureReasons: [timeSpoofed]` |
| Auto-time off | `Settings.Global.AUTO_TIME == 0` | `warnings: [autoTimeOff]` — warning only, never fails alone |
| Mock location | `Location.isMock` (API 31+) / `isFromMockProvider` | `failureReasons: [mockLocation]` |
| Fake-GPS app installed | `<queries>` package check against known fake-GPS packages | `warnings: [mockAppInstalled]` |
| Radius | `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` → `distanceTo(target)` | `failureReasons: [outOfRadius]` |
| Weak GPS | Fix accuracy worse than `maxAccuracyMeters` | `inconclusive` — never fails on poor GPS |
| Knox tripped | `ro.boot.warranty_bit` / `ro.warranty_bit` | `warnings: [knoxTripped]` |
| Emulator | `Build.FINGERPRINT`/`MODEL`/… heuristics | `warnings: [emulator]` |

### Why `Date` header instead of a time API?

Any HTTPS response from a domain you control already carries a
server-generated `Date` header. It's TLS-protected and free — no extra
time service, no extra request shape. A `HEAD` request is issued.

**Default endpoints** (tried in order, first success wins — all return a
standard `Date` header):

| Endpoint | Provider | Why |
|---|---|---|
| `https://clients3.google.com/generate_204` | Google | The endpoint Android itself uses to validate connectivity; 204 No Content |
| `https://1.1.1.1` | Cloudflare | IP literal — survives mild DNS poisoning; independent provider |
| `https://captive.apple.com` | Apple | The endpoint iOS/macOS uses for its captive-portal check |

A region blocking Google typically doesn't block Cloudflare and Apple.
If your app has its own backend, prepend it to `timeEndpoints` — trusting
a domain you control is strictly better than a third party.

## Status model

```
pass          → allow the action
fail          → block; inspect result.failureReasons
inconclusive  → retry (weak GPS / permission missing / location off)
noNetwork     → ask the user to connect, then retry
error         → log result.errorMessage
```

**Design rule:** `fail` requires direct evidence. Indirect signals
(auto-time off, fake-GPS app installed, emulator, Knox bit) go to
`warnings` for logging/risk-scoring and never fail on their own.

### Samsung / Knox

Knox devices are **not** blocked: Knox Workspace runs the app normally,
`isMock` and root checks behave identically on OneUI. If the Knox
warranty bit was ever tripped (bootloader unlocked — possibly by a
previous owner), you get `knoxTripped` as a *warning*, not a failure.

## Install

```yaml
dependencies:
  geotime_consistency:
    path: path/to/geotime_consistency   # or git/url
```

The plugin manifest already declares `INTERNET`,
`ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION` — merged into your app automatically.

## Integrate into your app

1. **Request location permission** (runtime) before calling — the plugin
   returns `inconclusive`, not a crash, if missing:

   ```dart
   await Permission.locationWhenInUse.request(); // permission_handler
   ```

2. **Call validate** at the moment the action happens (check-in tap):

   ```dart
   final result = await GeotimeConsistency.validate(
     const GeoTimeConfig(
       // timeEndpoints defaults to a Google/Cloudflare/Apple fallback
       // chain — prepend your own backend if you have one.
       targetLatitude: 10.762622,
       targetLongitude: 106.660172,
       radiusMeters: 200,
       timeTolerance: Duration(seconds: 60),
       maxAccuracyMeters: 100,
     ),
   );

   switch (result.status) {
     case GeoTimeStatus.pass:
       // proceed
     case GeoTimeStatus.fail:
       // result.failureReasons tells you exactly which cheat was caught
     case GeoTimeStatus.noNetwork:
       // prompt to connect
     case GeoTimeStatus.inconclusive:
       // retry — probably indoors / weak GPS
     case GeoTimeStatus.error:
       // result.errorMessage
   }
   ```

3. **Send `result.warnings` + `timeDeltaMs` to your backend** for risk
   scoring if you need audit trails (optional — the plugin works fully
   client-side).

## Recommended policy

- Block on `fail`. Never block on warnings alone.
- On `inconclusive`, offer "move to open sky" + retry rather than an error.
- On `noNetwork`, treat as retryable — the time check cannot run offline
  by design (device clock is untrusted).
- `timeTolerance`: keep ≥ 60 s. Smaller values false-positive on
  NTP-synced devices with harmless drift.
- `radiusMeters`: GPS accuracy outdoors is typically 5–50 m; a radius
  below ~50 m will cause legitimate rejections.

## Hardening

- **Radius fairness**: `outOfRadius` only fails when
  `distance − accuracy > radius`, so a legitimate user at the edge with
  a noisy fix isn't rejected.
- **Boot-anchor upgrade**: the persisted anchor re-anchors silently
  within 5 minutes after a reboot — a clock change in that window is
  undetected (see `shortcut:` comment in `NetworkTimeChecker`). Server-side
  anchoring removes this gap.

### Deliberately not included

- **Play Integrity API**: its verdict must be verified server-side, and
  devices with a tripped Knox warranty bit (e.g. previously
  bootloader-unlocked Samsung phones — often legitimate second-hand
  devices) fail `MEETS_DEVICE_INTEGRITY`. Accepting that edge case for
  now keeps client-side checks self-contained and avoids blocking real
  users. Revisit when a backend verdict path exists.

## Limitations — read before shipping

This is **detection, not a guarantee**. A sufficiently determined user
on a rooted device can hook `isMock`, `System.currentTimeMillis`, and
the network stack. For most consumer apps the combination above stops
all common cheat tools. If abuse has real monetary value, move the
final decision server-side (send raw signals up; let the server
verdict).
