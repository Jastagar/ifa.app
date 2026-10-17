# Ifa for Android

Talk to Ifa from your phone whenever your PC is running Ifa on the same Wi-Fi.

- **Wake word on the phone.** Say "Ifa". The phone runs the same openWakeWord model as the PC (`ifa.onnx`), so no audio leaves the phone until it hears the wake word.
- **Mic button.** Tap to talk, tap again when you're done (or just stop talking).
- **Voice streaming.** Your voice streams to the PC. The PC runs speech-to-text, the agent and the Chatterbox voice, and streams the reply back as audio.
- **Follow-ups.** After Ifa answers, the phone listens for a few seconds without the wake word, like on the PC.
- **Screen comes up.** When the wake word fires, the screen turns on and shows Ifa, even over the lock screen. The screen stays on while the app is open.
- **Notifications go straight to Ifa.** No Spring Boot bridge needed.
- **Type to Ifa** from the text box.

## Setup

1. On the PC, start Ifa. It prints a line like:
   `[phone] listening on ws://0.0.0.0:8789/phone (discovery udp/8788). Pairing code: 123456`
   On Windows, allow Python through the firewall on **private networks** when asked.
2. Install the app, open **Settings**, enter the pairing code, and tap **Save and reconnect**.
3. Tap **Start Ifa** and allow the microphone and notifications.
4. Optional:
   - **Grant notification access** to forward phone notifications to Ifa.
   - **Allow full-screen alerts** (Android 14+) so the wake word can turn the screen on over the lock screen.

The app finds the PC on its own by broadcasting on UDP 8788. If your router blocks broadcasts, type the PC's address (e.g. `192.168.1.97`) in **PC address**.

## How it works

```
 phone                                              PC (Ifa)
 ─────                                              ────────
 mic ─► wake word (onnxruntime, on device)
          │ "Ifa"
          ▼
 {"type":"start"} + 16 kHz PCM16 frames ──────────► VAD (Silero) ends the utterance
                         ◄────────── end_of_speech  Whisper -> transcript
                         ◄────────── transcript      agent turn (same memory as the PC)
                         ◄────────── reply + audio   Chatterbox -> PCM16 frames
 AudioTrack plays it     ◄────────── turn_end {followup}
 follow-up listen (no wake word) or back to wake word
```

Everything runs over one WebSocket (`ws://<pc>:8789/phone`, header `X-IFA-Token: <pairing code>`). Text frames are JSON events, binary frames are audio. Notifications use the same socket (`{"type":"notification", ...}`).

| Code | What it does |
|---|---|
| `service/IfaVoiceService.kt` | Foreground service: discovery, reconnects, the listen → think → speak state machine, waking the screen |
| `voice/WakeWordEngine.kt` | openWakeWord port: melspectrogram → embedding → `ifa.onnx`. Matches the Python scores exactly |
| `voice/MicRecorder.kt` | 16 kHz mono mic in 80 ms chunks |
| `voice/SpeechPlayer.kt` | Streams Ifa's voice into an AudioTrack and reports when it has finished playing |
| `ifa/IfaDiscovery.kt` | UDP probe/reply to find the PC |
| `ifa/IfaConnection.kt` | The WebSocket |

The wake-word models live in `app/src/main/assets/wakeword/`. If you train a custom "hey ifa" model, replace `ifa.onnx` there too.

## With the app closed

Once you tap **Start Ifa**, the wake word keeps working with the app closed or swiped away and the screen off. A background service keeps going, and the "Ifa" notification shows it's on. It stops when you tap **Stop**, force-stop the app, or restart the phone. After a restart, open the app once: Android doesn't let an app turn the microphone on from the background, so it can't start itself at boot. Tap **Keep Ifa running in the background** in Settings so battery saving doesn't kill it. Some phones (Xiaomi, OnePlus, Oppo, Vivo, Samsung) also need **Unrestricted** battery for the app in system settings.

## Notes

- Android shows the green mic indicator while the wake word is on. Turn the wake word off in Settings to use the mic button only. The mic is off whenever the PC isn't connected.
- The wake threshold comes from the PC (`IFA_WAKE_THRESHOLD`). Phone mics differ, so you can override it in Settings. The wake score under the mic button shows what the model hears.
- The old Spring Boot upload is still there under **Also send to the backend URL**, off by default.
