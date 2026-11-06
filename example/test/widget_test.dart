import 'package:flutter_test/flutter_test.dart';

import 'package:geotime_consistency_example/main.dart';

void main() {
  testWidgets('renders validate button and initial output', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());

    expect(find.text('Validate'), findsOneWidget);
    expect(find.textContaining('Tap the button'), findsOneWidget);
  });
}
