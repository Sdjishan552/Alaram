# Wake 7 Alarm

A personal Android alarm designed for heavy sleepers.

## What it does
- Daily exact 7:00 AM alarm using Android AlarmManager.
- Full-screen alarm UI on the lock screen.
- Looped alarm audio using the **ALARM** audio usage stream.
- Pick a custom audio file from the phone.
- Alarm screen requires **7 mental-math questions** to be answered correctly before the stop button becomes available.
- Back button is disabled while the alarm screen is active.
- Reschedules after reboot/time changes.
- Includes a Test Alarm button.

## Important Android limitation
A normal third-party app cannot guarantee that it can override every hardware/software control on every Android phone. The OS can still force-stop/uninstall the app, and OEM power-management features can restrict apps. Alarm volume and Do Not Disturb policies can also affect audio. This project uses Android's supported exact-alarm and full-screen alarm mechanisms.

## Build
Open this folder in Android Studio and build the debug APK. Or push it to GitHub; the included GitHub Actions workflow builds the APK automatically.

On Android 13+, exact alarms are a supported use case for alarm-clock apps. Android 14+ may require the user to allow full-screen intent access. See Android's official documentation for exact-alarm and full-screen-intent behavior.
