import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geotime_consistency/geotime_consistency.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('geotime_consistency');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      expect(methodCall.method, 'validate');
      expect(methodCall.arguments['timeEndpoints'], ['https://x.test/h']);
      return {
        'status': 'pass',
        'failureReasons': <String>[],
        'warnings': <String>[],
      };
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('validate maps platform result', () async {
    final r = await GeotimeConsistency.validate(
      const GeoTimeConfig(
        timeEndpoints: ['https://x.test/h'],
        targetLatitude: 1,
        targetLongitude: 2,
        radiusMeters: 100,
      ),
    );
    expect(r.status, GeoTimeStatus.pass);
    expect(r.passed, isTrue);
  });
}
