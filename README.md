# Mimic Simple Voice Chat Integration

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

This is a separate Paper plugin that connects the **Mimic** creature plugin to
**Simple Voice Chat**. It records speech from connected players, rejects silence,
low background noise, and short impulses, stores the accepted clips, and replays
them spatially from Mimic entities.

This is an independent addon for
[Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat), created and
maintained by [Max Henkel (`henkelmax`)](https://github.com/henkelmax). Full
credit for Simple Voice Chat and its API belongs to Max Henkel and its
contributors; they are not affiliated with or responsible for this addon.

The behavior is inspired by Lethal Company voice-mimic addons such as Skinwalkers
and Mirage: every enemy independently selects real clips from the player whose
appearance it copied and says them at random times from its live position. Every
nearby Simple Voice Chat client hears the same synchronized clip.

## Requirements

- Paper matching the Mimic server (the project currently targets Paper API line
  26.1, compiled against `26.1.2.build.74-stable`)
- Java 25
- Mimic 1.0.0 or newer
- Simple Voice Chat with API 2.6.20 (or a newer compatible API)
- Mimic's own LibsDisguises and PacketEvents dependencies

Players also need the matching Simple Voice Chat client mod. The normal voice chat
UDP port must already be configured and reachable.

## Install

1. Run `mvn clean package`.
2. Put `target/MimicSimpleVoiceChatIntegration-1.0.0.jar` beside `Mimic-1.0.0.jar`
   and the Simple Voice Chat plugin in the server's `plugins/` directory.
3. Restart the server. Do not use a plugin hot-loader for voice chat addons.
4. Use `/mimicvoice status` to verify that the voice API is ready.

The plugin detects Mimics through the stable `mimic:mimic` persistent-data marker.
The preferred identity contract is `mimic:mimicked_player_uuid`; when present and
valid, that UUID is authoritative. The legacy `mimic:mimicked_player` name remains
supported for existing Mimic 1.0.0 entities and is resolved against an exact online
player first, then the persisted clip name index only when that name identifies one
UUID. Reused or ambiguous offline names remain unresolved rather than selecting a
different player's clips. Existing Mimics and recordings do not need to be deleted.
The UUID marker must be written by compatible Mimic-side
code; this addon does not claim that Mimic 1.0.0 produces it. The carrier has no
Bukkit custom name, preventing a mob-style proximity tag; LibsDisguises renders the
normal player nametag.

The plugin descriptor uses Paper API version `26.1` (the API line, not the full
Maven build string). Paper and Simple Voice Chat APIs are provided by the server
and are not bundled into the addon jar.

## Speech-only recording

Each player's Opus stream has its own decoder and stateful detector. The detector
uses an absolute RMS/peak gate plus an adaptive noise-floor margin and separate
release hysteresis. A clip is accepted only after enough frames were classified as
speech. A 1.2-second phrase boundary keeps words separated by natural pauses in one
clip, while long silence tails are trimmed and isolated clicks/packets are discarded.
At least 25% of an accepted phrase must contain voice-like frames, preventing
scattered noise spikes from accumulating into a clip. Phrases can be up to 30
seconds long. Persisted clips that fail this check are moved to
`recordings/_rejected_noise/` for operator inspection. Quarantined audio follows
the same retention window and is included in the admin clear commands.

Accepted audio is 48 kHz, 16-bit mono PCM under:

```text
plugins/MimicSimpleVoiceChatIntegration/recordings/<player UUID>/
```

Retention, per-player limits, voice thresholds, replay distance/volume, and
randomized first/repeat timing are configurable in `config.yml`. Each player has
a rolling clip pool: once it is full, saving a new phrase deletes that player's
oldest phrase. Retention cleanup runs periodically while the server is online.
With persistence disabled, the bounded pool stays in memory only.

The storage boundary is bounded in both directions: completed clips waiting for
registration are admitted only while they fit the configured pending-write byte
budget (64 MiB by default) and the fixed 256-save entry limit. In memory-only
mode, the per-player pool is additionally subject to the global
`storage.maximum-memory-audio-megabytes` cap (512 MiB by default); oldest memory
clips are evicted when that cap is reached. Disk playback reads use a separate
fixed 64-read queue; excess reads are rejected for a later playback retry rather
than accumulating in an unbounded executor.

Clear operations retain a reserved bounded control admission, so a saturated save
queue cannot silently displace privacy deletion. Playback selection for the
affected scope is suppressed until the asynchronous clear succeeds or fails;
failure is reported explicitly and is never presented as confirmed deletion.
Active playback PCM is also capped globally by
`playback.maximum-active-audio-megabytes` (128 MiB by default); excess playbacks
retry later without blocking the Bukkit thread.

By default, each Mimic chooses another random clip after a random 5–20 second
delay measured from the end of its previous clip. Playback uses a locational
channel that follows the carrier, which remains compatible with LibsDisguises.
Fragments shorter than one second are left on disk but skipped during playback.
A Mimic only selects clips owned by the exact player it currently copies; there
is no cross-player fallback.

## Commands

```text
/mimicvoice status
/mimicvoice reload
/mimicvoice clear <player|all>
/mimicvoice consent <allow|deny|status>
```

Administration requires `mimicvoice.admin`. Recording also checks the
`mimicvoice.record` permission, which defaults to all players and can be denied by
a permissions plugin. Recording consent defaults to allowed, and every joining
player receives a warning with the opt-out command before capture is enabled for
that session. Players can opt out without an administrator; opting out immediately
discards their unfinished capture. Consent changes are saved with atomic file
replacement, and a player is warned if persistence fails. Existing accepted and
quarantined clips can be removed with the admin clear command.

`/mimicvoice status` also reports the bounded capture queue depth/capacity,
received and processed packet counts, overload drops, accepted speech segments,
pending storage writes and their PCM byte total, successful/failed storage
saves, save/read backpressure rejections, pending playback reads, and current
memory-audio bytes, active playback PCM bytes, and playback PCM budget
rejections. If recording or storage is overloaded, the plugin drops
recording work without interrupting normal voice transmission; storage admission
never blocks the capture worker.

Voice recording laws and platform rules vary. The default join notice tells
players what is happening and how to opt out; server owners are responsible for
providing any additional notice or consent flow their jurisdiction requires.

## License

Copyright © 2026 ManujeroZX.

This addon is licensed under the
[GNU General Public License version 3 only](LICENSE) (`GPL-3.0-only`). Simple
Voice Chat, Mimic, Paper, and other dependencies remain under their respective
licenses.
