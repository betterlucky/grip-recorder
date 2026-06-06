# Grip Recorder

A simple Android app for timed dynamometer grip-strength rep collection.

Grip Recorder is intended to be a measurement instrument. It exports session
CSV files for Lodestone or another system to import and own as durable history.

## Current Flow

- Tap **Get Ready** once.
- Say **start** to begin the countdown. If voice capture is off, the countdown starts automatically.
- The app counts down from 3 before the first pull.
- The app signals **Pull** for the configured pull duration, defaulting to 3 seconds.
- The app switches to **Rest** for the configured rest duration, defaulting to 5 seconds.
- During rest, say the value shown on the dynamometer. Whole numbers such as `42` are expected; no `.0` suffix is required.
- Say **wrong**, **incorrect**, or **mistake** during rest to flag the current rep. Say **stop** to end early.
- The app repeats for the configured rep count, defaulting to 10 reps.
- Export structured CSV to `Downloads/GripRecorderData/`.
- Select `left` or `right` for hand.
- Select one of `check_1`, `check_2`, `short_evidence_5`, `full_protocol_10`, or `custom` for protocol.
- Set number and rest gap are under **Advanced settings**.

The timing loop is independent from speech recognition callbacks, so the pull/rest cadence keeps running even if recognition is slow or misses a value. Empty rest windows are marked as missed in the editable values list.

Voice capture uses bundled Vosk recognition rather than Android's built-in Google recognizer. The app listens for command words and records values during rest phases with a number-focused grammar covering values from zero to two hundred, optional one-digit decimals, common fractional phrases, `kg`/`kilograms`, and the flag words.

CSV export uses one row per rep with session metadata repeated:

```csv
session_id,started_at,hand,protocol_label,pull_seconds,rest_seconds,rep_count,set_number,rest_gap_minutes,rep_index,value_kg
```

Files are written to `Downloads/GripRecorderData/` with names like:

```text
grip_session_2026-06-06T09-14-22_left_check_2.csv
```

## Build

```sh
./gradlew assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install On Attached Phone

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.grippulltester android.permission.RECORD_AUDIO
adb shell monkey -p com.grippulltester 1
```

## License

Grip Recorder is released under the MIT License. Vosk and the bundled small
English model are Apache 2.0 licensed; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
