# Phase 2 Whisper Handoff Plan

## Summary

Phase 2 adds local Whisper Tiny speech-to-text to the existing voice-only keyboard.

You will handle:
- Melange dashboard work
- Android Studio UI work
- running Gemini prompts inside Android Studio

I will handle:
- prompt design
- step-by-step instructions
- review of what Gemini changes
- debugging guidance based on errors or behavior

I will not directly edit the app code for this phase unless you later ask me to.

## Current State

Phase 1 is complete.

The app already has:
- a working Android IME
- a bubble-only keyboard UI
- audio recording and latest-audio playback debug behavior
- microphone permission flow
- onboarding/setup screen

Important technical constraint:
- the current debug recording path is based on `MediaRecorder` and writes `3gp` / `AMR_NB`
- that format is fine for debug playback, but it is the wrong input path for Whisper
- Phase 2 must replace the recording pipeline with `AudioRecord` using `16 kHz`, `mono`, and Whisper-friendly PCM data

## Phase 2 End State

When Phase 2 is finished:
- tap bubble once: start recording
- tap bubble again: stop recording
- app processes the audio locally with Whisper Tiny through ZETIC Melange
- raw Whisper text is inserted into the active text field
- the latest raw Whisper result is also visible in a compact debug preview
- latest-audio playback still works for debugging
- secure/password fields remain disabled
- no formatter is added yet

## Chosen Defaults

- Language: English only
- No real-time transcript
- Batch flow only: record, stop, process, insert
- Model loading strategy: preload from the launcher/setup flow before normal keyboard use
- Output behavior: auto-insert raw Whisper text and keep a small debug preview
- Secret/config storage: `local.properties`, not repo-tracked files
- Model naming: use your own Melange-deployed encoder and decoder repositories if available
- Fallback only if blocked: `OpenAI/whisper-tiny-encoder` and `OpenAI/whisper-tiny-decoder`

## Subphases

### Phase 2A: ZETIC Dashboard Preparation

Owner:
- You

Goal:
- prepare the Melange-side values Gemini will need in the app

What you do:
1. Sign in to Melange.
2. Generate or copy your `PERSONAL_KEY`.
3. Confirm the two Whisper Tiny repositories you want to use:
   - encoder
   - decoder
4. Make sure both repositories are in a usable state for Android integration.
5. Copy the exact repository names.

Values to collect:
- `PERSONAL_KEY`
- `WHISPER_ENCODER_MODEL`
- `WHISPER_DECODER_MODEL`

Recommended value format:
- `your_username/whisper-tiny-encoder`
- `your_username/whisper-tiny-decoder`

Fallback if your account deployment blocks:
- `OpenAI/whisper-tiny-encoder`
- `OpenAI/whisper-tiny-decoder`

Done when:
- you have all three values ready

Stop point:
- return to me with those values available locally

### Phase 2B: Project Configuration and ZETIC SDK Wiring

Owner:
- Gemini in Android Studio
- guided by my prompt

Goal:
- make the Android project ready to load Whisper models

Changes expected:
- update `app/build.gradle.kts`
- enable `buildConfig`
- add ZETIC dependencies
- enable `packaging.jniLibs.useLegacyPackaging = true`
- add any required `pickFirsts` if Gemini sees native library collisions
- load `PERSONAL_KEY` from `local.properties` into `BuildConfig`
- optionally also load encoder and decoder model names from `local.properties`

Recommended dependency baseline from ZETIC sample app:
- `com.zeticai.mlange:mlange:1.3.0`
- `com.zeticai.ext:ext:0.0.1`

Local config keys to place in `local.properties`:
- `PERSONAL_KEY=...`
- `WHISPER_ENCODER_MODEL=...`
- `WHISPER_DECODER_MODEL=...`

Done when:
- project syncs
- app builds
- no missing ZETIC classes remain

Stop point:
- if Gradle cannot resolve the ZETIC dependencies
- if native library packaging errors appear

### Phase 2C: Audio Pipeline Migration

Owner:
- Gemini in Android Studio
- guided by my prompt

Goal:
- replace the current recorder path with Whisper-friendly PCM capture

Required change:
- stop using `MediaRecorder` as the source of transcription audio

New recording path:
- `AudioRecord`
- `16_000 Hz`
- `CHANNEL_IN_MONO`
- PCM-based sample collection
- preferred shape: `FloatArray`

Debug playback requirement:
- preserve latest-audio playback
- only the latest clip should exist
- playback must continue working after the recorder migration

Recommended implementation shape:
- use `AudioRecord` for transcription capture
- write the latest clip to a local `wav` file for debug playback
- keep only one latest clip
- starting a new recording replaces the old clip

Files likely touched:
- `VoiceInputMethodService.kt`
- `MainActivity.kt`
- `keyboard_view.xml`
- new helper classes for audio capture and wav writing

Done when:
- recording still starts and stops from the bubble
- latest playback still works
- captured audio is now suitable for Whisper input

Stop point:
- if playback breaks after removing `MediaRecorder`
- if audio capture becomes unstable across keyboard reopen/app switch flows

### Phase 2D: Whisper Pipeline Integration

Owner:
- Gemini in Android Studio
- guided by my prompt

Goal:
- add the actual Whisper Tiny inference pipeline

Recommended structure based on ZETIC sample app:
- `AudioSampler`
- `WhisperFeature`
- `WhisperEncoder`
- `WhisperDecoder`
- optional helper for token probability / argmax

Pipeline:
1. record raw PCM audio
2. convert to `FloatArray`
3. pass audio to `WhisperWrapper.process(...)`
4. run encoder model
5. run decoder model token generation loop
6. decode tokens to text
7. return raw Whisper text

Additional requirement:
- add `vocab.json` to app assets for token decoding

Files likely added:
- a small `whisper/` package under `app/src/main/java/com/aaryaharkare/voicekeyboard/`
- `app/src/main/assets/vocab.json`

Done when:
- local transcription succeeds on device
- the returned text is visible in debug UI before insertion wiring

Stop point:
- if model loading fails
- if first-run model download hangs or errors
- if token decoding fails due to missing vocab asset

### Phase 2E: IME Integration

Owner:
- Gemini in Android Studio
- guided by my prompt

Goal:
- connect Whisper output to actual keyboard behavior

Required behavior:
- after stop recording, show `Processing...`
- transcribe locally
- insert raw Whisper text into the current input field
- keep a compact last-result debug preview in the IME UI
- preserve latest-audio play/pause debugging

Recommended state model:
- `Idle`
- `Recording`
- `ProcessingStt`
- `Playing`
- `Disabled`
- `Error`

Insertion behavior:
- use `currentInputConnection.commitText(...)`
- no formatter yet
- no post-processing beyond safe trimming/cleanup if absolutely needed

Secure field behavior:
- no recording
- no playback
- no transcription
- disabled UI state only

Done when:
- you can dictate into a normal text field and raw Whisper text appears in that field

Stop point:
- if text commits into the wrong field
- if the IME gets stuck in `Processing`
- if input connection is null during field transitions

### Phase 2F: Validation

Owner:
- You
- with my review/debug help

Goal:
- verify Phase 2 works well enough to move to formatter work later

Manual tests:
1. Normal text fields in multiple apps
2. Folded and unfolded Fold 6
3. App switch during recording
4. Keyboard dismiss and reopen
5. Latest-audio playback after each recording
6. First run with model download
7. Second run with normal cached model behavior
8. Password and secure fields

Success criteria:
- raw text is inserted reliably
- playback still works for the latest clip only
- no crashes on IME lifecycle changes
- model loading state is understandable
- no cloud API is used outside Melange provisioning/download

## Exact User Responsibilities

You will need to do these things manually:

1. Melange dashboard:
   - sign in
   - get `PERSONAL_KEY`
   - confirm encoder/decoder repository names

2. Android Studio UI:
   - let Gradle sync after each Gemini prompt
   - install any missing Android SDK components if prompted
   - run the app on the phone
   - verify behavior on the Fold 6

3. Local config:
   - paste Melange values into `local.properties`

4. Testing:
   - record sample audio
   - verify raw transcript quality
   - report errors, stack traces, or screenshots back to me

## How We Will Work

We will do this one subphase at a time.

For each subphase:
1. you ask me for the prompt
2. I give you one Gemini prompt only for that subphase
3. you run it inside Android Studio
4. you tell me what Gemini changed and whether the build/test passed
5. I either approve the result or give you the next correction prompt
6. only then do we move to the next subphase

## Prompt Order

Use this order later:
1. Phase 2A manual dashboard setup
2. Phase 2B Gemini prompt for Gradle + ZETIC config
3. Phase 2C Gemini prompt for audio pipeline migration
4. Phase 2D Gemini prompt for Whisper wrapper classes
5. Phase 2E Gemini prompt for IME transcription insertion
6. Phase 2F manual test checklist and bug-fix prompts

## Important Constraints

- Do not add the formatter in Phase 2
- Do not add spoken cues in Phase 2
- Do not add cloud transcription APIs
- Do not keep transcript history
- Do not keep multiple audio clips
- Do not remove secure-field blocking
- Do not migrate the project to Compose
- Do not hardcode Melange secrets in repo-tracked files

## First Step When We Resume

When you are ready to begin Phase 2 for real, the first thing I should give you is:

- the exact manual checklist for Phase 2A
- followed by the Gemini prompt for Phase 2B

