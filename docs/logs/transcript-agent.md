# transcript-agent — Live lecture transcript (v3)

## Understanding
User request: "Live lecture transcript: while recording audio, write a text transcript alongside it, synced with your ink".

Today (`ink/EditorServices.kt`): `Recorder` = MediaRecorder → AAC `.m4a` sidecar (`.<ink file>.recN.m4a`); `Dictation` =
SpeechRecognizer on the mic (separate dialog). Android lets only one client own the mic, so a MediaRecorder recording
and a mic-driven SpeechRecognizer can't run together. `InkEditorImpl` stamps every stroke drawn while recording with
`rec` (recording id) + `t` (ms since start, `view.recClockStart/recOffset` clock); playback sets `view.playRec/playPos`
(strokes after the position are ghosted) and tapping a timed stroke while a recording plays calls `Listener.onSeek`.
`Recording(id, file, duration, created)` lives in `InkDoc.recordings` (JSON, `ignoreUnknownKeys`, `encodeDefaults`).

## Plan
1. **Audio capture (API 33+)** — new `ink/TranscriptAudio.kt` `LiveAudioCapture`: one `AudioRecord` (MIC, 32 kHz mono
   PCM16) on one capture thread → `MediaCodec` AAC-LC 64 kbps → `MediaMuxer` MP4 (same `.m4a` as today), and the same
   PCM decimated to 16 kHz written into a `ParcelFileDescriptor` pipe (write end non-blocking: a slow/absent recognizer
   only drops ASR audio, never stalls the encoder). The recognizer gets the read end through
   `RecognizerIntent.EXTRA_AUDIO_SOURCE` (+ CHANNEL_COUNT 1 / ENCODING PCM_16BIT / SAMPLING_RATE 16000). A new pipe per
   recognizer session is adopted by the capture thread (the thread owns every write fd → no fd-reuse races).
2. **Recognizer** — new `ink/TranscriptEngine.kt` `LiveTranscriber`: `createOnDeviceSpeechRecognizer` when
   `isOnDeviceRecognitionAvailable`, else default; `EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE` (one session for the
   whole lecture, `onSegmentResults`), falling back to classic sessions restarted on results / no-match / timeout.
   Fallback ladder when the engine refuses: on-device segmented → on-device plain → default recognizer → "unsupported"
   (recording continues). Partials are kept (committed as a segment on restart / pause / language switch / stop).
3. **Recorder facade** (`EditorServices.kt`): `start(out, live)` picks live capture on 33+ (falls back to MediaRecorder on
   any failure), `pause/resume` (both paths), `openPcmPipe()`, `stop()`.
4. **Model** — new `ink/Transcript.kt`: `TranscriptSeg(start, end, text, rec, lang)`; stored as a new defaulted field
   `Recording.transcript` (one line in `InkModel.kt`; old notes load with an empty list, old app versions ignore the key).
   Search normalisation (case, Arabic diacritics/tatweel, alef/ya/ta-marbuta variants), `.txt` formatting, index-at-time.
5. **UI** — new `ink/TranscriptUi.kt`: live strip under the recording bar (collapsible, partial greyed, AR/EN switch,
   transcript on/off, "not supported on this device" note); karaoke panel under the playback bar (current segment
   highlighted + auto-scrolled; tap = seek audio + ink replay; ink tap → onSeek → highlight follows); search, copy all,
   insert as text box, export `.txt` (library file next to the note, open/share bar); transcript dialog from the
   recordings dialog.
6. **Hooks in `InkEditorImpl.kt`** (kept local): recording state (start/stop/pause, transcriber lifecycle), RecordingBar
   (pause + language + transcript strip), PlaybackBar (shared `PlayClock` position holder), recordings dialog.
7. Strings `tr_*` en + ar. Battery: no wakelocks; recognizer destroyed + AudioRecord stopped on pause; everything
   released on stop / dispose.

## Progress
- Session 1 (cut off by usage limit while writing the UI): `Transcript.kt`, `TranscriptAudio.kt`, `TranscriptEngine.kt`,
  `Recorder` facade, `Recording.transcript` field, most of `TranscriptUi.kt`.
- Session 2: finished `TranscriptUi.kt`, 28 `tr_*` strings en + ar (both XML files parse), `InkEditorImpl` hooks, compile.
- Files:
  - NEW `ink/Transcript.kt` — `TranscriptSeg(start, end, text, rec, lang)`, `LiveTranscript` (Compose state),
    `PlayClock`, `TranscriptPrefs` (own prefs file: live on/off, strip open), `TranscriptText` (indexAt binary search,
    plain / export text, Arabic-aware search folding).
  - NEW `ink/TranscriptAudio.kt` — `LiveAudioCapture` (API 33): AudioRecord 32 kHz mono → MediaCodec AAC-LC 64 kbps →
    MediaMuxer `.m4a`; same PCM → [1 2 1]/4 low-pass + decimate → 16 kHz PCM16 → non-blocking pipe write.
  - NEW `ink/TranscriptEngine.kt` — `LiveTranscription.isSupported`, `LiveTranscriber` (recognizer, fallback ladder,
    segmented sessions, restarts, partial keeping, final-words wait), `TranscriptSession` (editor-facing wrapper).
  - NEW `ink/TranscriptUi.kt` — `LectureRecordingBar` + live strip, `LecturePlaybackBar`, `TranscriptPanel`
    (karaoke / search / copy / insert / export), `TranscriptDialog`, `transcriptPreview`.
  - `ink/EditorServices.kt` — `Recorder` only: `start(out, live)`, `live`, `openPcmPipe()`, `pause()/resume()`,
    `stop()`; MediaRecorder path unchanged (now released if prepare/start throws).
  - `ink/InkModel.kt` — one change: `Recording(..., val transcript: List<TranscriptSeg> = emptyList())`.
  - `ink/InkEditorImpl.kt` hooks (all local): state block after `isPlaying` (session, PlayClock, recPaused, curRecId,
    stripOpen, trPanelOpen, trDialog); `startRecording` (live capture + `transcript.begin` on the stroke clock); new
    `pauseRecording` / `resumeRecording`; `stopRecording` (transcript.finish → replaces the stored transcript when the
    final words arrive; Recording gets the snapshot); onDispose (`transcript.finishNow()` before stop, `release()` after);
    new `exportTranscript(r)` after `exportPdf`; bars → `LectureRecordingBar` / `LecturePlaybackBar` + `TranscriptPanel`;
    recordings dialog rows (preview + transcript button); `TranscriptDialog`; private `RecordingBar` / `PlaybackBar`
    deleted (moved into TranscriptUi.kt).
  - `res/values{,-ar}/strings_ink.xml` — 28 `tr_*` keys.
- Compile (`FILTER=ink/ tools/compile.sh`): no errors in any file or hook of mine. The build still fails on
  ink5-agent's in-progress work. On the last run only 4 errors were left, all in `InkStickers.kt:266/270`
  (`width`/`height` used as properties).

## Decisions & limits
- **API levels.** API 33+ with a recognizer service: one AudioRecord capture → AAC file + recognizer pipe. API 26–32,
  no recognizer, or a capture that fails to start (mic busy, codec refused): today's MediaRecorder recording. The strip
  then says "Transcribe while recording isn't supported on this device. The audio is still recorded." No crash.
- **Recognizer ladder.** on-device + segmented session → on-device classic → default + segmented → default classic →
  unsupported. The ladder only steps while a mode has produced nothing yet. Language not supported / unavailable on
  device: `triggerModelDownload` (for next time), then the default recognizer; if that refuses too, the strip asks to
  try the other language. A working mode that errors is recreated with back-off (0.7 s × n, ≤ 4 s); after 6 failures in
  a row the strip shows "stopped" with Retry. No-match / silence timeout → immediate restart.
- **Seamless segments.** A segmented session (`EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE`) runs the whole
  lecture. Classic sessions restart right after each result. Each session gets a new pipe, and the capture thread starts
  writing into it at once, so audio during the restart waits in the pipe (~2 s buffer) and isn't lost. The write end
  is non-blocking: a stalled recognizer only drops ASR audio and never stalls the recording.
- **Timing.** Recognizers give no per-word times on API 33. Segment start = stroke clock at onBeginningOfSpeech − 300 ms,
  or at the first partial − 1 s, or estimated from the word count (380 ms/word). It is clamped to the previous segment
  end. End = clock at the result. This is the same clock as the strokes' `t`, so karaoke and ink ghosting agree.
  Expect ±1 s accuracy.
- **Partials** are committed as segments on restart, error, pause, language switch and stop. When stopping, the session
  waits up to 1.5 s for the final words. The Recording is saved at once with what is there; the final transcript replaces
  it when it arrives. On dispose, the transcript is committed synchronously (no wait).
- **Storage.** The transcript lives in `Recording.transcript` inside the `.note` / `.ink.json`, so it moves with copies,
  versions and renames. Older files load with `[]` (defaults), and older app versions ignore the key
  (`ignoreUnknownKeys`). I didn't use a sidecar file.
- **Pause** (new on the recording bar): MediaRecorder.pause / AudioRecord stopped (mic released; capture thread waits
  on a lock, no CPU); recognizer destroyed; stroke clock frozen (`recOffset`); ink drawn while paused isn't timed.
  The audio file has no gap (encoder timestamps continue).
- **Battery.** No wakelock or service. While recording: one capture thread + recognizer. On pause, stop and dispose,
  the recognizer is destroyed, AudioRecord / MediaCodec / MediaMuxer are released and pipes are closed. Playback's
  position ticker runs only while playing. The panel recomposes only when the current segment changes
  (`derivedStateOf`).
- **Background.** Without a foreground service, Android silences the mic when the app is backgrounded or the screen is
  off (true for the old MediaRecorder path too). I didn't add a service, to stay within "no wakelocks beyond recording".
- **UI.** The strip and panel sit under their bars, are collapsible, and have a fixed max height (88 dp live,
  128 dp / 200 dp wide for the panel), so they fit split panes. Each segment keeps its own text direction (AR/EN). Search
  ignores case, Arabic diacritics, tatweel, alef/ya/ta-marbuta variants and Arabic-Indic digits. "Insert as text box"
  uses `view.addTextAtCenter` (visible centre on whiteboards; 1/3 down the current page on paged notes, as dictation
  does). Export writes "<name> transcript.txt" next to the note with [m:ss] times and shows the existing Open/Share bar.
  Tapping a segment calls `view.listener.onSeek(rec, start)`, the same path as tapping ink, which seeks audio and ink
  replay. Tapping ink moves the highlight through the shared `PlayClock`.
- **Not verified on a device** (no emulator here). Engine support for `EXTRA_AUDIO_SOURCE` / segmented sessions differs:
  Google's on-device service on Android 13+ supports it. Samsung's own recognizer may not; then the ladder moves to
  Google or reports unsupported.

## Requests to lead
1. Please test on the Tab S11 Ultra (Android 16): start recording in AR and EN; switch language mid-lecture; pause and
   resume; stop then play; tap segments and ink; search Arabic with and without tashkeel; export .txt. Also check
   whether the strip says "Transcribed on this device".
2. Optional later: a microphone foreground service so lectures keep recording with the screen off. That needs a manifest
   change (`FOREGROUND_SERVICE_MICROPHONE`) and is outside my ownership.

## Self-check
1. Live transcription while recording — **PASS (compile + reasoning; not device-tested)**: single capture → AAC + pipe
   (API 33+), on-device preferred with fallbacks, seamless restarts, partials kept, MediaRecorder fallback with the
   unsupported message, language from `Prefs.speechLang` with a quick switch on the bar.
2. Transcript model — **PASS**: segments (start, end, text, rec, lang) in `Recording.transcript`, `.note` compatible.
3. UI — **PASS**: live strip (partial greyed, collapsible); karaoke panel (highlight, auto-scroll, tap → seek, ink tap →
   highlight); search, copy all, insert as text box, export .txt; transcript in the recordings dialog (preview + full).
4. Battery — **PASS**: no wakelocks; recognizer and codecs released on stop, pause and dispose.
- Build: my files are clean. The whole module doesn't compile yet because of ink5-agent's in-progress files —
  **PARTIAL** until they land.

## Follow-up: microphone foreground service (lead-approved request 2)
- NEW `ink/RecordingService.kt`. It is a `foregroundServiceType="microphone"` service that does no audio work; the
  recorder stays in the editor.
  - `start(ctx, title, onAction)` is called from `startRecording` while the editor is in the foreground. It uses
    `ContextCompat.startForegroundService`, then `ServiceCompat.startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE`
    (API 30+). If the system refuses, recording continues without the service.
  - `update(paused, elapsed)` is called from pause and resume.
  - `stop()` is called from `stopRecording`, which also runs on dispose.
  - If `stop()` comes before `onStartCommand`, the service still calls startForeground first and then stops itself. This
    avoids the "did not call startForeground" crash.
- Notification: low-importance silent channel "Lecture recording".
  - Recording: title "Recording lecture", with the note title as text and a system chronometer
    (`setUsesChronometer` + `setWhen`), so there are no per-second updates.
  - Paused: "Recording paused · m:ss", with no chronometer.
  - Actions: Pause or Resume, and Stop (`PendingIntent.getService`, immutable). They go to the editor through a callback
    on the main thread (`onRecAction` in InkEditorImpl). Tapping the notification opens the app.
  - An action that arrives with no editor recording just removes the service.
- No wakelock: while AudioRecord or MediaRecorder is capturing, the audio system keeps the CPU awake as needed.
- Manifest: added `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE`, and the `<service .ink.RecordingService
  exported=false foregroundServiceType=microphone>`. POST_NOTIFICATIONS was already there. Without it (Android 13+) the
  service still runs; only the notification is hidden.
- InkEditorImpl hooks:
  - an `onRecAction` var next to the transcript state;
  - `RecordingService.start` at the end of `startRecording`;
  - `update` in pause and resume;
  - `stop()` in `stopRecording`;
  - the `onRecAction` assignment before `play()`.
- Strings: `tr_channel`, `tr_notif_recording`, `tr_notif_paused` in en and ar.
- Fixed my build blocker: `TranscriptSession.setOn()` clashed with the `on` property setter. It is now `switchLive()`.
- Limit: the editor composition must stay alive while the app is backgrounded. If the system kills the activity, dispose
  stops the recording cleanly (the audio is kept).
- Full `tools/compile.sh` → **BUILD OK** (after the switchLive rename; the whole module compiles, including ink5's files).
