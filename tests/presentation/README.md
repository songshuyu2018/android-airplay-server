# Automatic audio presentation

The receiver owns presentation decisions, so timers continue while the activity is
not visible. Only accepted audio from the current session updates the policy;
metadata, mDNS probes and TCP connections do not count as playback.

- First audio/new sender: request Playing once.
- Brief FLUSH/seek: cancel idle timeout on resumed audio without reopening a page
  the user has left. A pause of at least four seconds is treated as a new resume.
- Pause/teardown: close after 30 seconds. Duplicate stop events do not extend it.
- Missing audio without teardown: detect inactivity after five seconds and count
  the 30-second deadline from the last audio activity.
- Continuing zero-valued PCM is still playback, not a stopped track.
- Startup without any audio does not schedule a close.
- Automatic page entry closes to the previous app/home via moveTaskToBack; manual
  entry closes to Overview. Other pages and video playback are left alone.
- New playback and activity recreation filter obsolete navigation requests.

The foreground service persists desired-running state and uses START_STICKY only
when background reception is enabled. User stop clears that state; onDestroy
only releases resources. Android's background-activity and power restrictions
still apply; this feature does not add any device-setup flow.

Run the deterministic clock tests (no waits or device needed):

```sh
mkdir -p /tmp/presentation-tests
javac -d /tmp/presentation-tests app/src/main/java/io/github/jqssun/airplay/audio/AudioPresentationPolicy.java tests/presentation/AudioPresentationPolicyTest.java
java -cp /tmp/presentation-tests AudioPresentationPolicyTest
```
