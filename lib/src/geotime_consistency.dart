import 'package:flutter/services.dart';

import 'models.dart';

/// Anti-cheat consistency checks for time and location (Android).
///
/// Usage:
/// ```dart
/// final result = await GeotimeConsistency.validate(
///   GeoTimeConfig(
///     timeEndpoint: 'https://api.myapp.com/health',
///     targetLatitude: 10.762622,
///     targetLongitude: 106.660172,
///     radiusMeters: 200,
///   ),
/// );
/// switch (result.status) {
///   case GeoTimeStatus.pass: /* allow */ break;
///   case GeoTimeStatus.fail: /* block — see result.failureReasons */ break;
///   case GeoTimeStatus.noNetwork: /* ask user to connect & retry */ break;
///   case GeoTimeStatus.inconclusive: /* retry, weak GPS */ break;
///   case GeoTimeStatus.error: /* log result.errorMessage */ break;
/// }
/// ```
class GeotimeConsistency {
  GeotimeConsistency._();

  static const MethodChannel _channel = MethodChannel('geotime_consistency');

  /// Runs all checks and returns the aggregated [GeoTimeResult].
  ///
  /// Requires `ACCESS_FINE_LOCATION` (or coarse) granted and location
  /// services enabled, otherwise returns [GeoTimeStatus.inconclusive]
  /// or [GeoTimeStatus.error] rather than failing.
  static Future<GeoTimeResult> validate(GeoTimeConfig config) async {
    final Map<dynamic, dynamic>? raw =
        await _channel.invokeMethod<Map<dynamic, dynamic>>('validate', config.toMap());
    if (raw == null) {
      return GeoTimeResult.fromMap(const {
        'status': 'error',
        'errorMessage': 'Null result from platform channel',
      });
    }
    return GeoTimeResult.fromMap(raw);
  }
}
