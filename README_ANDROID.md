# Exercise Vision Tracker (Android)

Native Android app that:
- uses the camera to detect likely exercise type (`Squat`, `Push-up`, `Jumping Jack`, `Lunge`)
- checks form and suggests corrections in real time
- estimates calorie burn based on MET and user weight
- stores completed sessions locally
- shows daily/monthly/yearly table + chart trends with growth rate

## Open in Android Studio
1. Open the folder: `C:\Users\alex\Documents\Codex\2026-04-23-make-a-exercise-tracking-app-that`
2. Let Gradle sync.
3. Run the `app` configuration on an Android device/emulator (Android 8.0+).

## Important
- First run will ask camera permission.
- Pose detection and form checks are heuristic logic for fitness guidance, not medical-grade analysis.
