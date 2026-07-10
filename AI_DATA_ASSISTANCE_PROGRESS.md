# AI Data Assistance Progress

## Project Goal

Build an Android/Kotlin-only extension for `fcitx5-android` that helps users work inside legacy, closed, or accessibility-limited environments where text cannot be copied and no public API is available.

The extension is designed to run inside the input method flow:

1. Trigger from keyboard gesture.
2. Capture one screen frame with `MediaProjection`.
3. Run offline OCR with Google ML Kit.
4. Search local enterprise knowledge data first.
5. Fall back to DeepSeek-compatible LLM API if local data misses.
6. Show the result as the first candidate item.
7. Inject selected result gradually with rate-limited `commitText()`.

No C++ code has been modified. All work is in the Kotlin/Java `:app` layer.

## Current Implementation Status

### Completed

- Added package:
  - `org.fcitx.fcitx5.android.extension.ai`

- Added AI infrastructure files:
  - `AiCandidateOverlay.kt`
  - `AiMediaProjectionStore.kt`
  - `AsyncLlmClient.kt`
  - `DataAssistanceCoordinator.kt`
  - `LocalSkillDatabase.kt`
  - `ScreenCaptureOcrPipeline.kt`
  - `ScreenCapturePermissionActivity.kt`
  - `SkillImportManager.kt`
  - `SmoothTextInjector.kt`

- Hooked Space long-press:
  - `CommonKeyActionListener.kt`
  - `SpaceLongPressAction` now calls `DataAssistanceCoordinator.triggerDataAssistance()`.

- Hooked candidate bar overlay:
  - `HorizontalCandidateComponent.kt`
  - AI result is inserted as the first horizontal candidate.
  - Clicking that AI candidate triggers smooth text injection.

- Added session reset gesture:
  - `BaseKeyboard.kt`
  - `KeyboardWindow.kt`
  - Two-finger downward swipe calls `DataAssistanceCoordinator.panicReset()`.

- Added screen capture permission flow:
  - `ScreenCapturePermissionActivity.kt`
  - `AiMediaProjectionStore.kt`
  - Registered activity in `AndroidManifest.xml`.

- Added offline OCR pipeline:
  - Uses `ImageReader` + `VirtualDisplay` for single-frame capture.
  - Uses ML Kit Chinese text recognizer API.

- Added local knowledge database:
  - Room database with `SkillEntity`.
  - FTS-backed search using Room `@Fts4`.
  - `LIKE` fallback search.
  - Import helper for JSON and simple TXT formats.

- Added network client:
  - OkHttp 4.x client.
  - 5-second connect/read/write timeout.
  - Retry support.
  - Non-streaming Chat Completion response parsing.
  - SSE streaming method exists.

- Added rate-limited text injection:
  - `SmoothTextInjector`
  - Commits one character at a time.
  - Adds random delay between 80 and 130 ms.

- Added dependencies:
  - `okhttp`
  - `com.google.mlkit:text-recognition-chinese`

## Known Issues / Gaps

### Build Environment

- Local Java path that works:
  - `C:\Program Files\Android\Android Studio\jbr`

- Previous bad path:
  - `C:\Program Files\Google\Android Studio\jbr`

- Gradle wrapper download succeeded after network permission.

- Build currently blocks before Kotlin compilation because Android Gradle Plugin cannot be resolved:
  - `com.android.application:9.2.0`

This means the new Kotlin code has not yet been compiler-verified.

### Product Gaps

- Settings UI is not implemented yet:
  - DeepSeek API key input.
  - Base URL/model config.
  - Prompt template editor.
  - Knowledge-base JSON/TXT import button.

- `DataAssistanceCoordinator` still uses non-streaming LLM call in the main flow.
  - `AsyncLlmClient.streamAiResponse()` exists but is not wired into the coordinator yet.

- Candidate overlay is currently only applied to the horizontal candidate bar.
  - Floating and expanded candidate views are not integrated.

- `AiCandidateOverlay` listener lifecycle needs cleanup.
  - Current implementation registers a listener in `HorizontalCandidateComponent`, but no unregister path is wired yet.

- Long-press candidate action index needs extra care.
  - Candidate click maps AI overlay index correctly.
  - Candidate long-click still needs final index mapping / AI item exclusion.

- Panic reset currently clears active jobs, UI overlay, and injector.
  - Decide whether it should also stop `MediaProjection` every time.

- Strict FTS5 is not implemented.
  - Current implementation uses Room `@Fts4`, which is more directly supported by Android Room.
  - If FTS5 is mandatory, use raw SQLite setup/migrations rather than Room FTS annotations.

## Important Files Changed

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateComponent.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/extension/ai/*`

## Recommended Next Steps

1. Fix Android Studio SDK / Gradle plugin resolution.
2. Run:
   - `.\gradlew.bat :app:compileDebugKotlin`
3. Fix compiler errors from Room, ML Kit, and Android API usage.
4. Clean up candidate overlay listener lifecycle.
5. Fix candidate long-click index mapping.
6. Wire LLM streaming into `DataAssistanceCoordinator`.
7. Add settings UI for:
   - API key.
   - Prompt template.
   - Knowledge-base import.
8. Test on device:
   - Space long-press trigger.
   - MediaProjection permission flow.
   - OCR extraction.
   - Candidate overlay display.
   - Smooth injection into target legacy text field.
   - Two-finger panic reset.

## Notes

- Do not modify C++ code for this feature.
- Keep all feature work inside `:app` Kotlin/Java unless there is a separate explicit decision.
- Treat screenshots and OCR text as sensitive. Do not upload images to cloud APIs.
