# Mimic Simple Voice Chat Integration — Technical Guide

This document describes the plugin as it is implemented in this project. It is
intended for server owners, maintainers, and anyone integrating the plugin with
Mimic and Simple Voice Chat.

## Credits and attribution

This project is an independent addon for
[Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat), created and
maintained by [Max Henkel (`henkelmax`)](https://github.com/henkelmax). Full
credit for Simple Voice Chat and its API belongs to Max Henkel and its
contributors; they are not affiliated with or responsible for this addon.

## 1. What the plugin does

`MimicSimpleVoiceChatIntegration` is a Paper plugin that connects three systems:

1. Simple Voice Chat supplies compressed microphone packets from players.
2. This plugin decodes, segments, filters, and stores speech phrases per player.
3. Mimic entities later select clips belonging to the player they are copying and
   replay them through Simple Voice Chat as spatial audio.

The result is a voice-mimic effect: each Mimic independently says real phrases
captured from the player represented by its disguise. The playback channel is
locational, so listeners hear it from the Mimic's current position and the
channel follows the entity while the clip is playing.

The plugin does not perform speech recognition, text transcription, voice
conversion, mixing, or cross-player clip selection. It stores and replays raw
decoded PCM audio.

## 2. Runtime requirements and build

The Maven project currently targets:

| Component | Version or requirement |
| --- | --- |
| Java | 25 (`maven.compiler.release`) |
| Paper API | `26.1.2.build.74-stable` on API line `26.1`, provided by the server |
| Simple Voice Chat API | `2.6.20`, provided by the server |
| Mimic | Installed server plugin, declared as a hard dependency |
| Simple Voice Chat | Installed server plugin, declared as a hard dependency |
| Build artifact | `MimicSimpleVoiceChatIntegration-1.0.0.jar` |

The Paper and voice-chat dependencies are marked `provided`, so they are not
bundled into the jar. The resulting plugin must therefore run alongside the
matching server plugins. The project does not include a shading or relocation
step. In `plugin.yml`, `api-version: '26.1'` names the Paper API line; it is not
the full Maven build string.

Build and test commands:

```text
mvn -B -ntp clean test
mvn -B -ntp clean verify
mvn -B -ntp clean package
```

The plugin descriptor is `src/main/resources/plugin.yml`. It declares the main
class, the `Mimic` and `voicechat` dependencies, the `/mimicvoice` command, and
the two permissions used by the plugin.

## 3. Source layout

| Area | Main classes | Responsibility |
| --- | --- | --- |
| Bootstrap and commands | `MimicSimpleVoiceChatIntegration` | Creates services, registers events, handles commands, and coordinates shutdown |
| Configuration | `PluginSettings` | Reads `config.yml`, applies defaults and clamps, and exposes typed settings records |
| Voice-chat bridge | `MimicVoicechatAddon` | Implements the Simple Voice Chat plugin API and forwards microphone/server events |
| Recording | `VoiceRecordingManager` | Enqueues bounded microphone work and maintains one worker-owned decoder and segmenter session per player |
| Audio analysis | `VoiceActivitySegmenter`, `SpeechQuality` | Detects speech frames, creates phrases, and revalidates persisted files |
| WAV I/O | `WavIO` | Reads and writes 16-bit mono PCM RIFF/WAVE files |
| Storage | `ClipStore`, `VoiceClip` | Indexes clips, persists them, applies retention and pool limits, and serves playback reads |
| Consent | `ConsentRegistry` | Persists player opt-outs in YAML |
| Playback | `MimicPlaybackManager`, `MimicPlaybackController` | The manager adapts Bukkit and Simple Voice Chat; the controller owns identity-sensitive scheduling, load generations, and playback state |

## 4. Plugin lifecycle

### Enable sequence

`onEnable()` in `MimicSimpleVoiceChatIntegration` performs the following work:

1. Copies the packaged default configuration to the plugin data directory if it
   is not already present.
2. Parses the configuration into a volatile `PluginSettings` object.
3. Starts the asynchronous `ConsentRegistry` load for `recording-opt-outs.yml`.
4. Creates a `ClipStore` rooted at the plugin's `recordings` directory.
5. Creates the `VoiceRecordingManager` and the Simple Voice Chat addon.
6. Looks up the `BukkitVoicechatService`. If it is unavailable, the plugin logs
   an error and disables itself.
7. Registers the voice-chat addon with Simple Voice Chat.
8. Creates and starts the `MimicPlaybackManager`, which scans for Mimics once per
   second.
9. Registers Bukkit join/quit listeners and the `/mimicvoice` command executor.
10. Starts the bounded player-metadata snapshot task used by the microphone
    callback.
11. Starts asynchronous clip loading from disk. Capture remains paused until the
    consent load completes successfully; then notices are sent to online players
    and capture is enabled on the Bukkit thread.

Clip loading happens after the other services are started on the storage executor.
Playback can therefore be ticking briefly before persisted clips have finished
loading; during that period no loaded clips are available for selection.

### Disable sequence

`onDisable()` first makes the voice-chat addon inert and unregisters its volume
category, then stops playback and closes recording sessions. The recording
worker drains the already accepted bounded packet queue in FIFO order, flushes
active sessions, closes their Opus decoders, and is joined before storage is
closed. New packets and post-shutdown clip admission are rejected once shutdown
begins. If a third-party decoder ignores both `close()` and interruption, the
plugin reports a failed, fail-closed capture shutdown rather than claiming a
terminal worker state. Consent, bounded playback reads, and bounded storage work
are then closed on their respective workers; storage similarly reports a
non-terminal worker instead of permitting post-close index mutation.

### Join and quit behavior

A joining player immediately receives a yellow privacy notice when recording and
privacy notices are enabled. Capture remains blocked for that player until the
message has been sent. The message reports whether the player is currently opted
in and gives the appropriate consent command. Reloading configuration briefly
pauses capture while settings and notices are refreshed.

When a player quits, the current capture session is flushed and closed, and a
quit barrier rejects late packets until that UUID joins again. A phrase that is
already active can therefore be saved at quit even if its normal silence
boundary has not yet arrived.

## 5. End-to-end recording pipeline

The recording flow is:

```text
Simple Voice Chat microphone packet
        |
        v
MimicVoicechatAddon.onMicrophonePacket
        |
        v
VoiceRecordingManager eligibility checks
        |
        v
bounded FIFO capture queue (owned Opus bytes + immutable metadata)
        |
        v
single capture worker, per-player OpusDecoder
        |
        v
48 kHz PCM, split into 960-sample / 20 ms frames
        |
        v
VoiceActivitySegmenter
        |
        v
Completed phrase (short[] PCM)
        |
        v
ClipStore asynchronous save
        |
        +--> WAV file under recordings/<UUID>/, or memory-only clip
```

### Eligibility checks

For every microphone packet, `VoiceRecordingManager` checks:

1. `recording.enabled` is true.
2. Whisper packets are allowed when `recording.record-whispers` is true.
3. The player has not opted out in `ConsentRegistry`.
4. The player has the configured recording permission, unless the configured
   permission is blank.
5. The Opus payload is non-null and non-empty.

Failure of any of the first four checks immediately discards that player's
unfinished segment. An empty payload is ignored without creating or modifying a
capture session.

The default permission is `mimicvoice.record`, and that permission defaults to
true in `plugin.yml`. A permissions plugin can deny it for selected players.
The microphone callback performs only these checks, metadata capture, one Opus
payload copy, a non-blocking queue offer, and atomic counters. Decoding, PCM
iteration, VAD, and clip submission happen on the capture worker.

### Per-player sessions

Player state is indexed by UUID, but live sessions are owned exclusively by the
single capture worker. A session contains:

- The player's most recent name.
- A dedicated Simple Voice Chat `OpusDecoder`.
- A dedicated `VoiceActivitySegmenter` configured when the session is created.
- The time of the most recent packet.
- State used to detect an idle stream.

The bounded queue is FIFO, so packets for a player reach its decoder in enqueue
order and no decoder call can run concurrently with another call. Worker-side
control generations invalidate queued packets after consent denial, permission
loss, reload, server stop, quit, overflow, or expiry.

Decoded audio is split into fixed 960-sample blocks before entering the
segmenter. At 48,000 samples per second, this is exactly 20 milliseconds per
normal frame. If a decoder returns a partial final block, it is still processed
as one frame with its actual sample count. The segmenter copies each retained
decoder range once because active phrases outlive the decoder call. A completed
segment is then transferred to the bounded storage submitter without another
PCM copy; the general-purpose `ClipStore.save()` API remains defensive and
clones caller-owned arrays.

### Stream gaps and session expiration

The capture worker performs maintenance while polling the queue (every 100
milliseconds when idle):

- After `stream-reset-milliseconds` of silence/no packets, the active phrase is
  flushed and the Opus decoder state is reset. The session remains available for
  later packets.
- After 60 seconds with no packet, the session is removed and closed.

The default stream reset is 1.5 seconds. This is separate from the VAD's phrase
hangover: the stream reset handles a missing microphone stream, while hangover
handles silence inside a live stream.

Disabling recording, denying consent, losing the recording permission, or
discarding a session from a command resets the segmenter without saving its
unfinished phrase. A queue overflow also discards the affected session and
invalidates all queued packets from that generation, so a clip cannot span the
dropped packet gap.

## 6. Voice activity detection and phrase construction

`VoiceActivitySegmenter` is an energy-based detector for 48 kHz mono PCM. It
does not use a speech model. Every frame is summarized by:

- RMS level in decibels relative to full-scale PCM.
- Peak level in decibels relative to full-scale PCM.

For each frame, both the RMS and peak conditions must pass:

```text
RMS >= max(activationDb, noiseFloorDb + noiseMarginDb) - hysteresis
peak >= peakDb - hysteresis
```

Hysteresis is zero when starting a phrase. Once a phrase is active,
`release-hysteresis-db` lowers both thresholds, making it easier for quieter
parts of the same phrase to remain connected.

While no phrase is active, non-speech frames update an adaptive noise floor. The
level is smoothed with a 95% previous / 5% current blend, capped below the
activation threshold, and bounded at -90 dB. This allows the detector to account
for a modest background noise level without treating it as speech.

### Default VAD values

| Setting | Default | Frame equivalent | Effect |
| --- | ---: | ---: | --- |
| `activation-db` | -42 dB | — | Minimum RMS gate for starting speech |
| `peak-db` | -32 dB | — | Minimum peak gate |
| `noise-margin-db` | 10 dB | — | Required margin above the learned noise floor |
| `release-hysteresis-db` | 5 dB | — | Threshold reduction while a phrase is active |
| `pre-roll-milliseconds` | 100 ms | 5 frames | Keeps a small amount before the first detected speech frame |
| `hangover-milliseconds` | 1,200 ms | 60 frames | Silence duration that ends a phrase |
| `minimum-speech-milliseconds` | 500 ms | 25 frames | Minimum number of speech-classified frames |
| `minimum-clip-milliseconds` | 900 ms | 45 frames | Minimum trimmed output length |
| `minimum-speech-ratio` | 0.25 | — | At least 25% of output frames must be speech-classified |
| `maximum-clip-seconds` | 30 s | 1,500 frames | Hard upper bound for one active phrase |
| `stream-reset-milliseconds` | 1,500 ms | — | Flush/reset threshold for a missing packet stream |

The values in `PluginSettings.from()` are clamped to safe ranges. For example,
the activation threshold is constrained between -80 and -6 dB, the maximum clip
length between one and sixty seconds, and the speech ratio between 0.05 and
1.0. This means an invalid or extreme YAML value is converted into an effective
runtime value rather than being used as-is.

### State transitions

When inactive, the segmenter retains only the configured pre-roll of
non-speech frames. When the first speech frame arrives, it starts an active
phrase and prepends that pre-roll.

While active, every frame is appended. A speech frame increments the voiced
counter and resets the silent-tail counter. A non-speech frame increments the
silent-tail counter. The phrase finishes when either:

- The silent tail reaches the hangover duration; or
- The maximum clip length is reached.

At completion, the segmenter retains the configured pre-roll before the first
speech frame, trims the long silence tail, and keeps one 20 ms boundary frame
after the last speech frame. The phrase is accepted only if all three conditions
hold:

```text
voicedFrames >= minimumSpeechFrames
outputFrames >= minimumClipFrames
voicedFrames / outputFrames >= minimumSpeechRatio
```

Rejected phrases are discarded from the active in-memory segment. This is what
prevents silence, a single click, or sparse intermittent noise spikes from
becoming a clip. The test suite explicitly covers all three cases, as well as
natural pauses shorter than the 1.2-second default hangover.

## 7. Clip storage and file format

The plugin data directory is normally:

```text
plugins/MimicSimpleVoiceChatIntegration/
├── config.yml
├── recording-opt-outs.yml
└── recordings/
    ├── <player UUID>/
    │   └── <timestamp>_<safe player name>_<random suffix>.wav
    └── _rejected_noise/
        └── <player UUID>/
            └── <original file name>.wav
```

Accepted persisted clips are standard RIFF/WAVE files containing:

- PCM format code 1.
- One channel.
- 48,000 Hz sample rate.
- 16 bits per sample.
- Little-endian signed samples.

`WavIO.write()` writes to a uniquely named temporary file and then replaces the
target, using an atomic move where the filesystem supports it. This prevents a
partially written target from normally appearing as a completed clip. The
temporary file is removed whether writing, moving, or replacement succeeds or
fails.

`WavIO.read()` accepts RIFF files with extra chunks such as metadata, but the
audio data itself must be 16-bit mono PCM. It limits individual chunk sizes to
128 MiB. `WavIO.inspect()` currently reads the full audio data before returning
its metadata; it is an inspection convenience, not a header-only parser.

### Persistence modes

With `storage.persist-clips: true`, accepted clips are written asynchronously to
disk and are reloaded on the next startup.

With persistence disabled, clips are stored as `short[]` arrays in memory. The
same per-player maximum still applies, but all clips disappear on restart. A
global `storage.maximum-memory-audio-megabytes` limit also bounds the total
memory-only PCM pool; the oldest memory clips are evicted when it is exceeded.

Storage file work is isolated from the Bukkit main thread. A single bounded
storage worker serializes file writes, startup scanning, deletions, quarantine
moves, and retention passes; a separate single bounded read worker handles disk
playback reads. Bulk storage work uses a reserved bounded admission slot for
clear/control operations, so a saturated save queue cannot silently displace a
privacy deletion. Playback admission is bounded at 64 pending reads. Completed
PCM save submissions are bounded by both 256 pending entries and
`storage.maximum-pending-write-megabytes` (64 MiB by default). Admission is
non-blocking: a clip that would exceed either bound is rejected and counted as a
backpressure rejection. The PCM array returned by the segmenter is transferred
to `ClipStore.saveOwned()` without another full-array clone; the public defensive
`save()` path still clones caller-owned arrays.

### Retention and rolling pools

Each clip has a creation timestamp taken from the filename and from the in-memory
`VoiceClip` record. On startup, clips older than `retention-hours` are deleted.
The per-player pool is then sorted newest-first and truncated to
`maximum-clips-per-player`. Saving a new clip applies the same limit, deleting
the oldest clips first.

The defaults are 72 hours of retention and 20 clips per player. A maximum-length
30-second clip is about 2.88 MB of raw 16-bit mono PCM, so the configured pool
size can have a meaningful memory/disk cost.

Retention is enforced at startup, whenever a player's rolling pool is updated,
and by maintenance every five minutes. The maintenance pass also expires
quarantined WAV files according to their last-modified time.

### Startup validation and quarantine

Persisted WAVs are read and checked before being registered. Files that are
older than the retention cutoff are deleted. Files with unsupported audio
properties or invalid names are ignored. Files that fail the persisted speech
quality check are moved to:

```text
recordings/_rejected_noise/<player UUID>/
```

They are moved rather than immediately deleted so an operator can inspect or
recover them. They remain subject to `storage.retention-hours`, and both the
per-player and global clear commands delete matching quarantined files.

## 8. Consent, privacy, and permissions

Consent is represented as an opt-out set, not an opt-in set. A player can use:

```text
/mimicvoice consent allow
/mimicvoice consent deny
/mimicvoice consent status
```

`allow` removes the player's UUID from the opt-out set. `deny` adds it and
immediately discards that player's unfinished capture. The set is stored in
`recording-opt-outs.yml` and keyed by UUID, so it survives username changes.
Consent YAML is written to a temporary file and atomically replaces the previous
file where supported. If persistence fails, the choice still applies to the
current server session and the player receives an explicit warning that it may
not survive reconnecting.

Opting out does not delete clips that were already saved. An administrator must
use `/mimicvoice clear <player>` or `/mimicvoice clear all` to remove existing
accepted and quarantined clips.

The plugin's default join notice explains that recording is enabled and gives
the opt-out command. With notices enabled, the recording manager rejects packets
until the notice has been delivered for that player session. Server owners remain
responsible for configuring the recording policy, notices, and any additional
consent process required by their jurisdiction or platform rules.

## 9. Mimic discovery and player association

`MimicPlaybackManager` runs a Bukkit task once per second and scans every loaded
world for `Vindicator` entities. An entity is treated as a Mimic when its
persistent data container contains the byte key:

```text
mimic:mimic
```

The preferred identity marker is the string key:

```text
mimic:mimicked_player_uuid
```

When present, the marker must be a canonical UUID. It is authoritative and is
used directly; the legacy name is retained as display/compatibility metadata and
cannot override it. A malformed UUID marker is handled safely, logged once per
marker state, and left unresolved rather than risking playback for the wrong
player.

For old entities, the legacy string key remains supported:

```text
mimic:mimicked_player
```

If the UUID marker is absent, the legacy name (or the existing Bukkit custom-name
fallback when the key is absent) is resolved in this order:

1. An exact online player name, yielding the current UUID.
2. The name index loaded from saved clips, for offline playback.

The clip selector receives only the resulting UUID. It never falls back to another
player's pool. If neither marker nor legacy name can be resolved, the Mimic waits
and tries again later. This keeps old Mimic 1.0.0 entities and their recordings
compatible without requiring deletion or recreation. A future Mimic-side change
may populate the UUID marker; this addon does not claim that the current Mimic
release writes it.

The state is attached to the entity UUID, so each Mimic has independent timing,
last-clip tracking, loading state, and active playback. If its represented
player changes—including a UUID marker, legacy name, or resolved UUID change—any
current audio is stopped, in-flight loads are invalidated, and a new first-delay
schedule is created.

## 10. Playback state machine

For each tracked Mimic, the manager performs the following checks once per tick:

1. Playback is enabled and the Simple Voice Chat server API is available.
2. The entity is still valid and still has the Mimic marker.
3. The represented player identity has not changed.
4. The Mimic is not already loading a clip or playing one.
5. The next scheduled playback time has arrived.
6. At least `minimum-nearby-listeners` connected voice-chat players are within
   the configured distance.
7. A clip exists for the exact represented player and is not the previous clip
   when another candidate is available.

If there are no nearby voice-chat listeners, the next check is one second later.
If no eligible clip exists, the next check uses
`retry-without-clip-seconds`.

Clip file reads happen asynchronously. The callback returns to the Bukkit main
thread before validating entity identity and starting playback. This protects
the entity and API interactions from being performed on the storage thread.

When a clip is ready, the manager creates a Simple Voice Chat
`LocationalAudioChannel` with:

- A fresh random channel UUID.
- The Mimic's world and eye position.
- The configured audible distance.
- The `mimic_voice` volume category, displayed as “Mimic voices”.

The configured `playback.volume` is applied as a sample gain before the audio
player starts. Samples are clamped to the signed 16-bit range to avoid integer
overflow distortion at the gain boundary.

While the clip plays, the channel position is updated every playback tick to
follow the Mimic. When the audio player stops, the next random repeat delay is
measured from that stop event. The default first and repeat delay ranges are
both 5–20 seconds. A clip shorter than `minimum-clip-seconds` is left in storage
but excluded by `ClipStore.select()`; the default is one second.

If playback is disabled, the voice API disappears, the entity is removed, or
configuration is reloaded, active audio is stopped and in-flight loads are
invalidated through an identity version counter. The clear commands perform the
same invalidation before deleting accepted/quarantined clips, so an active
playback stops and a pending read cannot start after the clear. A clip read
started for one identity is checked again on the Bukkit thread before it can
start, so it cannot play after the entity changes to another identity.

## 11. Commands and permissions

| Command | Who can use it | Behavior |
| --- | --- | --- |
| `/mimicvoice status` | `mimicvoice.admin` | Reports API readiness, clip/player counts, active captures, tracked Mimics, active/total playbacks, queue depth/capacity, received/processed packets, overload drops, accepted segments, pending-write count/bytes, pending playback reads, successful/failed saves, save/read backpressure rejections, memory-audio bytes, active playback PCM bytes, and playback PCM budget rejections |
| `/mimicvoice reload` | `mimicvoice.admin` | Reloads `config.yml`, flushes or discards capture sessions depending on the new recording setting, and resets playback states |
| `/mimicvoice clear <player>` | `mimicvoice.admin` | Removes accepted and quarantined clips for an online name or a name known by the clip index |
| `/mimicvoice clear all` | `mimicvoice.admin` | Removes all indexed accepted clips and all quarantined WAV files |
| `/mimicvoice consent allow` | Any player | Enables future recording for that player |
| `/mimicvoice consent deny` | Any player | Disables future recording and discards the unfinished phrase |
| `/mimicvoice consent status` | Any player | Reports that player's current consent state |

The command alias is `/mvc`. `mimicvoice.admin` defaults to operators, while
`mimicvoice.record` defaults to all players.

The clear operations are asynchronous because file deletion runs on the storage
executor. Playback state is invalidated immediately on the Bukkit thread before
deletion is queued, and playback selection for the affected scope is suppressed
until the clear future completes. The confirmation or explicit failure message
is sent back on the Bukkit thread after the operation completes. A capture
already in progress may still produce a later clip unless recording is denied or
disabled; a failed clear never claims that deletion was confirmed.

## 12. Configuration reference

All keys are in `src/main/resources/config.yml`. `PluginSettings.from()` applies
the runtime clamps described below.

### Recording

| Key | Default | Runtime range / meaning |
| --- | ---: | --- |
| `recording.enabled` | `true` | Master capture switch |
| `recording.record-whispers` | `true` | Accept or reject packets marked as whispers |
| `recording.permission` | `mimicvoice.record` | Blank disables the permission gate |
| `recording.privacy-notice` | `true` | Sends the delayed join notice |

### Voice activity

| Key | Default | Runtime range |
| --- | ---: | --- |
| `activation-db` | `-42.0` | `-80.0` to `-6.0` |
| `peak-db` | `-32.0` | `-80.0` to `-3.0` |
| `noise-margin-db` | `10.0` | `1.0` to `30.0` |
| `release-hysteresis-db` | `5.0` | `0.0` to `15.0` |
| `pre-roll-milliseconds` | `100` | `0` to `500` |
| `hangover-milliseconds` | `1200` | `40` to `3000` |
| `minimum-speech-milliseconds` | `500` | `20` to `5000` |
| `minimum-clip-milliseconds` | `900` | `20` to `5000` |
| `minimum-speech-ratio` | `0.25` | `0.05` to `1.0` |
| `maximum-clip-seconds` | `30` | `1` to `60` |
| `stream-reset-milliseconds` | `1500` | `100` to `10000` |

Millisecond values are converted to frames with a ceiling operation based on
the fixed 20 ms frame size. This avoids rounding a configured duration down to
zero frames.

### Storage

| Key | Default | Runtime range / meaning |
| --- | ---: | --- |
| `storage.persist-clips` | `true` | Disk-backed WAVs or memory-only clips |
| `storage.maximum-clips-per-player` | `20` | `1` to `200` clips |
| `storage.retention-hours` | `72` | `1` hour to 365 days |
| `storage.maximum-pending-write-megabytes` | `64` | `1` to `4096` MiB of queued PCM |
| `storage.maximum-memory-audio-megabytes` | `512` | `1` to `8192` MiB of memory-only PCM |

### Playback

| Key | Default | Runtime range / meaning |
| --- | ---: | --- |
| `playback.enabled` | `true` | Master playback switch |
| `playback.distance` | `32.0` | `1.0` to `128.0` blocks/meters as interpreted by Simple Voice Chat |
| `playback.volume` | `0.90` | `0.0` to `2.0` sample gain |
| `playback.minimum-clip-seconds` | `1.0` | `0.1` to `30.0`; shorter clips are skipped |
| `playback.minimum-nearby-listeners` | `1` | `0` to `100` connected listeners |
| `playback.first-delay-seconds.minimum` | `5` | `0` to `3600` |
| `playback.first-delay-seconds.maximum` | `20` | At least the first minimum, at most `3600` |
| `playback.repeat-delay-seconds.minimum` | `5` | `1` to `3600` |
| `playback.repeat-delay-seconds.maximum` | `20` | At least the repeat minimum, at most `3600` |
| `playback.retry-without-clip-seconds` | `10` | `1` to `600` |
| `playback.maximum-active-audio-megabytes` | `128` | `1` to `8192` MiB of active playback PCM |

The first delay may be configured as zero. Repeat delay is always at least one
second after clamping. Active PCM passed to Simple Voice Chat players is also
bounded globally by `playback.maximum-active-audio-megabytes`; playbacks that
would exceed the budget are rejected non-blockingly and retried later.

## 13. Threading and shutdown model

The implementation deliberately separates packet dispatch, audio processing, and
file work:

- Bukkit's main thread handles plugin lifecycle, commands, entity scanning,
  playback creation, and location updates.
- The Simple Voice Chat microphone callback reads only the sender UUID and an
  immutable player snapshot, copies the owned Opus payload, captures
  UUID/name/whisper/generation metadata, offers a `PacketWork` item to a bounded
  `ArrayBlockingQueue`, and returns. It never calls Bukkit `Player` methods,
  decodes, runs VAD, touches files, or waits for the worker.
- One daemon platform thread named `mimic-voice-capture-worker` owns all
  `CaptureSession` objects, decoders, segmenters, FIFO packet processing, and
  100 ms idle/expiry maintenance. It never receives a Bukkit `Player` object.
- A daemon bounded storage worker named `mimic-voice-storage` handles WAV I/O,
  clip indexing work, retention, and deletion. A separate bounded
  `mimic-voice-read` worker admits at most 64 pending disk reads. Pending PCM
  save arrays are capped at 256 entries and the configured pending-write byte
  budget; admission is non-blocking and rejected work is counted separately from
  actual storage failures.
- Asynchronous playback reads return to the Bukkit thread before touching entity
  state or starting audio.

The capture queue has capacity 512 and uses non-blocking `offer`. On overflow,
the packet is counted as an overload drop and the affected generation is
discarded before future packets are decoded. This passive recorder never blocks
or cancels normal Simple Voice Chat transmission. Consent denial and other
discard controls invalidate queued work; a finish control flushes only work
already processed into the active session.

On plugin shutdown, no new packets are accepted. The capture worker drains its
accepted queue, flushes active sessions, closes decoders, and is joined for up to
ten seconds (with a final cancellation/interrupt fallback that clears queued
work). If it remains live because a third-party decoder is non-cooperative,
shutdown is reported as failed and publication stays disabled. Bounded read and
storage workers are then closed with their own completion windows and a second
terminal check; a failed check is logged explicitly and post-close
registration/index mutation remains disabled.

## 14. Automated tests

The current test suite contains 82 passing tests:

- `WavIOTest` verifies 48 kHz mono PCM write/read round-tripping and temporary
  file cleanup after a successful write.
- `VoiceActivitySegmenterTest` verifies silence rejection, impulse rejection,
  speech acceptance, trailing-silence trimming, microphone-gap flushing, natural
  pause preservation, configured pre-roll retention, and sparse-noise rejection.
- `SpeechQualityTest` verifies dense speech passes while sparse spikes fail.
- `ClipStoreTest` verifies names containing underscores, exact player ownership,
  oldest-clip eviction, quarantine of persisted sparse noise, quarantine
  retention, offline quarantined-name indexing, accepted/quarantined clear
  behavior, bounded pending-write accounting, failed-save cleanup, FIFO clear
  ordering, saturated clear/control admission, global memory-only eviction,
  defensive caller-array ownership, bounded disk-read admission, corrupt
  playback-file quarantine, and non-cooperative storage shutdown.
- `PendingAudioBudgetTest` verifies non-blocking admission against both the
  pending-entry and pending-byte limits.
- `ConsentRegistryTest` verifies durable opt-out/opt-in round-trips, temporary
  file cleanup, explicit persistence-failure reporting, and bounded retention
  when persistence is blocked.
- `MimicVoicechatAddonTest` verifies shutdown makes the addon inert and removes
  its registered volume category, and verifies microphone admission uses the
  immutable UUID/snapshot boundary without Bukkit `Player` access.
- `MimicIdentityResolverTest` verifies UUID precedence, exact online-name and
  persisted-name compatibility, malformed-marker handling, custom-name
  compatibility, and the absence of cross-player fallback.
- `MimicIdentityTrackerTest` verifies in-flight load invalidation, active
  playback stop callbacks, reset of last-clip state, and preservation of
  scheduling for an unchanged identity.
- `MimicPlaybackControllerTest` verifies the playback boundary independently of
  Bukkit: Mimic filtering, exact UUID ownership, legacy resolved-name input,
  delay boundaries, listener gating, no-clip retry, identity/load generation
  invalidation, removal/reload/API/setting shutdown, clear invalidation and
  active-stop behavior, channel-follow updates, gain clamping, alternate-clip
  selection, cleanup after a throwing stop, active-playback PCM admission/release,
  idempotent close, and main-thread dispatch of storage completions.
- `MimicVoicePipelineComponentTest` combines the real voice activity segmenter,
  asynchronous `ClipStore` save/index, UUID-targeted selection, and the
  playback request sink. It is a component test, not a live Paper or UDP test.
- `VoiceRecordingManagerTest` verifies callback offload, per-player FIFO order,
  single-decoder serialization, bounded overflow recovery, consent-denial and
  finish/quit barriers, reload invalidation, normal accepted capture, idempotent
  worker shutdown including fail-closed non-cooperative decoder handling, atomic
  generation/save admission, fatal-queue cleanup,
  separate speech-acceptance/submission-failure counters, and a 2,000-packet
  bounded-queue stress path.

The deterministic tests do not run an actual Paper server, Simple Voice Chat
server, Mimic entity, or networked client. Bukkit scanning, plugin lifecycle,
real PDC contents, and live spatial playback therefore still require the
manual smoke test in `MANUAL_INTEGRATION_TEST.md`.

## 15. Continuous integration and manual coverage

`.github/workflows/ci.yml` runs on pull requests and pushes to `main`. It uses
Temurin Java 25 with Maven dependency caching and runs `mvn -B -ntp clean verify`;
the workflow has no deployment, release, or publishing step. The manual
runbook covers the server/plugin/client wiring and UDP-backed spatial-audio
checks that are intentionally not automated in this repository.

## 16. Implementation notes and operational limitations

These details are important when diagnosing behavior or extending the plugin:

1. **Capture and startup quality checks are not identical.** Live capture uses
   the adaptive noise-floor margin and trims phrases before applying the
   minimum-clip and speech-ratio checks. `SpeechQuality`, used while loading
   persisted files, applies fixed RMS/peak thresholds with simple hysteresis; it
   does not reproduce the adaptive noise floor and does not separately enforce
   `minimum-clip-milliseconds`. A persisted clip can therefore be judged
   differently after a restart.
2. **Capture and storage counters describe different events.** The accepted
   speech-segment counter increments when a completed phrase passes capture
   validation. ClipStore separately reports saves that succeeded, failed, or
   were rejected by backpressure; pending counts and bytes are released when
   each asynchronous save reaches a terminal state.
3. **Capture overload is intentionally lossy for recording only.** The 512-item
   capture queue uses non-blocking admission. An overflow drops the packet,
   invalidates the affected decoder generation, and discards its unfinished
   phrase so audio is never stitched across the gap; the player's normal voice
   transmission is not cancelled.
4. **Clearing clips does not cancel an active capture session.** If a player is
   currently speaking, a later completed phrase can be saved after an admin has
   cleared that player's existing pool. Clear does stop active playback and
   invalidates pending playback reads. Use consent denial or disable recording
   when the unfinished capture must also be discarded.
5. **Invalid files found during playback reads are removed from active playback.**
   A read failure returns no samples, removes the clip from the active index, and
   moves an existing file to the rejected-noise quarantine when possible. This
   prevents the same corrupt clip from being selected and warned about every
   playback attempt.
6. **Mimic identity is UUID-first with a legacy boundary.** The manager prefers
   `mimic:mimicked_player_uuid` and uses it as the authoritative clip owner. If
   that marker is absent, it preserves the old `mimic:mimicked_player` exact
   online-name and persisted-name-index lookup, plus the existing custom-name
   fallback. A malformed UUID marker is intentionally unresolved instead of
   falling back to a potentially wrong name. Old Mimic entities and recordings
   remain usable; the current addon does not claim that Mimic itself writes the
   UUID marker.
7. **The playback scanner currently targets Vindicators.** Mimic must expose its
   carrier as a `Vindicator` with the `mimic:mimic` marker for this integration to
   discover it.
## 17. Files to inspect when changing behavior

For common maintenance tasks, these are the primary change points:

- Change defaults, validation ranges, or frame conversion in
  `PluginSettings.java` and `src/main/resources/config.yml`.
- Change live speech detection in `VoiceActivitySegmenter.java`.
- Change persisted-file revalidation in `SpeechQuality.java`.
- Change file naming, WAV validation, retention, or pool selection in
  `ClipStore.java` and `WavIO.java`.
- Change player consent behavior in `ConsentRegistry.java` and the command/event
  handling in `MimicSimpleVoiceChatIntegration.java`.
- Change Bukkit discovery or locational voice-chat adaptation in
  `MimicPlaybackManager.java`; change identity-sensitive timing, load
  generations, or playback lifecycle in `MimicPlaybackController.java`.
- Change deterministic playback/component coverage in
  `MimicPlaybackControllerTest.java` and
  `MimicVoicePipelineComponentTest.java`.
- Change Simple Voice Chat event registration or volume-category setup in
  `MimicVoicechatAddon.java`.
- Change the live smoke-test procedure in `MANUAL_INTEGRATION_TEST.md` and CI
  triggers/verification in `.github/workflows/ci.yml`.
