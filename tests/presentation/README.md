# Automatic audio presentation

The receiver owns presentation decisions so the 30-second timer continues while
its Activity is not visible. Only accepted audio from the current session counts;
metadata, mDNS probes and TCP connections do not open the player.

- First audio/new sender: request the existing Activity Playing page once.
- Brief FLUSH/seek: cancel idle timeout on resumed audio without reopening a page
  the user has left. A pause of at least four seconds is treated as a new resume.
- Pause/teardown: close after 30 seconds. Duplicate stop events cannot extend it.
- Missing audio without teardown: detect inactivity after five seconds and count
  the 30-second deadline from the last audio activity. Zero-valued PCM is playback.
- Initial idle does not auto-close. Both automatic and manual Playing return to
  the launcher after the stop deadline. This explicitly starts CATEGORY_HOME and
  moves the task back with automatic PiP suppressed, retaining Playing for the
  next app visit. The manual back button still opens Overview so settings and
  other tabs remain accessible. Other tabs and video playback are left alone.
  Obsolete navigation requests are filtered on recreation.
- Presentation uses MainActivity, its original orientation/back handling and its
  original player composition. No service overlay window is created.

The foreground receiver persists desired-running state and uses START_STICKY only
when background reception is enabled. User stop clears that state; onDestroy only
releases resources.

## Huawei background popup permission

Background pop-ups are different from SYSTEM_ALERT_WINDOW. On the first manual
launch on Huawei/Honor, a one-time prompt offers this app's settings. It waits for
Android runtime permission requests to finish, never interrupts automatic playback,
and records only that settings were offered. There is no public Android runtime
permission grant/result contract for this vendor switch, so the app does not claim
that opening settings granted it. Settings retains an entry for later access; the
auto-open switch represents the app preference, not an inferred permission value.
Other Android devices retain the existing overlay-permission settings route.

## System media controls and Huawei playback center

A single MediaSessionCompat publishes authoritative sender state, title/artist/album,
duration, bounded artwork and a manual Playing activity intent. Play/pause/previous/
next are sent through DACP without optimistic state changes. Paused sessions remain
active to allow system resume. Only actual music requests audio focus; pause,
disconnect and shutdown release it. Audio seeking is not advertised because this
receiver cannot send arbitrary seeks through DACP. The foreground service adds
mediaPlayback during a media session and uses connectedDevice alone while idle.

EMUI 14.2 on the NOH-AN00 test device additionally filters sessions using
HwMediaSessionServiceEx -> MediaControlUtils.checkController ->
isInMediaSessionOrStyleList(queryType=2). This checks the vendor's media player
allowlist; publishing a standard MediaSession does not bypass this check. The
readable preinstalled /system/emui/base/thirdappfilter/third_app_filter.xml does not
contain io.github.jqssun.airplay. Vendor system configuration is not modified by
this app. Notification controls working must not be reported as proof that the
Huawei Control Center playback card accepted the app. Vendor allowlist adaptation
is needed for that device's dedicated playback center.

## Verification

Device verification after the CI build should cover authorized background Activity
launch, 30-second return to desktop for both entry modes, manual Back opening
Overview for manual navigation, reopening Playing after idle exit, first
manual-launch permission prompt, pause/resume/seek, sender takeover, and system
notification controls. Huawei playback center acceptance is a separate check.

Run deterministic clock tests without waits or a device:

```sh
mkdir -p /tmp/presentation-tests
javac -d /tmp/presentation-tests app/src/main/java/io/github/jqssun/airplay/audio/AudioPresentationPolicy.java tests/presentation/AudioPresentationPolicyTest.java
java -cp /tmp/presentation-tests AudioPresentationPolicyTest
```

Developer settings includes audio_idle_return_home (default true). Disabling it
suppresses idle CLOSE navigation at dispatch time; playback tracking, automatic
opening and the original 30-second deadline remain independent. Disabled idle
expiry leaves Playing visible. Manual back navigation is unaffected.

Local pause (UI, notification and media-session/headset pause) immediately masks
the native output gain for a fixed five seconds, then sends the usual DACP pause.
The Media3 player adapter uses the same mask. The mask does not change playback
state, stop/flush PCM, alter system volume, swallow later commands, or auto-play
at expiry. Play during the window is forwarded normally but stays silent until
expiry. Repeated pauses do not extend the window. Sender gain changes are retained
and the latest gain is restored; detach cancels the pending release callback.
Device checks: local/headset pause, play again within five seconds, repeated pause,
volume changes while silent, and disconnect/service stop while the mask is active.
