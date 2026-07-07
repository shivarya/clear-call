# ClearCall — Tier B "Isolate a voice" + clean-uplink-with-earbuds

Status: **v1 SHIPPED (2026-07-07), zero-training.** Both features below are implemented and
emulator-verified; real-device (Pixel + earbuds + second phone) validation is the remaining step.
The original trained-separation-model design is preserved as the **v2 appendix** at the bottom —
it drops into the same seam if/when the gating v1 isn't enough.

## What shipped

### 1. "Phone mic with earbuds" call mode (the earbud-noise fix)

**Problem:** with any Bluetooth earbuds on a call, Android starts SCO — the *earbud's* mic
captures the voice (poor hardware, narrowband link, lots of room noise) and the phone's far
better mic + our DFN3 cleaning are bypassed at their best.

**Fix (we own the pipeline, so we can):** when earbuds are connected at call setup and
`Prefs.phoneMicWithBuds` is on (default), the LiveKit room is created with
`AudioType.MediaAudioType` instead of communication audio — no SCO, so the far end plays
through the buds over **A2DP** (better quality than SCO) and capture falls to the **phone's
built-in mic** (48 kHz, DFN3's native path). Works with any earbuds, brand-irrelevant.

- `LiveKitSessionManager.connect(..., useMediaAudio)` builds the overrides; LiveKit's
  `AudioSwitchHandler` deliberately skips call routing for non-communication audio modes, so
  nothing fights the system's media routing (verified in the 2.26.1 sources).
- `CallManager.connectLiveKit` decides per call (`prefs.phoneMicWithBuds && isBluetoothAudioConnected()`),
  publishes `CallState.phoneMicMode`; the in-call UI shows a "Phone mic · earbuds audio" chip and
  hides the earpiece/speaker toggle (the system owns routing in media mode).
- Mode is fixed for the duration of a call; buds connecting mid-call just start receiving A2DP.
- **Verify on hardware:** echo (media mode loses the hardware voice-call AEC; WebRTC's software
  AEC still runs and closed buds leak little — test far-end speech loud in buds), A2DP latency
  (~100–300 ms playback side), volume keys control media volume, buds disconnecting mid-call
  drops output to the loudspeaker (privacy surprise — known v1 tradeoff).

### 2. "Isolate a voice · beta" — speaker-gated suppression

Keep only the voice matching an **enrolled reference sample** (any speaker, not just the owner);
remove background noise always, and duck *other people's voices* when the enrolled voice isn't
the one speaking. All on-device, all from existing open models, **no training**:

```
mic → DFN3 (noise removal, fullband, unchanged)
        ├→ analysis copy @16 kHz → 1 s rolling lock-free ring
        │    every 250 ms (background coroutine, never the audio thread):
        │    Silero VAD → anyone speaking? → SpeakerEncoder (WeSpeaker CAM++) → cosine vs
        │    enrolled d-vector → hysteresis state machine (open ≥0.45 / close ≤0.30 +
        │    2 s hold-open since last strong match)
        └→ per-sample smoothed gain: open 0 dB / closed −35 dB (attack 20 ms, release 250 ms)
```

- **Models** (fetched by `mobile/scripts/fetch-ml-assets.ps1`, gitignored):
  - sherpa-onnx AAR v1.13.3 (static-link-onnxruntime variant, Apache-2.0) — provides both the
    `SpeakerEmbeddingExtractor` and Silero `Vad` Kotlin APIs; 16 KB-page-aligned `.so`s (verified).
  - `wespeaker_en_voxceleb_CAM++.onnx` — speaker encoder, **dim=512** (verified at runtime), ~28 MB.
  - `silero_vad.onnx` — VAD (MIT), ~0.6 MB.
- **Enrollment** (`ui/EnrollVoiceScreen`, wired from Settings): read a ~20 s paragraph
  (`EnrollmentRecorder`: AudioRecord VOICE_RECOGNITION 16 kHz mono → float). Quality gate
  (`VoiceEnrollment.checkQuality`): ≥12 s voiced, <1 % clipping, and **self-consistency**
  (embeddings of the two halves must cosine ≥0.70 — catches noisy/multi-talker samples). The raw
  WAV is kept app-private (`filesDir/voice_enrollment/`) with `Prefs.targetVoiceModelVersion`, so
  a future encoder swap silently recomputes the stored d-vector (`VoiceEnrollment.recomputeIfStale`
  at app start) — users never re-enroll. Nothing ever leaves the device.
- **Fail-open everywhere:** no enrollment / model load failure / non-integer capture rate /
  any tick exception → gate stays open, i.e. plain DFN3. The bridge's watchdog + exception
  bypass ("NS can never break a call") is unchanged; `processHop` only feeds a ring and applies
  a gain (no allocation, no locks, no inference on the realtime thread).
- **Honest v1 limits** (accepted): an interferer's first ~0.5 s leaks before the gate closes;
  simultaneous overlap is not separated (background talker rides at DFN3 level while the target
  speaks); same-gender similar voices are the hard case — retune `SpeakerGate`'s thresholds with
  the debug overlay (shows live `gate/vad/cos/gain`).
- **Fixed en route:** `NoiseSuppression.captureProcessorFor` now applies
  `prefs.targetVoiceEmbedding` on *every* call setup, not only when the processor is rebuilt —
  re-enrolling while Isolate was already selected used to never reach the live processor.

### Emulator verification (2026-07-07, x86_64, API 36)

- Encoder loads, dim=512, deterministic (cos(same input)=1.0), discriminates (cos(buzz,noise)=0.19).
- Silero VAD loads; correctly scores synthetic non-speech low (0.02–0.06).
- Full chain `SpeakerConditionedProcessor` (DFN3 @16 kHz ×3 resample + gate feed + ramp):
  **454 µs/hop avg vs the 10 000 µs watchdog budget**.
- Enrollment UI: record → stop → quality gate correctly rejects a silent take → retry.
- Release APK builds; `zipalign -c -P 16` passes; 202 MB universal (arm64-v8a+x86_64; ship as AAB).
- `MlSelfTest` (debug builds only) logs all of the above at app start — keep until the Pixel
  bring-up is done, then delete.

### Remaining: real-device validation (needs the Pixel + Realme buds + a second phone)

1. Phase A A/B in a noisy room: call with buds, toggle off (SCO/earbud mic baseline) vs on
   (48 kHz phone mic + DFN3, buds on A2DP) — judge at the callee; overlay `rate` shows which
   path is live (SCO 16 kHz vs builtin 48 kHz). Echo/latency/volume checks above.
2. Enroll a real voice; two same-voice enrollments should cosine ≥0.8, different voices ≤0.5
   (log line in `VoiceEnrollment`).
3. Gate matrix: target alone (no regression) · target silent + other talker (ducks ≤0.5 s) ·
   both talking (target survives via hold-open) · TV/music (DFN3 handles) · same-gender
   interferer (tune θ) · Hindi + English · 10 min endurance (overlay p95, battery).
4. Retune `SpeakerGate.COSINE_OPEN/CLOSE/HOLD_OPEN_MS/FLOOR_DB` from overlay observations.

---

## v2 appendix — trained target-speaker separation (deferred)

v1 gates in time; it cannot separate simultaneous overlap. The upgrade path (researched
2026-07, all facts verified then):

- **No off-the-shelf streaming on-device TSE model with usable weights exists.** WeSep
  (wenet-e2e, toolkit with pBSRNN/pDPCCN/Spex+ recipes + WeSpeaker conditioning + on-the-fly
  mixing) has no released checkpoints; UW's LookOnceToHear (CHI'24; 8 ms chunks in 6.24 ms on
  embedded CPU) is binaural-input and CC BY-NC-SA (non-commercial — architecture reference only,
  never ship its weights); pDeepFilterNet2 (Orosound/Télécom Paris, ICASSP'25) published no code.
- Plan if pursued: train a **causal separator at 16 kHz** (WeSep pBSRNN recipe made causal, or a
  VF-Lite-style uni-GRU masker; 10 ms hop) conditioned on **the same WeSpeaker encoder the app
  ships** (embedding-space compatibility is why CAM++ was chosen for v1). Data: LibriSpeech +
  VCTK + DNS5 personalized-track + MUSAN + RIRs (avoid WHAM! — CC BY-NC). Train on the GTX 1070
  for bring-up, rent a 4090 (~$30–60) for the real run. Export ONNX with explicit recurrent-state
  tensors; verify streaming parity vs offline; INT8 dynamic quant; run via ONNX Runtime Mobile
  (or through the already-bundled sherpa onnxruntime). It replaces the gate inside
  `SpeakerConditionedProcessor.processHop` with zero caller changes; enrolled voices carry over
  thanks to the WAV + `targetVoiceModelVersion` recompute hook.
