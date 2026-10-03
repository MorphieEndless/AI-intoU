# Relay performance and compatibility tranche

## Scope

This release keeps the current Android/MCP wire protocol and production
credential compatibility. It does not complete the coordinated M2b identity
migration. No production configuration or runtime data belongs in this change.

## Changes

- Registry implementation moves to `app/infra`, ACK schema to `app/schemas`.
  Legacy imports are re-exports, not a second implementation. Cleanup,
  heartbeat and device reports carry the owning session. Replaced sockets
  cannot remove their successor or overwrite its state. Closing a previous
  socket does not hold the registry lock.
- Static bearer account binding comes from central settings. Waveforms keep
  repeat 1–60 and the ten-minute cap; no minimum length is introduced.
  Escalate holds for ten seconds by default across server and relay clients;
  explicit zero retains indefinite hold. CLI relay URLs are explicit or use
  `SB_RELAY_SERVER`, with no infrastructure-specific default in source.
- Android relay uses one worker and a 64-message inbox per connection.
  Heartbeats remain independent. Stop drains queued work with negative ACKs;
  overflow is rejected explicitly. Disconnect cancels the old worker and
  completes the failsafe before reconnecting. Delayed callbacks from stopped
  connections cannot reset the active connection. A scan's delayed device
  report does not occupy the command inbox.
- App state updates use atomic StateFlow updates. Screens subscribe only to
  the state they need, not all logs and device updates at the navigation root.
  Connection settings are remembered instead of repeatedly decrypted during
  waveform redraws. Initial keystore work and credential saves run off main.
- Service teardown no longer uses `runBlocking`. It attempts stop while BLE
  is usable, then closes resources without scheduling work into a cancelled
  scope. Start/restart/shutdown are serialized. Main-thread teardown does not
  wait for the write pacer; process death still cannot guarantee a physical
  stop, so hardware watchdog and disconnect protection remain necessary.
- BLE cache skips repeated positive hardware frames within a connection.
  Modes/channels are separate; reconnect and raw writes invalidate the cache.
  Stop frames and watchdog refreshes are never deduplicated. The existing
  100ms pacing remains; the additional 35ms stop delay is removed.
- Journal changes are reduced in order in batches of up to 128, with one
  atomic write per batch. Counters and per-operation transitions are kept.
  Up to 50ms of additional journal latency is accepted; this is not a durable
  per-command audit log. Slider drag state is not reset by device updates.

## Validation and rollout

GitHub Actions is the build authority. The pull request runs server pytest,
all seven legacy verification scripts, deployment tests, Android unit tests,
debug assembly and the existing signed-release job when secrets are available.
This change also expands Python compilation checks to `app` and `scripts`.

New regressions cover stale cleanup and state mutation, lock re-entry,
static account binding, repeat limits, ten-second/default and explicit-zero
holds, concurrent state updates, per-channel frame caching, ordered commands,
stop/overflow handling and disconnect cancellation ordering.

Before deployment, use the Actions-built APK for physical-device checks:

1. Drag sliders during AI waveform updates; switch between settings/logs and
   control. Capture frame-time/jank and main-thread traces on the same device
   and workload before and after this release. No numeric speedup is claimed.
2. Flood a relay inbox, stop from MCP and from the phone, then disconnect the
   network. Check that physical outputs stop and stale queued output does not
   resume. Test BLE off/on and phone background/foreground transitions.
3. Verify cached positive frames still receive watchdog refreshes, suction
   mode changes are sent, and reconnect does not suppress the first frame.
4. Verify history counts, clearing, reload and storage-failure feedback.
5. Preserve deployment configuration and data. Do not auto-migrate a running
   legacy database in this release. M2b/ORM cutover needs a backup, migration
   rehearsal and coordinated credentials/client rollout as a separate change.

Rollback is a source/APK rollback. This tranche changes no database schema.
Keep the deployed hotfix semantics when rolling back server code; an unpatched
older repository revision can reintroduce the reconnect ownership incident.
