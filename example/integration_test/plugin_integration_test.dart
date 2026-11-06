import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:geotime_consistency/geotime_consistency.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('validate returns a result without throwing', (WidgetTester tester) async {
    final result = await GeotimeConsistency.validate(
      const GeoTimeConfig(
        targetLatitude: 10.762622,
        targetLongitude: 106.660172,
        radiusMeters: 500,
      ),
    );
    expect(GeoTimeStatus.values, contains(result.status));
  });
}
