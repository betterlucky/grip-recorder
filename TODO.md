# TODO

## Next App Improvements

- Add protocol presets that auto-configure reps/timing:
  - `check_1`
  - `check_2`
  - `short_evidence_5`
  - `full_protocol_10`
  - `custom`
- Add a missed-value indicator for reps where no value was captured.
- Add a test-complete export prompt after the final rep.
- Add a countdown before the first pull.
- Add a voice-activated start flow:
  - Change **Start Session** to **Get Ready**.
  - After tapping **Get Ready**, listen for `start`.
  - Saying `start` begins the initial countdown.
  - During a ready/session state, saying `stop` can end the session early.
- Add a low-feedback mode that hides settings/buttons during an active session, leaving only phase, timer, latest value, and stop.
- Add support for common fractional phrases if needed, such as `three quarters`.
- Add an optional pre-test microphone check.

## Integration

- Keep Grip Recorder as a measurement instrument only.
- Export one session CSV into `Downloads/GripRecorderData/`.
- Treat Lodestone as the owner of persistence and history.
- Use `session_id` as the natural key for Lodestone import/upsert.
