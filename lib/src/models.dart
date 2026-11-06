/// Overall verdict of a [GeotimeConsistency.validate] call.
enum GeoTimeStatus {
  /// Every enabled check passed.
  pass,

  /// Direct evidence of cheating was found (mock location on,
  /// device clock differs from server time beyond tolerance, ...).
  fail,

  /// Not enough evidence to conclude either way
  /// (GPS accuracy too poor, location services off, ...).
  inconclusive,

  /// No usable internet connection. Time check requires network;
  /// the client app decides whether to retry or block.
  noNetwork,

  /// Unexpected system error (permission denied, Play Services
  /// missing, ...). See [GeoTimeResult.errorMessage].
  error,
}

/// Hard-failure reasons. Presence of any of these means
/// [GeoTimeStatus.fail] (except they never appear otherwise).
enum FailureReason {
  /// su binary / Magisk / test-keys detected, or the Play Integrity
  /// verdict failed.
  rooted,

  /// Play Integrity `MEETS_DEVICE_INTEGRITY` not met (e.g. Samsung
  /// Knox warranty bit tripped by an unlocked bootloader).
  deviceIntegrity,

  /// Device wall clock differs from trusted server time beyond
  /// the configured tolerance, or the boot-time anchor moved
  /// (clock was changed mid-session).
  timeSpoofed,

  /// The reported location is flagged as a mock location AND a
  /// mock-provider app is configured.
  mockLocation,

  /// Distance between current location and target exceeds the
  /// allowed radius.
  outOfRadius,
}

/// Signals that raise suspicion but never fail on their own.
/// Intended for logging / risk scoring on the client side.
enum WarningReason {
  /// `Settings.Global.AUTO_TIME` is disabled. Legitimate for
  /// offline users — do not hard-fail.
  autoTimeOff,

  /// A package that looks like a known fake-GPS app is installed.
  mockAppInstalled,

  /// Running on an emulator.
  emulator,

  /// Samsung Knox warranty bit was tripped (unlocked bootloader
  /// at some point, possibly before the current owner).
  knoxTripped,
}

GeoTimeStatus _parseStatus(String v) => GeoTimeStatus.values.asNameMap()[v] ?? GeoTimeStatus.error;

FailureReason _parseFailure(String v) => FailureReason.values.asNameMap()[v]!;

WarningReason _parseWarning(String v) => WarningReason.values.asNameMap()[v]!;

/// Configuration for a single [GeotimeConsistency.validate] call.
class GeoTimeConfig {
  /// Trusted time sources, tried in order until one answers. Defaults
  /// span three independent providers so a blocked/unreachable one
  /// (e.g. Google in some regions) is skipped:
  ///  - Google `generate_204`: the endpoint Android itself uses to
  ///    validate connectivity — tiniest possible response.
  ///  - Cloudflare `1.1.1.1`: IP literal, survives mild DNS poisoning.
  ///  - Apple `captive.apple.com`: the endpoint iOS/macOS uses for its
  ///    own captive-portal check.
  /// All return a standard `Date` header on any response.
  static const defaultTimeEndpoints = [
    'https://clients3.google.com/generate_204',
    'https://1.1.1.1',
    'https://captive.apple.com',
  ];

  const GeoTimeConfig({
    this.timeEndpoints = defaultTimeEndpoints,
    required this.targetLatitude,
    required this.targetLongitude,
    required this.radiusMeters,
    this.timeTolerance = const Duration(seconds: 60),
    this.maxAccuracyMeters = 100,
    this.networkTimeout = const Duration(seconds: 5),
  });

  /// Trusted HTTPS URLs whose `Date` response header is used as server
  /// time — tried in order, first success wins. Prefer putting your own
  /// backend first if you have one; defaults to [defaultTimeEndpoints].
  final List<String> timeEndpoints;

  /// Center of the allowed area (e.g. the check-in site).
  final double targetLatitude;
  final double targetLongitude;

  /// Allowed radius in meters around the target.
  final double radiusMeters;

  /// Max accepted |deviceTime - serverTime|. Defaults to 60 s —
  /// smaller values cause false positives from clock drift + latency.
  final Duration timeTolerance;

  /// Locations with horizontal accuracy worse than this (meters)
  /// yield [GeoTimeStatus.inconclusive] instead of failing.
  final double maxAccuracyMeters;

  /// Timeout for the server-time request.
  final Duration networkTimeout;

  Map<String, Object> toMap() => {
        'timeEndpoints': timeEndpoints,
        'targetLatitude': targetLatitude,
        'targetLongitude': targetLongitude,
        'radiusMeters': radiusMeters,
        'timeToleranceMs': timeTolerance.inMilliseconds,
        'maxAccuracyMeters': maxAccuracyMeters,
        'networkTimeoutMs': networkTimeout.inMilliseconds,
      };
}

/// Aggregated result of all checks.
class GeoTimeResult {
  const GeoTimeResult._({
    required this.status,
    required this.failureReasons,
    required this.warnings,
    this.serverTime,
    this.deviceTime,
    this.timeDeltaMs,
    this.accuracyMeters,
    this.distanceMeters,
    this.errorMessage,
  });

  factory GeoTimeResult.fromMap(Map<dynamic, dynamic> m) => GeoTimeResult._(
        status: _parseStatus(m['status'] as String? ?? 'error'),
        failureReasons:
            (m['failureReasons'] as List?)?.map((e) => _parseFailure(e as String)).toList() ?? const [],
        warnings: (m['warnings'] as List?)?.map((e) => _parseWarning(e as String)).toList() ?? const [],
        serverTime: _millisToDate(m['serverTimeMs']),
        deviceTime: _millisToDate(m['deviceTimeMs']),
        timeDeltaMs: (m['timeDeltaMs'] as num?)?.toInt(),
        accuracyMeters: (m['accuracyMeters'] as num?)?.toDouble(),
        distanceMeters: (m['distanceMeters'] as num?)?.toDouble(),
        errorMessage: m['errorMessage'] as String?,
      );

  static DateTime? _millisToDate(Object? v) =>
      v is num ? DateTime.fromMillisecondsSinceEpoch(v.toInt(), isUtc: true) : null;

  final GeoTimeStatus status;

  /// Proven cheat signals. Empty unless [status] is [GeoTimeStatus.fail].
  final List<FailureReason> failureReasons;

  /// Suspicious-but-not-conclusive signals. For logging/risk scoring.
  final List<WarningReason> warnings;

  /// UTC time according to the trusted server (network-derived).
  final DateTime? serverTime;

  /// UTC time according to the device clock at check time.
  final DateTime? deviceTime;

  /// Signed difference `deviceTime - serverTime`, latency-corrected.
  final int? timeDeltaMs;

  /// Horizontal accuracy of the location fix used, in meters.
  final double? accuracyMeters;

  /// Distance between current location and the target, in meters.
  final double? distanceMeters;

  /// Populated when [status] is [GeoTimeStatus.error].
  final String? errorMessage;

  bool get passed => status == GeoTimeStatus.pass;

  @override
  String toString() =>
      'GeoTimeResult(status: $status, failures: $failureReasons, warnings: $warnings, '
      'deltaMs: $timeDeltaMs, distance: $distanceMeters, accuracy: $accuracyMeters, '
      'error: $errorMessage)';
}
