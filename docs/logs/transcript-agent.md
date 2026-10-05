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
(see below)
