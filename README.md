# SuperRun (PitPat Lite)

Minimal Android app for PitPat-T01 walking pads (Superun / DeerRun, BLE service `0xFBA0`).
No account, no cloud, no reminders: connect, start/pause/stop, speed, incline, live stats, mute.

Tested against: Superun BA16-B (advertises as `PitPat-T01`, service `FBA0`, write `FBA1`, notify `FBA2`).

## Build

Needs JDK 17+ and the Android SDK (`platforms;android-35`, `build-tools;35.0.0`).

```sh
export JAVA_HOME=~/.local/jdk/jdk-21.0.13+9 ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Protocol

Community-documented packet format, see `PitPatProtocol.kt`. Credits:
[peteh/pacekeeper](https://github.com/peteh/pacekeeper),
[Razzolate99/pitpat-treadmill](https://github.com/Razzolate99/pitpat-treadmill),
[azmke/pitpat-treadmill-control](https://github.com/azmke/pitpat-treadmill-control),
[Alexxanddr/homebridge-pitpat-treadmill](https://github.com/Alexxanddr/homebridge-pitpat-treadmill).

Incline control sends byte 9 of the command packet; verified on the belt speed path only, incline is
unverified on real hardware and only shown when the treadmill reports `maxIncline > 0`.

## Safety

A BLE stop is not an emergency stop. Keep the safety key attached and the physical controls in reach.
