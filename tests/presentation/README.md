# Automatic audio presentation

The receiver owns presentation decisions, so timers continue while the activity is
not visible. Only accepted audio from the current session updates the policy;
metadata, mDNS probes and TCP connections do not count as playback.

- First audio/new sender: show the service-owned player overlay once. When the
  original Playing page is already visible, reuse it without adding another surface.
- Brief FLUSH/seek: cancel idle timeout on resumed audio without reopening a page
  the user has left. A pause of at least four seconds is treated as a new resume.
- Pause/teardown: close after 30 seconds. Duplicate stop events do not extend it.
- Missing audio without teardown: detect inactivity after five seconds and count
  the 30-second deadline from the last audio activity.
- Continuing zero-valued PCM is still playback, not a stopped track.
- Startup without any audio does not schedule a close.
- After 30 seconds, remove the automatic overlay without starting or moving an
  activity. The app underneath stays where the user left it. Manual Playing closes
  to Overview; other pages are left alone. Starting video removes the music overlay.
- Closing the overlay explicitly does not stop playback or the receiver. Continuing
  heartbeats do not reopen it; a new sender or stable pause/resume can open it again.
- Both surfaces share PlayerContent and PlayerSource; only the Activity wrapper
  owns orientation/back navigation. The overlay has its own lifecycle, saved-state
  owner, hardware-accelerated window, and main-thread spectrum polling. Removal
  disposes composition and cancels its polling coroutine before native teardown.
- New playback and activity recreation filter obsolete navigation requests.

The foreground service persists desired-running state and uses START_STICKY only
when background reception is enabled. User stop clears that state; onDestroy
only releases resources. Automatic music presentation uses SYSTEM_ALERT_WINDOW
instead of starting a background Activity. Without the already-authorized overlay
permission, playback and system media controls remain available. System UI/lock
screen rules still apply. No device-setup flow is added.

## System media controls

A single MediaSessionCompat session serves Android/EMUI/HarmonyOS Android-compatible
system media controls, headset keys and the media notification. It publishes title,
artist, album, duration, bounded artwork and authoritative sender playback state.
Play/pause/previous/next are forwarded through the existing DACP controller; actions
never optimistically alter the app's state. Audio seeking is not advertised because
this receiver cannot send arbitrary seeks through DACP. Opening the system media
card enters the manual Playing page. The paused session remains active for resume
when its visual overlay is gone. Music requests audio focus only during actual
playback and releases it on pause/disconnect; focus loss requests sender pause.
The service adds mediaPlayback to its connectedDevice foreground type when a media
session connects, while idle/boot reception uses connectedDevice alone.

Device verification after installing the CI APK should cover background start,
30-second removal and resume, manual entry/exit, sender takeover, and Huawei media
card artwork/state plus play/pause/previous/next in both directions. The deterministic
tests below do not replace this OS-level device verification.

Run the deterministic clock tests (no waits or device needed):

```sh
mkdir -p /tmp/presentation-tests
javac -d /tmp/presentation-tests app/src/main/java/io/github/jqssun/airplay/audio/AudioPresentationPolicy.java tests/presentation/AudioPresentationPolicyTest.java
java -cp /tmp/presentation-tests AudioPresentationPolicyTest
```
