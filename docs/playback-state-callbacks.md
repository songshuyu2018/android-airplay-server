# Playback state callbacks

Settings → 播放状态上报 contains a default-off switch and an editor for two curl
commands: playing and paused/stopped. Paste each complete command; the editor
parses it automatically, shows the method and endpoint, and only saves valid
commands. Both commands are saved atomically. Enabling or saving synchronizes the
current music state immediately. Receiver restart is not required.

A music start/resume reports playing immediately, then every 40 seconds. A
confirmed pause, stream end, receiver stop, or transition away from music reports
paused without waiting for the heartbeat. The existing seek/FLUSH pause detection
is unchanged: a button press is not proof that the sender really paused. Mirroring,
video, metadata-only connections and the visual theme preview do not count as music.

The importer supports HTTP(S), `-X`/`--request`, `-H`/`--header`, inline
`-d`/`--data`/`--data-raw`/`--data-binary`, `--json`, `--url`, quoted strings and
backslash-newline continuations. It never launches curl or a shell, expands
variables, reads `@file` data, or follows HTTP redirects. For JSON bodies without
an explicit Content-Type (such as the supplied HA turn_off command), it adds
`Content-Type: application/json`. Unsupported options are rejected rather than
silently changing their meaning.

Example (replace the address, token and entity in Settings):

```sh
curl -X POST http://homeassistant.local:8123/api/services/input_boolean/turn_on \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -d '{"entity_id":"input_boolean.music_running"}'
```

Use the same command with `turn_off` for paused/stopped. Personal credentials are
not bundled in the APK. Commands are kept in private app preferences (backup is
disabled); logs contain only playing/paused, HTTP status or exception class, never
headers, request bodies, command text or exception messages. The preview omits URL
query parameters. Disabling the switch cancels queued and future callbacks.

One background worker serializes HTTP and timers. A generation check drops old
queued states on pause/resume, reconfiguration or disable. An already transmitted
request cannot be recalled; the newer state follows it on the same worker.
Connection/read timeouts are two seconds each. Playing failures wait for the next
40-second heartbeat. Paused failures retry after two and then five seconds (three
attempts total), cancelled if playback resumes or configuration changes. Service
shutdown attempts a final paused callback if one has not been confirmed. Force
stop, process kill and network/server outages cannot guarantee remote delivery.

`tests/reporting/run.sh` tests parsing, cadence, latest-state ordering, in-flight
transitions, retry cancellation, config changes, shutdown and secret-safe logs.
It also sends real loopback HTTP requests and verifies authorization, JSON, method
and on→off ordering. Tests never call the configured Home Assistant instance.
