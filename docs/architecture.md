# Architecture and implementation notes

This document describes how SurgPanel is built and how each on-screen element is wired to real state. It is the condensed reference for reviewers who want to know what is actually implemented rather than what a demo GIF suggests.

## 1. Stack and build targets

| Item | Value |
| --- | --- |
| Language | Kotlin (no JNI, no C++) |
| UI | Jetpack Compose, wrapped by the PICO Spatial SDK window DSL |
| App id | `com.cwj.surgpanel` |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| ABI | `arm64-v8a` |
| Renderer | `box.usdz` scene asset, planar window containers |

The whole app is one `SpatialApplication` process. Spatial windows are not separate processes, so every panel shares one in-memory state graph instead of exchanging IPC messages.

`minSdk` is 26 rather than 35 on purpose. PICO Spatial SDK 6.2.8 requires Android 8.0 (API 26) at runtime, so pinning `minSdk` to 35 would have made the APK installable only on API 35 hardware for no technical reason. `targetSdk` stays at 35 because the SDK's window DSL and the Compose toolchain are validated against that level. The merged manifest of the verified build reports `minSdkVersion="26"` and `targetSdkVersion="35"`.

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

`platform/SessionClock.kt` runs one daemon thread driven by a monotonic schedule. Every tick is scheduled against an absolute grid on `System.nanoTime()`: tick *n* is due at `anchor + n × STEP_MS` (`STEP_MS` = 100 ms), so the work done inside a tick is subtracted from the next wait instead of being added to the period and lateness cannot accumulate. Each tick sleeps to one millisecond before its grid point and then yields until the deadline — `Thread.sleep` alone is only accurate to a few milliseconds, and a per-tick late wake-up is exactly what makes waveforms and digits drift apart. If the thread is descheduled for longer than `SLACK_NS` (five ticks = 500 ms) the anchor is re-based on the current reading, which trades one skipped beat for a bounded re-alignment rather than a burst of catch-up ticks.

The surgical time is a tick index, not wall time: the timeline is `tickIndex × STEP_MS`, and the sub-second phase the panels see is derived as `(tickIndex × STEP_MS % 1000) / STEP_MS`, so the phase is a consequence of how many beats have actually been committed rather than of how often the thread happened to wake up. While the session is paused the tick index is frozen and the thread yields a whole step per iteration — a bare `continue` would compute a zero wait and spin a core at full speed for the whole pause — so the timeline stalls and resuming continues from the frozen beat instead of replaying a backlog or jumping the phase. A write to `elapsedSec` from outside (timeline reset) is detected by comparing against the last committed second and re-seeds both the tick index and the anchor. State writes are posted to the main thread (`mainHandler.post { update(phase) }`), which keeps snapshot writes off the clock thread.

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

The stage animation is owned by the main thread, not by the pipeline. Each stage posts exactly one message; a `Handler`-driven ticker then advances `focusAnim` from 0 to 1 in `STAGE_TICK_MS` (20 ms) steps, so progress is paced by the same thread that renders it. The pipeline thread only waits on a `CountDownLatch` for the stage to finish, bounded by `durationMs + 2 s` so that a blocked main thread cannot hang the pipeline forever. The previous shape posted one message per 20 ms from the worker — close to a hundred messages per chain, queued ahead of the AI results — and a cancelled run could still have its queued ticks overwrite the next run's progress. Every Compose-backed field (`aiRunning`, `incident`, `aiEngine`, `aiLatencyMs`, `sliceIndex`, `focusStage`, `focusAnim`) is written on the main thread, and a monotonically increasing `generation` counter is checked before every stage and on every tick: when it moves, the ticker stops itself and releases the pipeline, so a cancelled or restarted run can never write stale results.

There are two entry points:

* `triggerDemo()` — the console button. It injects the bleeding scenario, runs the pipeline and holds the state for 16 s before stabilising, which makes the whole chain repeatable for a live walkthrough. Pressing it again while running collapses the scenario.
* `onAlarmTriggered(level)` — product mode only. A critical threshold crossing starts the same pipeline without operator input.

`onResolved(fromData)` clears the chain. When it is called from the data path in demo mode it returns early, because the vitals recovering on their own must not pull the conclusion away from the surgeon mid-read.

`ai/AiNarrator.kt` is the only other thread-touching piece of the advisory path. It keeps at most one `drain()` coroutine, guarded by a lock it shares with `reset()`: deciding whether the worker is still alive and starting `drain(token)` both happen inside the same `synchronized(workerLock)` block, and `reset()` cancels and clears the handle under that same lock. Without the lock a line could be appended in the window where the worker had already drained the queue but had not yet completed, so the line sat in the buffer with nobody consuming it and a suggestion would occasionally never appear; the unlock/cancel race could also let two `drain()` coroutines consume the same queue. The coroutine detaches its own handle through `invokeOnCompletion`, and only when the handle is still its own (`worker === job`), so a finishing worker cannot clear a newer one. Emission itself stays on the main thread.

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

## 12. Gaze dwell and pinch confirm

`platform/GazePinchController.kt` binds the two PICO Spatial input streams to the five panels:

| Stream | SDK surface | Use |
| --- | --- | --- |
| Eye | `EyeTrackingProvider` pose | cast the eye forward vector, intersect the five window planes, keep the nearest positive hit |
| HMD | `HMDTrackingProvider` pose | fallback ray origin and direction when eye data is not ready |
| Hand | `HandTrackingProvider` right / left pinch | read `isReady` plus pinch strength, arm on crossing `PINCH_THRESHOLD` (0.82) |

Dwell accumulates while the same panel stays under the ray and resets the moment the hit changes. `HOT_AT` (0.34 of `DWELL_MS` = 750 ms) turns the panel into `hotPanelId`, which is the highlight state consumed by `PanelFrame`. A pinch above threshold while a panel is hot calls the handler registered with `setConfirmHandler(panelId, ...)`, increments `confirmCount` and then stays locked for `CONFIRM_COOLDOWN_MS` (700 ms) so one physical pinch cannot fire two confirmations.

Eye access is a runtime permission (`com.picovr.permission.EYE_TRACKING`). `requestEyePermission` returns whether a system dialog was actually raised, `bindHostContext` supplies the activity context from the first composed panel, and the provider is restarted after the grant arrives so a cold launch with no permission still ends up streaming. When the eye provider reports a non-usable support state the controller falls back to the HMD ray and says so in `supportNote`; nothing in the panel logic assumes a working eye tracker.

## 13. Voice-issued alarm

`platform/VoiceInput.kt` wraps the system `SpeechRecognizer` (no cloud SDK, no vendor key) and is driven by the microphone button in the console. Recognised text goes to `VoiceCommandRouter.handle`, which normalises it (punctuation, spaces and filler removed) and matches `COMMAND_BLEED` ("立刻查看出血点") plus a small set of paraphrases. On a hit the router sets `alarm = CRITICAL`, marks `manualBleed = true`, pushes the focus window forward and records the event with source `voice`, so the red frame and the localization chain come from the same state as an instrument-driven alarm rather than a separate path.

Recognition failures are explicit: no service, permission denied and network or engine errors each produce their own event line, and a miss never silently changes alarm state.

## 14. Spatial radar and coordinate readout

`core/WindowPoseModel.kt` holds the designed world pose of all five windows (metres, right-handed, origin at the console centre, +X to the operator's right, +Y up, +Z towards the operator). `ui/common/MiniRadar.kt` draws that model as a top-down 2D radar: a 2.9 m span circle, a marker per window, and a two-letter code per marker. The gaze-hit and dwell-hot panels are lit in cyan.

The same file exposes `CoordStack`, which prints one live line per window in the form `CODE x.xx / y.yy / z.zz`. It appears under the radar in the console and the compact radar itself sits in the lower-left of the focus window, so the operator can read absolute panel positions without leaving the view. Both components only read state; they write nothing.

## 15. Determinism contract under load

`core/PerfGovernor.kt` watches battery state and slow-frame averages and exposes three visual levels (`visualHz` 60 / 30 / 20, `waveStride` 2 / 3.4 / 5). The contract is deliberately narrow:

1. `Level` carries nothing but redraw rate and waveform sampling stride. It has no access to alarms, phases or timers.
2. `SessionClock` never references `PerfGovernor`. Its 100 ms daemon tick and the alarm hysteresis run at full rate in every level, so the millisecond-scale alarm latency is identical at 60 Hz and at 20 Hz.
3. `AiController`, `SurgicalSession` and `SignalEngine` do not reference `PerfGovernor` either; degradation cannot reach the data path.
4. The latest numeric text and the red alarm frame are plain state reads, so they repaint on every change regardless of level. Only the canvas waveform resamples.
5. Values that are drawn as curves pass through `rememberSmoothFloat`, which interpolates towards the target with a frame-rate independent step, so a drop from 60 Hz to 20 Hz stays smooth instead of stepping.
6. All visual animation is driven by exactly one frame loop. `rememberVisualClock` no longer starts a `LaunchedEffect` per panel; it attaches to the private `VisualClockDriver` singleton, which keeps a single `withFrameNanos` loop, a single reference count and therefore a single `PerfGovernor.reportFrame` call per frame. Per-frame consumers that are not the clock itself — the radar trail sampler and `rememberSmoothFloat` — subscribe through `rememberFrameCallback`, which registers a listener on the same driver and unregisters it in `onDispose`; the listener table is only touched from the main thread and the callback always invokes the newest lambda through `rememberUpdatedState`. With the previous per-panel loops the same frame was reported up to five times (nine with the alert overlay), which biased the slow-frame average and could push the governor into a lower level on its own extra traffic; the separate trail and interpolation loops added further samples of the very same frame. `DisposableEffect` attaches on entry and detaches on dispose, so the loop stops when the last consumer leaves the composition.
7. Nothing suspends threads. There is no background scheduler, no wake lock juggling and no thread pool inside the governor; the alarm thread is never paused, throttled or parked.

## 16. Text resources

Every string the operator sees is resolved from `res/values/strings.xml`; no Compose file in `ui/` contains a hard-coded Chinese literal. The resource file holds 154 entries grouped by panel with a stable prefix per screen:

| Prefix | Screen |
| --- | --- |
| `console_*` | surgical console page and its event stream |
| `focus_*` | focus / zoom window |
| `imaging_*` | CT and MRI slice panel |
| `vitals_*` | vitals panel |
| `scope_*` | endoscope panel, including the Canvas annotations |
| `record_*` | case record panel |
| `narration_*` | AI narration block |
| `panel_*` | shared panel frame chrome |

Static labels use `stringResource(R.string.x)` in composables. Text that is produced inside a lambda, a `Canvas` draw pass or a click handler cannot call a composable function, so those sites either hoist the value into a local at the top of the composable or read `LocalContext.current.resources.getString(R.string.x, arg)` directly. Formatted entries use positional placeholders (`%1$s`, `%1$.2f`) so argument order is explicit and no `String.format` with an inline pattern remains in the UI layer.

The default locale is Chinese; the resource directory is `values/` without a qualifier, so a device set to any other locale still renders the Chinese strings rather than blanks. Domain vocabulary inside `core/` and `data/` (alarm messages, advisor rationales, log lines, demo scenario data) is intentionally kept as code literals: it is generated data and diagnostic output, not UI copy, and translating it per-locale would not change what is shown.

## 17. Build and verification

`./gradlew :app:compileDebugKotlin --rerun-tasks` compiles the module from scratch; the current tree builds with zero warnings and zero errors. `:app:assembleDebug` produces `app-debug.apk` (arm64-v8a) for the emulator and the device.

The recorded verification run used JDK 21.0.2, Android SDK platform 35 with build-tools 35.0.1 and the checked-in Gradle 8.13 wrapper on a Windows 11 host. A full `clean :app:assembleDebug` finished `BUILD SUCCESSFUL` in 1 m 17 s with all 37 tasks executed, and the incremental rebuild after the resource cleanup finished in 51 s. The emitted `app-debug.apk` is 14.1 MB and its manifest reports `minSdkVersion="26"`, `targetSdkVersion="35"` and the label `空间手术面板`. Every change described in this document was followed by a real build before it was accepted; the two changes that failed to compile (a `stringResource` call from a non-composable lambda, and a duplicate `console_59` key) were fixed or reverted rather than left described as done. When the Kotlin compile daemon cannot create its probe file under a non-ASCII user path, Gradle falls back to in-process compilation and the build still succeeds; that warning is environmental, not a code defect.

The determinism changes described above (§4 clock grid, §8 main-thread stage ticker and `AiNarrator` lock, §15 single frame loop) were each followed by both variants of the build on a Windows 11 host with JDK 21.0.2: `:app:assembleDebug --rerun-tasks` finished `BUILD SUCCESSFUL in 1 m 2 s` with all 36 tasks executed and zero compiler warnings, producing `app-debug.apk` at 14,824,053 bytes (14.13 MB); `:app:assembleRelease --rerun-tasks` finished `BUILD SUCCESSFUL in 1 m 30 s` with all 50 tasks executed, producing the signed `app-release.apk` at 11,187,249 bytes (10.67 MB), verified by `apksigner verify --print-certs` (certificate SHA-256 `4827573d17eb9dc13f3dd51b4f0dce7ab4a548046ae9cb800bd90373cea1d0ca`). Both APKs keep `versionCode=4`, `versionName=1.3.0`, `minSdk=26`, `targetSdk=35`.
