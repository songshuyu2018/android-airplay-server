# DACP pause and control availability recovery

The reported symptoms were local silence for five seconds followed by continued
sound, and intermittently disabled transport buttons during active audio playback.
The captured 2026-11-11 21:13-21:14 sample was successful (pause HTTP 204 followed by
native FLUSH), not a captured failure. Code review found definite recovery defects:
replay cleared the queue before sending and dropped failures, service loss cleared
addresses without a complete recovery loop, and retry limits could permanently
stall resolution. UI disabled all transport on transient endpoint unavailability.

## Ownership and command lifecycle

`DacpControlState` is an Android-independent synchronized model. A request belongs
to a sender generation and an immutable endpoint/credential snapshot. Old HTTP and
NSD completions cannot invalidate a newer session or endpoint revision. Only one
HTTP attempt owns the worker slot at a time, including during session replacement.

Explicit play/pause requests use latest-intent-wins semantics. Unsent older intents
are superseded, and a failed in-flight older pause cannot be requeued after a newer
play. An already transmitted request cannot be recalled; serial ordering ensures
that the newer intent follows it. Retryable commands remain queued across failed
sends, up to three actual HTTP attempts and a ten-second monotonic deadline. These
limits are independent of the fixed five-second local mute. All outcomes finish
the original Future: success, failure, superseded, expired, unavailable or session
changed. Queue overflow also has an explicit result. No queued work lives forever.

Next/previous/volume commands are not automatically replayed after an ambiguous
network failure, because the sender may already have executed them. An unsent
snapshot replaced by a newer endpoint can be re-claimed safely without consuming
a real HTTP attempt.

## Discovery and UI

NSD callbacks are marshalled to the main looper, guarded by listener identity,
sender generation and resolve token. Discovery retries use bounded 0.5-8s backoff
with no permanent lifetime attempt cap. A legacy resolve remains owned until its
system callback completes; the four-second watchdog reports slow resolution without
pretending to cancel the OS operation. Timers and old callbacks cannot overwrite a
new owner; release cancels discovery, scheduling and pending commands.

An NSD lost event schedules endpoint refresh but retains the last working address
until a real HTTP request fails. That permits control during a transient mDNS loss.
HTTP failures invalidate only the endpoint revision actually used, then rediscover
and retry the still-current idempotent intent. IPv6 URLs use component construction.
HTTP resources are disconnected in finally; success acknowledges delivery only,
not the sender's playback state.

`canControl` means a live sender identity supports accepting control requests,
including during reconnect; `ready` means an endpoint is currently available.
Playing/Overview transport, Media3 and system MediaSession actions now use the same
capability condition. Temporarily missing endpoints do not permanently grey out
buttons, and pressing can request recovery. True disconnect/session reset disables
control. Sender-derived playback state remains authoritative. Remote-volume UI
keeps its existing endpoint-ready gating.

## Observability and five-second mute

The existing fixed five-second gain mask is unchanged, including repeated-pause and
play-during-mute behavior. Its expiry only restores gain, never sends a play command.
New logs correlate command number, generation, source, attempts, HTTP result and
terminal outcome, plus local mute start/end and actual playback-state transitions.
Active-Remote secrets are not logged. This distinguishes delivery failure from an
HTTP acknowledgment followed by continued sender playback or a subsequent play.

## Validation

- `DacpControlStateTest`: unresolved pause, retry to a new endpoint, newer play
  replacing pause, stale session/endpoint callbacks, retry/expiry bounds, recovery
  after expiry, non-idempotent failure and IPv6 URL construction.
- `DacpControllerIntegrationTest`: compiles the actual Kotlin controller with small
  deterministic Android NSD/looper adapters and uses a real local HTTP server. Tests
  cover NSD loss, HTTP 503 -> rediscovery -> HTTP 204, pending-Future semantics,
  latest intent, more discovery failures than the former lifetime cap, stale NSD
  callbacks, command expiry and no duplicate next-track execution.
- Existing audio session ownership tests still pass. CI runs both recovery suites
  using the verified wrapper distribution's embedded Kotlin compiler, then compiles
  the Android app against the real SDK.

These tests do not simulate Huawei Bluetooth firmware. The 2026-11-14 capture
confirmed missing expected-service discovery while screen-off, followed by late
resolution and error 3 after wake. It does not prove where multicast was filtered.
The verification APK needs device testing with headset and App controls, including
pause/play during reconnect. If HTTP 204 is observed without actual stop, logs
provide evidence for a separate sender/state-confirmation issue.

## Connection-time endpoint cache and screen-off recovery

The RTSP connection reports its numeric peer address through UxPlay's per-session
callback, JNI event 12 and AudioSessionGate. Probes, closed connections and old
sessions cannot replace the active audio sender's address.

When live DACP-ID and Active-Remote arrive, DacpController immediately browses
`_dacp._tcp` and installs an endpoint hint if it knows the RTSP peer:

1. Reuse the saved port for this DACP-ID and numeric peer address.
2. Otherwise use port 39831, Music Assistant's usual DACP port.
3. Continue NSD discovery to learn other ports and refresh stale hints.

Only the endpoint is persisted, never Active-Remote. The private cache holds at
most 16 entries for seven days. A successful resolution or HTTP command refreshes
it. A failed request invalidates the matching cached port; a two-second hint
cooldown prevents a tight retry loop while permitting later recovery without
multicast. The existing command deadline, latest play/pause intent and three HTTP
attempt limit still apply. Ambiguous next/previous/volume failures are not retried.
The default port is a fallback, not a general DACP protocol constant.

On Android's legacy NSD API a four-second watchdog cannot cancel the system
resolve. It now reports slow resolution and starts recovery without releasing the
resolve slot. The terminal system callback releases that slot. A late result is
accepted only for the current DACP generation; a pending replacement resolve is
submitted after the old operation really finishes. Repeated identical endpoint
announcements do not invalidate a queued HTTP worker snapshot.

The receiver already holds a partial CPU wake lock. Its existing multicast lock
is now idempotent and paired with a Wi-Fi high-performance lock for the running
receiver, with balanced release on stop or startup failure. On Android 12 this
supports screen-off discovery; newer Android versions restrict Wi-Fi lock modes.
Locks are supplementary: cached/default unicast requests remain usable without a
fresh mDNS response. No QU packet implementation is introduced.

## Additional verification

The release workflow runs the session and DACP Java tests plus
`tests/control/run-integration.sh <Gradle lib directory>`. Integration tests use
real loopback HTTP with a deterministic NSD/looper test double. They cover slow
resolves, session replacement, discovery outages, stale and missing cache,
current-session credentials, default-port failure/recovery, command ordering and
network-lock lifecycle. They do not emulate Huawei firmware's screen-off packet
filter; validate that behavior with the resulting APK and device logs.

Useful log messages: `DACP endpoint hint` (`source=cache` or `ma-default`),
`DACP resolve start`, `DACP resolve slow`, `DACP resolve ignored old session`,
`DACP resolved`, and `Receiver network locks`.
