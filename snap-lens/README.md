# Snap Lens Starter Workspace

This folder is reserved for Lens Studio project artifacts related to the Spectacles implementation.

## Purpose

- Keep Snap Lens work isolated from the Android `app/` module.
- Track only source assets and scripts that are safe to version.
- Maintain a predictable handoff path for collaborators.

## Recommended Layout

- `snap-lens/README.md` (this file)
- `snap-lens/project/` (Lens Studio project files)
- `snap-lens/assets/` (tracked custom assets/scripts)
- `snap-lens/exports/` (optional build/export metadata)

Included starter scripts:

- `snap-lens/assets/scripts/ChecklistStateController.js`
- `snap-lens/assets/scripts/GestureChecklistInput.js`
- `snap-lens/assets/scripts/FingerCountAdapter.js`
- `snap-lens/assets/scripts/HandFingerCountInput.js`
- `snap-lens/assets/scripts/HandFingerCountInput_tuned.js`

Create folders as needed once the Lens Studio project is initialized.

## Quick Start (Lens Studio)

1. Open Lens Studio and create a new Spectacles-compatible Lens project.
2. Save the project under `snap-lens/project/`.
3. Implement:
   - checklist state mapping (`1..5`) with `ChecklistStateController.js`,
   - direct hand finger counting using `HandFingerCountInput.js` (SIK hand joints),
   - tuned hand finger counting using `HandFingerCountInput_tuned.js` (profiles + per-finger thresholds),
   - gesture fallback using `GestureChecklistInput.js` (pinch/grab/targeting),
   - optional adapter mode using `FingerCountAdapter.js`.
4. Record each device run in `docs/snap-spectacles/notes.md`.

## Scene Wiring (Starter)

1. Create a SceneObject `ChecklistController` and attach `ChecklistStateController.js`.
2. In Inspector, wire:
   - `countText`, `statusText`, `modeText` to text components,
   - `tick1..tick5` to checklist visual SceneObjects.
3. Create a SceneObject `GestureInput` and attach `GestureChecklistInput.js`.
4. Set `checklistController` input to the `ChecklistController` script component.
5. Create a SceneObject `FingerInput` and attach `HandFingerCountInput.js`.
6. Wire `checklistController` on `HandFingerCountInput.js` to `ChecklistController`.
7. For calibrated demos, swap to `HandFingerCountInput_tuned.js` on `FingerInput`.
8. Keep `GestureInput` enabled as fallback, or disable it to test pure finger count.
9. (Optional) Add `FingerCountAdapter.js` on a SceneObject and wire the same controller.
10. Run on connected Spectacles and validate 0..5 checklist mapping.

## Tuning Workflow (Recommended)

Use `HandFingerCountInput_tuned.js` and tune in this order:

1. Set `profile`:
   - `Near (30-45cm)` for closer hand demos,
   - `Standard (45-70cm)` for normal use,
   - `Far (70-100cm)` for farther demos.
2. Set `stableFrameThreshold` to trade off responsiveness vs stability.
   - lower (`2-3`) = faster but noisier,
   - higher (`4-6`) = steadier but slower.
3. If one finger class is unreliable, enable `useCustomThresholds`.
4. Adjust only the problematic finger threshold first:
   - increase threshold => stricter “extended” detection,
   - decrease threshold => easier “extended” detection.
5. Record final values in `docs/snap-spectacles/notes.md`.

## Version Control Guidance

- Commit source scripts, config, and authored assets.
- Avoid committing caches, temporary build outputs, or machine-specific files.
- Keep commits small and tied to a single behavior change.

## Definition of Ready for First Demo

- Stable finger counting for 1..5 in normal indoor lighting.
- Checklist visual state correctly follows detected count.
- Reset interaction reliably clears checklist state.
- At least one completed test entry in `docs/snap-spectacles/notes.md`.

## USB Validation Notes

- Use Lens Studio preview with connected Spectacles over USB.
- Prioritize on-device validation (editor behavior can differ for hardware input modules).
- Log each run in `docs/snap-spectacles/notes.md`.
