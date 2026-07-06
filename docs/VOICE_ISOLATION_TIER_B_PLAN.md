# ClearCall — Tier B: "Isolate a voice" (target-speaker extraction) — implementation plan

Status: **seam shipped, models not built.** The app has a working seam (see "How it plugs in"); this doc is the plan to build the two models that make it actually filter. Until then the "Isolate a voice · beta" setting falls back to DeepFilterNet3 general suppression, and the enrollment button stays "coming soon."

## Goal

On a caller's **mic uplink**, keep only the voice matching a **provided reference sample** — the phone user's own voice by default, but any speaker whose sample is enrolled — and remove all *other* voices plus background noise. This is what Teams "voice isolation" / Google VoiceFilter-Lite do. It goes beyond DeepFilterNet3 (Tier A), which removes non-speech noise but not *other people's speech*.

Because ClearCall cleans each sender's outgoing audio (per-sender), the target is always a **near-end** voice (someone speaking into this phone). You cannot target the far-end person — they aren't in your mic. Their phone isolates their own voice on their side.

## Why it's a real ML build, not a library drop-in

There is no free, ready-made, real-time, on-device target-speaker-extraction model to bundle (DeepFilterNet is general NS; it can't do target extraction). Two models must be produced developer-side, then dropped into the existing seam:

1. **Speaker encoder** — reference sample → fixed-length d-vector (voice fingerprint). *Tractable*: strong pretrained models exist.
2. **Target-speaker separation model** — (mic frame + target d-vector) → only that speaker, streaming/real-time. *The hard part*: needs training, a real-time-capable architecture, and mobile conversion.

## How it plugs into the app (the seam already exists)

Two `TODO(P4-real)` hooks, no caller changes needed:

- `audio/TargetVoiceProfile.computeEmbedding(referenceSample16kMono: FloatArray): FloatArray?`
  → run the **speaker encoder**, return an L2-normalized `FloatArray(EMBEDDING_DIM)`.
- `audio/SpeakerConditionedProcessor.processHop(hop: FloatArray)`
  → run the **separation model** conditioned on `targetEmbedding` instead of the current `fallback.processHop(hop)` (DFN3).

Already wired around these: `SuppressionEngine.TARGET_SPEAKER`, the `CaptureProcessorBridge` (10 ms float frames, ring buffers, watchdog, A/B bypass — the model gets clean fixed-size hops and any failure safely bypasses), `Prefs.targetVoiceName/targetVoiceEmbedding` (local-only storage), and the Settings chooser. The bridge already resamples to whatever the model wants (see DfnNoiseProcessor for the 48 kHz pattern).

## Model choices (evaluate at build time)

**Speaker encoder (sample → d-vector):**
- **ECAPA-TDNN** (SpeechBrain `spkrec-ecapa-voxceleb`) — SOTA-ish, ~20 MB, exportable to ONNX/TFLite. Recommended.
- Resemblyzer / GE2E d-vector — smaller/simpler, lower accuracy.
- Output: 192–256-d vector; set `TargetVoiceProfile.EMBEDDING_DIM` to match.

**Target-speaker separation / masking (mixture + d-vector → target):**
- **VoiceFilter-Lite** (Google) — designed exactly for on-device streaming enhancement (mask over log-mel/STFT, conditioned on d-vector). No official weights — reimplement + train.
- **TD-SpeakerBeam** (time-domain SpeakerBeam) — strong open recipe (Asteroid/ESPnet), heavier; may need pruning for phone real-time.
- **Personalized DeepFilterNet** — extend DFN with a speaker-conditioning branch; benefits from reusing the DFN runtime already integrated.
- Prefer a **masking / spectral** model (cheaper, phase-preserving) over full time-domain for a phone real-time budget.

## Training (developer-side, one time — users never train)

- **Hardware**: the GTX 1070 (8 GB) is enough for a compact streaming model; rent a bigger GPU if scaling up.
- **Data**: clean speech (LibriSpeech / VoxCeleb / VCTK) + interfering speakers + noise (DNS-Challenge, MUSAN, WHAM!). Build mixtures: `target + interferer(s) + noise`, condition on the target speaker's *separate* enrollment utterance's d-vector. RIRs for reverb realism.
- **Objective**: SI-SDR (time-domain) or spectral/mask loss (magnitude + optional phase-sensitive); add an asymmetric loss (VoiceFilter-Lite trick) to bias toward *not* suppressing the target.
- **Constraints to train for**: streaming (causal or small look-ahead), 48 kHz (or 16 kHz then upsample — match the app's capture path), the 10 ms hop the bridge feeds.
- **Metrics**: SI-SDRi and target-vs-interferer suppression on held-out mixtures; word-error-rate of the target through the model; a subjective A/B.

## On-device runtime

- **Format**: TFLite (LiteRT) or ONNX Runtime Mobile, NNAPI/GPU delegate. Bundle like DFN's AAR ships `libdf.so` — model weights in `assets/`, loaded once (async, like `NativeDeepFilterNet`).
- **Budget**: each hop must finish well inside the realtime budget (the bridge watchdog auto-bypasses if not — see `CaptureProcessorBridge`). Profile on a real device; the `NoiseDebugOverlay` already shows µs/hop + p95.
- **Encoder** runs once per enrollment (not per frame) — cost irrelevant.

## Enrollment UX (build alongside the model)

- Default **self-enroll**: a one-time "record yourself for ~15–30 s reading a prompt" screen → `computeEmbedding` → store as the target profile (name defaults to the user's own name). Re-recordable if a different person will use the phone.
- Wire the existing disabled "Add a voice sample" button in `SettingsScreen` to this flow; show the stored profile name.
- Keep everything on-device: sample + d-vector never leave the phone (privacy is a selling point).

## Multi-target (later, optional)

Design already allows storing multiple named profiles (make `Prefs` hold a list). Keeping N voices = run the separation model N times per hop and mix, so cost scales linearly — cap at ~2–3 targets to stay within the realtime budget. Averaging d-vectors into one "group" fingerprint does **not** work (it matches none cleanly). Ship single-target first.

## Step-by-step to make it live

1. Pick + export the **speaker encoder** to TFLite; implement `TargetVoiceProfile.computeEmbedding`; set `EMBEDDING_DIM`.
2. Build the **enrollment screen**; store the d-vector via `Prefs.targetVoiceEmbedding`.
3. Train the **separation model** (start from an open recipe; VoiceFilter-Lite-style masking); convert to TFLite; bundle in `assets/`.
4. Implement `SpeakerConditionedProcessor.processHop` to run it conditioned on `targetEmbedding` (mirror `DfnNoiseProcessor`'s buffer/resample handling); keep the DFN3 fallback when no target is enrolled.
5. Flip the Settings copy from "coming soon" to enrolled/active; keep the A/B overlay for tuning.
6. Verify on two real devices: enroll voice A on the caller; with another person talking nearby, confirm only A reaches the callee; A/B against DFN3 and against bypass; watch CPU/latency in the overlay.

## Guardrails

- Never regress Tier A: with no target enrolled or on any model error/timeout, fall back to DFN3 (already the bridge's behavior).
- Keep the model optional and toggleable (`SuppressionEngine`) — some users/devices will prefer general suppression.
