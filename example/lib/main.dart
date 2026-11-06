import 'package:flutter/material.dart';
import 'package:geotime_consistency/geotime_consistency.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _output = 'Tap the button to run checks';

  Future<void> _validate() async {
    setState(() => _output = 'Requesting location permission…');
    final perm = await Permission.locationWhenInUse.request();
    if (!perm.isGranted) {
      setState(() => _output = 'Location permission denied');
      return;
    }

    setState(() => _output = 'Running checks…');
    final result = await GeotimeConsistency.validate(
      const GeoTimeConfig(
        // timeEndpoints defaults to a Google/Cloudflare/Apple fallback chain.
        targetLatitude: 10.762622, // HCMC
        targetLongitude: 106.660172,
        radiusMeters: 500,
      ),
    );
    setState(() => _output = result.toString());
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('geotime_consistency')),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton(onPressed: _validate, child: const Text('Validate')),
              const SizedBox(height: 16),
              Text(_output),
            ],
          ),
        ),
      ),
    );
  }
}
