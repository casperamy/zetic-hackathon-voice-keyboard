## Phase 2 Latency Optimization Plan (No ZETIC-side Changes)

### Summary
Implement a speed-focused refactor of the local inference path while keeping the same ZETIC account/models/runtime setup.
Primary strategy is to remove avoidable local overhead and reduce decoder work per request.
No hard timeout/SLA will be enforced; goal is minimum practical latency.

### Implementation Changes
1. **Remove non-essential work from hot path**
- Remove latest-audio playback feature and WAV write from normal IME flow.
- Remove related UI state/control for play/pause from the keyboard surface.
- Keep only record -> process -> commit output.
- Target file scope: [VoiceInputMethodService.kt](/Users/aaryaharkare/AndroidStudioProjects/VoiceKeyboard/app/src/main/java/com/aaryaharkare/voicekeyboard/VoiceInputMethodService.kt).

2. **Make audio capture allocation-free in steady state**
- Replace `ArrayList<Float>` collection with primitive preallocated buffers.
- Convert PCM16 -> float with reusable buffers; avoid boxing and large per-request allocations.
- Add short-silence precheck (energy threshold) before Whisper pipeline to skip obviously empty utterances.

3. **Reduce decoder compute aggressively**
- Set max decode length to `128` tokens by default.
- Keep EOS-based early stop and add repetition guard (stop on repeated terminal-like token patterns).
- Keep current non-streaming UX (no partial text), but decode fewer steps.
- Target file scope: [WhisperDecoder.kt](/Users/aaryaharkare/AndroidStudioProjects/VoiceKeyboard/app/src/main/java/com/aaryaharkare/voicekeyboard/whisper/WhisperDecoder.kt).

4. **Preload and warm models to remove first-use penalty**
- Keep explicit preload action.
- Extend preload to run one tiny warm-up inference for encoder+decoder so first real dictation avoids graph/session cold start cost.
- Persist initialized pipeline object in IME process and reuse between requests; no per-request model construction.

5. **Trim local pipeline overhead around Whisper**
- Keep existing model I/O contract (`[2,80,3000]` encoder input and decoder int64 shape requirements).
- Reuse all tensor buffers where possible; avoid re-creating direct buffers inside per-token loops.
- Minimize debug logging in inference loop in release path (retain error logs only).
- Target file scope: [WhisperFeature.kt](/Users/aaryaharkare/AndroidStudioProjects/VoiceKeyboard/app/src/main/java/com/aaryaharkare/voicekeyboard/whisper/WhisperFeature.kt).

6. **Add stage-level profiling for iterative tuning**
- Add monotonic timing markers for:
  - `audio_ready -> feature_extraction`
  - `feature_extraction -> encoder`
  - `decoder_total` and `decoder_steps`
  - `commit_text`
- Report in debug logs and on-screen debug text in compact form (last run only).
- This becomes the acceptance baseline for future tuning.

### Public/Interface Changes
1. Remove playback-related controls/state from IME UI and service API surface.
2. Keep user interaction unchanged for dictation:
- tap once = start
- tap again = stop + process + final text commit
3. Introduce internal performance config constants (decode cap, silence threshold, warm-up enabled), all local-only and not exposed externally.

### Test Plan
1. **Functional**
- Normal text field: record, stop, transcript commits correctly.
- Password field: remains disabled.
- Empty/near-silent input: returns fast with no crash and no garbage commit.
- App switch, keyboard reopen, fold/unfold: no stuck state or leaked inference job.

2. **Performance**
- Compare before vs after using same utterance set (2s, 4s, 8s English clips).
- Record median and tail latency per stage and end-to-end post-stop latency.
- Verify decoder step count drops materially with cap=128 and early-stop logic.

3. **Stability**
- 50 consecutive dictation cycles without process death or OOM.
- Verify no per-request model reload.
- Verify no regressions in final text insertion behavior.

### Assumptions and Defaults
1. No ZETIC dashboard/model conversion/deployment changes are allowed in this phase.
2. Non-streaming output remains required.
3. No hard latency timeout is imposed; optimization is “as low as possible” with current model/runtime constraints.
4. Speed is prioritized over long-utterance completeness; decode cap is intentionally strict (`128`) and can be revisited after measurements.
