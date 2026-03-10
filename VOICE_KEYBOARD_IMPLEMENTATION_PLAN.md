# Voice-Only Android Keyboard with Local ZETIC Inference

## Summary
- Build a native Android IME in Kotlin that replaces the normal keyboard UI with a compact mic bubble inside the keyboard area.
- Ship in phases: IME shell and bubble first, Whisper Tiny second, then formatter evolution from optional correction to explicit spoken cues to inferred formatting, with tiny general LLM exploration only if the lighter formatter path fails.
- Install path is sideload-only for now: Android Studio and `adb` during development, then a signed internal APK once the IME is stable. No Play Store work is required in this plan.
- Runtime is fully local. Current plan assumes ZETIC model assets may be fetched on first run, then inference stays on-device.
- English-only throughout. No transcript or audio retention. Password and secure fields are never supported.

## Implementation Changes
- Create a greenfield Android app with two entry surfaces: a launcher/settings activity for onboarding and a keyboard service built on `InputMethodService`.
- Define the core runtime state machine up front: `Idle`, `Recording`, `ProcessingStt`, `ProcessingFormat`, `Committing`, `Error`, `DisabledForField`.
- Define these stable interfaces so formatter strategies can change without reworking the IME: `AudioCaptureEngine`, `SttEngine`, `FormatterEngine`, `FieldPolicy`, `ModelManager`, `SessionOrchestrator`, and `TextCommitter`.
- Use `AudioRecord` for raw PCM capture and process audio only after the second tap. There is no streaming transcript and no real-time rendering in any phase.
- Keep all model calls off the main thread. The IME UI only reflects state and final commit results.
- Inject ZETIC keys and model identifiers through local Android/Gradle developer config, not repo-tracked env files or hardcoded constants.
- Preload models from the launcher/settings flow and expose readiness in UI. If a model is unavailable or still downloading, the bubble shows a blocked state and cannot start recording.
- Apply field-aware behavior from the start: standard text and multiline fields use the full pipeline, email/URL/phone/numeric fields use progressively narrower formatting rules, and password or secure entry fields always disable the bubble.
- Treat foldable support as a first-class UI requirement: the IME view must render correctly on inner and outer displays, portrait and landscape, without taking over the screen.

## Phase Plan
1. **Phase 0: Foundation**
   - Create the Android app skeleton, IME service declaration, onboarding activity, permission flow, keyboard enable/set-default guidance, and internal debug build setup.
   - Implement `SessionOrchestrator` and the state model before any AI work so later phases plug into a fixed lifecycle.
   - Add a minimal internal diagnostics screen showing field type, IME state, model readiness, last processing duration, and failure reason with no transcript retention.

2. **Phase 1: Bubble-Only IME**
   - Implement a custom input view that displays only a compact mic bubble and supporting status text or icon states.
   - Trigger the IME when a text field gains focus. The bubble lives inside the keyboard window, not as a system overlay.
   - Support tap-to-start and tap-to-stop with mocked recording and mocked processing delays so the full UI lifecycle is validated before real audio or inference is added.
   - Handle dismissal, app switching, rotation, fold/unfold, field changes mid-session, and input reconnection.
   - Disable the bubble in password or secure fields. In unsupported cases, show a disabled state rather than failing silently.

3. **Phase 2: Whisper Tiny Baseline**
   - Integrate ZETIC Android SDK and wire Whisper Tiny encoder and decoder as the first real model path.
   - Replace mocked recording with real PCM capture and convert the stop event into a transcription session.
   - On success, commit raw Whisper text directly into the focused field with no formatter pass.
   - Keep broad field coverage, but in narrow structured fields such as phone, numeric, email, and URL, bypass any text beautification and commit the narrowest safe output possible.
   - Add benchmark mode with a fixed English utterance set and record only aggregate metrics such as latency, fail/pass, and transcript correctness verdicts.

4. **Phase 3: Whisper Evaluation and Optional Correction**
   - Review benchmark and real-device output to classify Whisper failures into misrecognition, punctuation absence, casing issues, spacing, list structure loss, and domain-specific field problems.
   - Add an optional `CorrectionFormatter` only if repeated Whisper mistakes can be corrected safely without hallucinating content. If errors are mostly recognition failures, do not force a correction stage; tune the STT path instead.
   - Prefer deterministic cleanup first in this phase: whitespace normalization, sentence-case basics, obvious repeated token cleanup, and field-specific sanitization.
   - Gate this phase with a written error taxonomy so the project does not add a formatter that hides STT quality problems.

5. **Phase 4: Explicit Spoken Cues Formatter**
   - Introduce explicit formatting commands such as `new paragraph`, `new line`, `bullet`, `next point`, `comma`, `period`, `question mark`, and `colon`.
   - Implement cue parsing in a formatter layer that operates on Whisper output and converts recognized commands into structure while preserving spoken content.
   - Keep the formatter deterministic first. Only introduce a tiny text model here if deterministic cue handling is not enough.
   - Define exact command precedence now: spoken cues override inferred formatting, and cues are stripped from the final committed text.

6. **Phase 5: Inferred Formatting Without Cues**
   - Add a lightweight formatter model only after the cue-based path and baseline benchmarks are stable.
   - Scope this formatter to English punctuation, sentence boundaries, paragraph splitting, and list inference. It must not rewrite meaning or summarize.
   - Use a hybrid policy: model output is allowed for standard text fields, but constrained deterministic rules remain in charge for email, URL, phone, and numeric inputs.
   - Introduce side-by-side evaluation against Phase 4 to prove that inference improves output when cues are absent and does not degrade obvious cue-driven cases.

7. **Phase 6: Tiny General LLM Exploration**
   - Start only if the dedicated formatter path fails either quality or maintainability goals.
   - Require the candidate tiny LLM to meet the Fold 6 latency target for short dictation and to outperform the Phase 5 formatter on the benchmark set before promotion.
   - Keep the interface identical by swapping only the `FormatterEngine` implementation.
   - Reject this phase if the model improves formatting but causes rewriting, hallucination, or unacceptable latency.

## Test Plan
- Maintain a fixed English benchmark set from the start with categories for short sentences, punctuation-heavy speech, multiline notes, bulleted lists, explicit spoken commands, long dictation, names, and structured fields such as email, URL, phone, and number entry.
- Run every phase on the Galaxy Z Fold 6 in both folded and unfolded states, portrait and landscape, and across at least several host apps with different text fields.
- Use these phase gates:
  - Phase 1 passes when the IME can be enabled, selected, opened on focus, and driven through all UI states without crashes or stuck states.
  - Phase 2 passes when raw Whisper dictation can be recorded, transcribed locally, and committed with a typical under-3-second post-stop response for short utterances on the Fold 6.
  - Phase 3 passes only if the correction layer demonstrably fixes repeatable issues without changing intended meaning.
  - Phase 4 passes when spoken cues produce consistent structural output and cue words do not leak into final committed text.
  - Phase 5 passes when inferred formatting beats raw Whisper on the benchmark and does not regress explicit-cue behavior.
  - Phase 6 passes only if the tiny LLM beats the Phase 5 formatter on quality while staying within the same interaction envelope.
- Test failure paths explicitly: missing mic permission, model download not finished, offline before first model fetch, interrupted recording, incoming call/audio conflict, host app switching, IME dismissal mid-process, and unsupported or secure fields.

## Assumptions and Defaults
- Stack is native Kotlin Android. No Flutter or React Native bridge is part of this plan.
- Development uses Android Studio deployment first, then a signed internal APK after the IME is stable enough for repeatable installs.
- No user-visible hard recording cap is enforced initially. If testing shows instability, add failure-driven soft guardrails later rather than a permanent hard cap.
- Long-term goal is broad non-secure field coverage. Standard text and multiline get the richest formatting first; specialized fields remain more constrained by design.
- Secure, password, and similar protected fields are permanently out of scope.
- No cloud inference, no transcript storage, no audio storage, and no server telemetry are part of this plan.
- Formatter progression is fixed: raw Whisper baseline, optional correction, explicit spoken cues, inferred formatting, then tiny general LLM only if required.
