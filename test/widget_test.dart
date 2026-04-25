import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:app/main.dart';

void main() {
  testWidgets('App shows startup gate before meta resolves', (WidgetTester tester) async {
    await tester.pumpWidget(const LiminalRootApp());
    await tester.pump();

    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });
}
