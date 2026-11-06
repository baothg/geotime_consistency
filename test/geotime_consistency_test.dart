import 'package:flutter_test/flutter_test.dart';
import 'package:geotime_consistency/geotime_consistency.dart';

void main() {
  test('parses a fail result', () {
    final r = GeoTimeResult.fromMap({
      'status': 'fail',
      'failureReasons': ['timeSpoofed', 'mockLocation'],
      'warnings': ['autoTimeOff'],
      'serverTimeMs': 1_700_000_000_000,
      'deviceTimeMs': 1_700_000_120_000,
      'timeDeltaMs': 120_000,
      'accuracyMeters': 12.5,
      'distanceMeters': 800.0,
    });
    expect(r.status, GeoTimeStatus.fail);
    expect(r.passed, isFalse);
    expect(r.failureReasons,
        [FailureReason.timeSpoofed, FailureReason.mockLocation]);
    expect(r.warnings, [WarningReason.autoTimeOff]);
    expect(r.serverTime!.millisecondsSinceEpoch, 1_700_000_000_000);
    expect(r.timeDeltaMs, 120_000);
  });

  test('parses a noNetwork result with absent optional fields', () {
    final r = GeoTimeResult.fromMap({
      'status': 'noNetwork',
      'failureReasons': <String>[],
      'warnings': <String>[],
      'deviceTimeMs': 1_700_000_000_000,
    });
    expect(r.status, GeoTimeStatus.noNetwork);
    expect(r.failureReasons, isEmpty);
    expect(r.serverTime, isNull);
    expect(r.distanceMeters, isNull);
  });

  test('unknown status maps to error', () {
    final r = GeoTimeResult.fromMap({'status': '???'});
    expect(r.status, GeoTimeStatus.error);
  });

  test('config serializes all fields', () {
    const c = GeoTimeConfig(
      timeEndpoints: ['https://api.example.com/health'],
      targetLatitude: 10.0,
      targetLongitude: 106.0,
      radiusMeters: 200,
    );
    final m = c.toMap();
    expect(m['timeEndpoints'], ['https://api.example.com/health']);
    expect(m['timeToleranceMs'], 60000);
    expect(m['radiusMeters'], 200);
  });
}
