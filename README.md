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
| Server time | Layered trusted sources by trust: **HTTPS `Date` → SNTP/UDP-123 → GNSS satellite clock → trusted boot anchor** | `failureReasons: [timeSpoofed]` when \|device − source\| > tolerance |
| Clock changed mid-session | Boot anchor `wallClock − elapsedRealtime` persisted in SharedPreferences; `elapsedRealtime` can't be set by the user | `failureReasons: [timeSpoofed]` |
| Auto-time off | `Settings.Global.AUTO_TIME == 0` | `warnings: [autoTimeOff]` — warning only, never fails alone |
| Mock location | `Location.isMock` (API 31+) / `isFromMockProvider` | `failureReasons: [mockLocation]` |
| Fake-GPS app installed | `<queries>` package check against known fake-GPS packages | `warnings: [mockAppInstalled]` |
| Radius | `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` → `distanceTo(target)` | `failureReasons: [outOfRadius]` |
| Weak GPS | Fix accuracy worse than `maxAccuracyMeters` | `inconclusive` — never fails on poor GPS |
| Knox tripped | `ro.boot.warranty_bit` / `ro.warranty_bit` | `warnings: [knoxTripped]` |
| Emulator | `Build.FINGERPRINT`/`MODEL`/… heuristics | `warnings: [emulator]` |

### Why `Date` header instead of a time API?

Trusted time comes from up to 3 independent sources:

1. **HTTPS `Date` headers** — retried once. TLS cannot be forged
   on-path, so an HTTPS answer always wins regardless of arrival order.
2. **SNTP (UDP 123)** — attempted in parallel with HTTPS (different
   failure domain: a network blackholing TLS often still allows NTP) but
   only *trusted* when every HTTPS endpoint is dead. Unauthenticated UDP
   is forgeable — responses are field-validated (mode/stratum/originate
   timestamp) and corroborated against the boot anchor / GNSS clock.
3. **GNSS satellite clock** — captured alongside the location fix
   (`GnssMeasurementsEvent`), fully offline; used only when both network
   sources are dead. Requires real sky visibility — indoors it stays
   null, which is expected (location would be `inconclusive` anyway).
4. **Trusted boot anchor** — when every remote source is dead but a
   boot-count-valid anchor exists, trusted time is *estimated* as
   `anchor + elapsedRealtime` (`timeSource: "anchor"`). An honest user
   still gets a real verdict instead of `timeSourceUnreachable`; a moved
   clock still flags `timeSpoofed`.

If none of the four can answer → `timeSourceUnreachable` (log, don't
block). That now means: no trusted source AND no usable anchor — i.e.
first session on this boot, or BOOT_COUNT untrackable.

Time-source fetching has a 12s overall budget; blocked sockets are
force-closed on expiry (per-request timeouts are capped at 3s). A
re-entrancy guard rejects a second `validate()` while one is running.

### Trusted boot anchor

The persisted boot anchor is written **only by HTTPS and GNSS** —
`anchor = trustedTime − elapsedRealtime`, stored alongside Android's
`BOOT_COUNT`. An unverified wall clock can never poison it, a forged
SNTP answer can't re-seed it, and a reboot (BOOT_COUNT changed) voids
the old anchor instead of false-flagging the user. When remote sources
are unreachable the anchor doubles as an estimated time source.

### HTTPS time endpoints

**Default endpoints** (tried in order, first success wins — all return a
standard `Date` header):

| Endpoint | Provider | Why |
|---|---|---|
| `https://clients3.google.com/generate_204` | Google | The endpoint Android itself uses to validate connectivity; 204 No Content |
| `https://1.1.1.1` | Cloudflare | IP literal — survives mild DNS poisoning; independent provider |
| `https://captive.apple.com` | Apple | The endpoint iOS/macOS uses for its captive-portal check |

A region blocking Google typically doesn't block Cloudflare and Apple.
If your app has its own backend, prepend it to `timeEndpoints` — trusting
a domain you control is strictly better than a third party. SNTP
servers default to `pool.ntp.org`, `time.cloudflare.com`,
`time.google.com` (override via `sntpServers`).

## Status model

```
pass          → allow the action
fail          → block; inspect result.failureReasons
inconclusive  → retry (weak GPS / permission missing / location off)
noNetwork     → ask the user to connect, then retry
timeSourceUnreachable → internet OK but every trusted time endpoint
              failed; other checks still ran. Log it, don't block.
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

3. **Reuse the validated values.** The result already carries
   `serverTime` (trusted UTC), `validatedLatitude`/`validatedLongitude`
   (the position that passed the radius check — see `pullPercent`),
   `timeDeltaMs`, `distanceMeters`, `accuracyMeters` — use these
   as the source of truth for check-in payloads or cross-checks; do not
   re-read the device clock or request a second location fix.
   `pullPercent` tells you whether the validated position is the raw
   fix (`0`) or was pulled toward the target (`5`/`15`) to pass — a
   non-zero value means the coordinates are adjusted, not measured.

4. **Log results to Crashlytics** — the plugin never logs by itself.
   Spec below.

## Logging spec (Crashlytics)

The plugin is silent — the client decides what to record. Recommended
rules:

| `status` | Record? | How |
|---|---|---|
| `pass` | No | Success = noise |
| `fail` | **Non-fatal** `recordError` | `Exception('geotime_consistency_fail')`, keys below + `failureReasons` joined |
| `inconclusive` | **Non-fatal** `recordError` only if ≥3 in a row per user | `Exception('geotime_consistency_inconclusive')` + `errorMessage` — sporadic weak GPS is normal, persistent is a device/config problem |
| `noNetwork` | No | UX retry flow; logging adds nothing |
| `timeSourceUnreachable` | **Non-fatal** `recordError` | `Exception('geotime_consistency_time_source')` — likely an outage or region block; a spike means add/rotate an endpoint |
| `error` | **Non-fatal** `recordError` | `Exception('geotime_consistency_error')` + `errorMessage` |

Always attach these custom keys (both `recordError` calls):

```dart
FirebaseCrashlytics.instance.setCustomKey('gtc_reasons', result.failureReasons.join(','));
FirebaseCrashlytics.instance.setCustomKey('gtc_warnings', result.warnings.join(','));
FirebaseCrashlytics.instance.setCustomKey('gtc_time_delta_ms', result.timeDeltaMs ?? -1);
FirebaseCrashlytics.instance.setCustomKey('gtc_time_source', result.timeSource ?? 'none');
FirebaseCrashlytics.instance.setCustomKey('gtc_time_attempts', result.timeAttempts.join(';'));
FirebaseCrashlytics.instance.setCustomKey('gtc_gnss_available', result.gnssTime != null);
FirebaseCrashlytics.instance.setCustomKey('gtc_distance_m', result.distanceMeters ?? -1);
FirebaseCrashlytics.instance.setCustomKey('gtc_accuracy_m', result.accuracyMeters ?? -1);
```

Reading `gtc_time_attempts` on `timeSourceUnreachable` tells you exactly
which layer failed, e.g. `https:1.1.1.1:SSLHandshakeException;
sntp:pool.ntp.org:SocketTimeoutException` = TLS interception + UDP
blocked (typical captive/enterprise network), while
`UnknownHostException` on everything = broken DNS.

**Privacy rules — do NOT log:**

- Raw latitude/longitude (PII). `distanceMeters`/`accuracyMeters` are
  enough to debug.
- `serverTime`/`deviceTime` are safe but useless alone — `timeDeltaMs`
  is the only time field worth logging.
- `warnings` are fine (no PII) but only meaningful alongside a fail /
  repeated inconclusive — attaching them to every event is noise.

**Why these statuses matter in the dashboard:**

- `fail` non-fatals = your cheat-detection hit rate. A spike usually
  means a popular fake-GPS app shipped an update, not a bug.
- `inconclusive` clusters on one device model/OS version = a real
  plugin problem (e.g. an OEM breaking `getCurrentLocation`).
- `error` non-fatals = integration bugs; these should trend to zero.

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

- **Radius fairness**: `radiusMeters` is never eased — instead the fix
  is pulled toward the target in steps (0%, 5%, 15%) and re-checked each
  time; the first position inside the radius wins (same policy as
  location-multishot-capture). `outOfRadius` only fails when even the
  15%-pulled position stays outside, so a legitimate user at the edge
  with a noisy fix isn't rejected. The winning position is returned as
  `validatedLatitude`/`validatedLongitude` with `pullPercent`.
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
