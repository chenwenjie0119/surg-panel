# Architecture and implementation notes

This document describes how SurgPanel is built and how each on-screen element is wired to real state. It is the condensed reference for reviewers who want to know what is actually implemented rather than what a demo GIF suggests.

## 1. Stack and build targets

| Item | Value |
| --- | --- |
| Language | Kotlin (no JNI, no C++) |
| UI | Jetpack Compose, wrapped by the PICO Spatial SDK window DSL |
| App id | `com.cwj.surgpanel` |
| compileSdk / targetSdk / minSdk | 35 / 35 / 35 |
| ABI | `arm64-v8a` |
| Renderer | `box.usdz` scene asset, planar window containers |

The whole app is one `SpatialApplication` process. Spatial windows are not separate processes, so every panel shares one in-memory state graph instead of exchanging IPC messages.

## 2. Window layout

`Main.kt` declares one default container plus four `WindowContainer`s:

| Container | Form | Anchor |
| --- | --- | --- |
| `surgical-console` | default | first window, placed by the system |
| `imaging-panel` | planar, 860 × 700 dp | `Placement.right(console, 26.dp)` |
| `vitals-panel` | planar, 780 × 440 dp | `Placement.top(console, 26.dp)` |
| `scope-panel` | planar, 720 × 640 dp | `Placement.left(console, 26.dp)` |
| `focus-panel` | planar, 940 × 760 dp | `Placement.top(imaging, 30.dp)`, falls back to the right of the console |

All containers use `ContainerResizeType.ContentMinSize`, so a panel grows with its content but never shrinks below its composable minimum. Users can still grab and reposition any window; the `Placement` value only defines the initial arrangement.

## 3. Shared state

`core/SurgicalSession.kt` is a single Kotlin `object` holding the case identity, the timeline, the link policy, the imaging parameters, the vital signs, the endoscope parameters and the current incident. Fields are backed by `mutableStateOf`, `mutableIntStateOf` and `mutableFloatStateOf`, and window owners are registered as global snapshot observers, so a write from the console recomposes every panel inside the same frame.

Enum types carry the domain vocabulary: `LinkMode` (full / focus / off), `Modality` (CT in HU, MRI T2 in SI), `AlarmLevel` (none / watch / critical), `ProceduralPhase` (five phases from checklist to closure), `LayoutPreset` and `AppMode`.

## 4. Surgical clock

`platform/SessionClock.kt` runs one daemon thread with a fixed 100 ms tick. Every tenth tick advances `elapsedSec`; every tick recomputes vitals and evaluates the alarm.

* **Vitals source.** In product mode the clock first asks `InstrumentRegistry.vitals` for a frame; in demo mode, or when no instrument is attached, it falls back to the local model.
* **Event cycle.** The local model replays a 140 s cycle: bleeding from 40 s to 68 s, then a 20 s linear recovery, then a stable baseline with slow drift. The cycle repeats so the demo can be run back to back.
* **Alarm grading.** Entry and exit thresholds differ (critical in: HR > 112 or SBP < 90 or SpO₂ < 94; critical out: HR ≤ 106 and SBP ≥ 96 and SpO₂ ≥ 95, with a matching watch band). This hysteresis stops the status light from chattering around a single threshold.
* **Phase advance.** Phases switch at 20 s, 140 s and 220 s and are written to the event log.
* **Link behaviour.** In full-link mode a critical alarm forces the imaging slice to #46 and raises `linkedSliceHighlight`.

Every alarm transition is also written to logcat under the `SurgeryClock` tag with the mode, time, vitals and whether the frame came from an instrument. That tag is the primary way to verify behaviour in the emulator, because 2D `adb input tap` coordinates do not map onto spatial window hit testing.

## 5. Signal synthesis

`core/SignalEngine.kt` exposes pure functions only, so all panels can sample the same curve at any timestamp and stay phase-aligned:

* `ecgPhase` sums four Gaussian pulses for P, QRS and T.
* `abpPhase` combines a systolic upstroke, an exponential decay and a dicrotic notch.
* `respPhase` is a 12 breaths/min sine.
* `jitter` is a deterministic hash-based noise used for baseline drift.
* `tissueDensity` is the 3D field sampled by the slice renderer.

## 6. Imaging panel

`ui/imaging/ImagingPanel.kt` renders a CT or MRI axial slice:

1. `renderSlice` bakes a 96 × 96 `ImageBitmap`. Each pixel samples `tissueDensity`, converts to HU (CT) or signal intensity (MRI), applies the window width / level mapping and a smoothstep curve, then writes an ARGB value. The bitmap is cached with `remember` keyed on modality, slice index, width bucket and level bucket, so scrubbing does not re-sample pixels per frame.
2. The canvas draws that bitmap through a zoom matrix: `side = min(width, height) * 0.94f * zoom`, centred on the canvas. The image and its frame are drawn inside `clipRect`, so at 1.45× the slice is cropped by the window instead of spilling over the panel edge.
3. Overlays are derived from the same geometry: crosshair at the image centre, ROI box with breathing-phase pulse when ROI lock is on, orientation letters R/L/A/P, a 5 cm scale bar, the slice counter and the W/L readout. All text anchors pass through `clampX` / `clampY`, so labels remain readable when the scaled image exceeds the viewport.

The toolbar drives modality, slice ±1, window width ±50, window level ±10, ROI lock and zoom (0.15 steps, cycling back to 1.0). The title bar shows the current zoom with two decimals.

## 7. Endoscope panel

`ui/scope/ScopePanel.kt` draws the surgical field procedurally, frame by frame:

* Tissue depth is a three-stop radial gradient; mucosal folds and vessels are `Path` strokes whose curvature and phase come from the clock.
* Two forceps shafts enter from the lower corners with a slow bite oscillation and jaw markers.
* A 24-mark graduation ring, a field-edge ring and a centre crosshair give the optical HUD.

Two parameters are consumed by the renderer rather than only stored:

* **`scopeZoom`** scales the field radius: `r = min(width, height) * 0.45f * zoom`. Zooming therefore magnifies the anatomy, the forceps and the gradation ring together, exactly like an optical zoom on the camera head. Annotation anchors are clamped to the canvas so the "30° scope" and light-spot readouts stay visible at every magnification.
* **`autoFluorescence`** switches the whole palette from white-light tissue tones to a near-infrared pseudo-colour scheme (teal base, bright mucosal folds, green vessels, darker instrument shafts) and additionally draws a pulsing green enrichment halo over the lesion area with a radial gradient. Both the colour change and the extra signal layer are recomputed per frame, so toggling the chip in the panel is immediately visible.

## 8. Intelligent incident handling

`core/AiController.kt` orchestrates the response chain on a background thread and publishes each stage to `SurgicalSession`:

| Stage | Duration | Effect |
| --- | --- | --- |
| `LOCALIZE` | 500 ms | incident region flagged, focus window animates in |
| `ENLARGE` | 900 ms | lesion area virtually enlarged in front of the surgeon |
| `SECTION` | 1000 ms | imaging slice jumps to `incident.targetSlice` |
| `ADVISE` | 700 ms | ordered action list and confidence published |

`focusAnim` steps from 0 to 1 every 20 ms so the focus window can interpolate its own presentation. A monotonically increasing `generation` counter is checked before every stage, so a cancelled or restarted run can never write stale results.

There are two entry points:

* `triggerDemo()` — the console button. It injects the bleeding scenario, runs the pipeline and holds the state for 16 s before stabilising, which makes the whole chain repeatable for a live walkthrough. Pressing it again while running collapses the scenario.
* `onAlarmTriggered(level)` — product mode only. A critical threshold crossing starts the same pipeline without operator input.

`onResolved(fromData)` clears the chain. When it is called from the data path in demo mode it returns early, because the vitals recovering on their own must not pull the conclusion away from the surgeon mid-read.

## 9. Inference gateway

`data/AiAdvisor.kt` defines one interface with two implementations.

* `LocalAiAdvisor` is a rules engine over vitals trend plus current slice. It has three branches: active bleeding (`BLD-S7`, five ordered actions, confidence 92), isolated hypoperfusion from high pneumoperitoneum pressure (`PERF-N2`, three actions, confidence 74) and a stable observation case (`OBS-N0`, confidence 66). Each branch returns region, cause, target slice, evidence items with severity and ordered actions with time budgets.
* `RemoteAiAdvisor` posts JSON (`case`, `patient`, `procedure`, `vitals{hr,sbp,dbp,spo2,etco2}`, `imaging{modality,slice}`, `phase`, `trigger`) to a configured endpoint with a bearer token and an optional `X-Model` header. Timeouts are 2.5 s connect and 3 s read.

`AiGateway` holds the endpoint, key and model as volatile fields. When they are blank the local engine is used directly; when the remote call throws, the local engine runs and reports itself as `本地兜底 · 远程不可达`, so the chain never stalls during surgery. No vendor name, product name or model identifier is compiled into the app.

## 10. Instrument layer

`data/InstrumentPort.kt` declares `VitalSignSource`, `ImagingSource` and `EndoscopeSource` (open / poll / close) plus a `VitalsFrame` record and the `InstrumentRegistry` singleton. A device integration only has to register an implementation; panels and the inference request builder read the same session fields, so no UI code changes. Unregistered sources report `未接入` in the console.

## 11. Operating modes

`AppMode` switches the data origin and the trigger policy:

| | Demo | Product |
| --- | --- | --- |
| Vitals | local replay | instrument frames, local fallback |
| Trigger | console demo button | automatic on critical alarm |
| Auto-resolve | ignored from data path | applied |
| Purpose | repeatable walkthrough | wired to the OR |

The console exposes mode switching, window visibility, link policy, shared imaging parameters, event injection, timeline reset, phase display, patient identity, instrument status and the live event stream (capped at 20 entries).

## 12. Build and verification

`./gradlew :app:compileDebugKotlin --rerun-tasks` compiles the module from scratch; the current tree builds with zero warnings and zero errors. `:app:assembleDebug` produces `app-debug.apk` (arm64-v8a) for the emulator and the device.
