# Snap Spectacles Test Notes

Use this log for every on-device run so behavior changes are easy to trace.

## Session Header

- Date:
- Branch:
- Lens Studio version:
- Spectacles firmware/app version:
- Tester:

## Build + Deploy Record

- Lens project name:
- Build target/profile:
- Deploy method:
- Deploy result:
- Time to deploy:

## Finger Count Validation

| Scenario | Expected | Observed | Pass/Fail | Notes |
|---|---|---|---|---|
| 0 fingers | all unchecked |  |  |  |
| 1 finger | item 1 checked |  |  |  |
| 2 fingers | items 1-2 checked |  |  |  |
| 3 fingers | items 1-3 checked |  |  |  |
| 4 fingers | items 1-4 checked |  |  |  |
| 5 fingers | items 1-5 checked |  |  |  |

## Finger Count Tuning Record

- Script used (`HandFingerCountInput.js` or `_tuned.js`):
- Hand side:
- Profile (Near/Standard/Far/Custom):
- stableFrameThreshold:
- Custom thresholds enabled (true/false):
- Thumb threshold:
- Index threshold:
- Middle threshold:
- Ring threshold:
- Pinky threshold:
- Notes on best-performing setup:

## Stability Checks

- Lighting: bright / medium / dim
- Distance bands: near / mid / far
- Hand orientation: palm front / side / angled
- Jitter observed:
- False positives/negatives:
- Recovery behavior (occlusion -> reacquire):

## Reset Interaction

- Reset input used (gesture/voice/button):
- Trigger reliability:
- Latency to reset:
- Any accidental resets:

## Performance Snapshot

- Approx FPS:
- Perceived input latency:
- Thermal notes:
- Battery impact (short run):

## Bugs / Follow-ups

- [ ]
- [ ]
- [ ]

## Decision Summary

- Keep:
- Change:
- Next test focus:

## Vuzix vs Snap Comparison Snapshot

- Same task performed:
- Vuzix behavior (camera inference):
- Snap behavior (native hand/gesture input):
- Which felt faster:
- Which felt more stable:
- Demo takeaway sentence:
