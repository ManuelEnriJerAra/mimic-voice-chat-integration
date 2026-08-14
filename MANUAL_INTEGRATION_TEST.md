# Manual Paper integration test

This repository has deterministic unit/component coverage, but it does not
ship a Paper server, Mimic, Simple Voice Chat, or a voice-chat client. Use this
runbook for the live plugin-wiring and spatial-audio smoke test.

## Prerequisites

- Java 25 and a Paper server on the 26.1 API line.
- A compatible Mimic release installed and configured.
- Simple Voice Chat installed on the server and matching client mod installed
  for each tester.
- The server's Simple Voice Chat UDP port configured and reachable from the
  client. This test does not attempt to validate firewall or router behavior.

Obtain Paper, Mimic, Simple Voice Chat, and any Mimic runtime dependencies from
their respective official distribution channels. Do not commit or redistribute
server binaries, plugin jars, client mods, credentials, or private server data.

## Build and install

1. Run `mvn -B -ntp clean package`.
2. Copy `target/MimicSimpleVoiceChatIntegration-1.0.0.jar` into the server's
   `plugins/` directory alongside Mimic and Simple Voice Chat.
3. Start the server normally and wait for all three plugins to enable.
4. Confirm `/mimicvoice status` reports a ready voice API and no startup error.

## Smoke checks

1. Join with a client that has Simple Voice Chat connected.
2. Confirm the privacy notice appears and grant recording consent if required.
3. Speak a phrase longer than the configured minimum; verify the status command
   reports an accepted segment and a successful storage save.
4. Create or trigger a Mimic using the installed Mimic plugin. Verify that its
   entity carries `mimic:mimic` and inspect its PDC with the server's normal
   administrative/debug tooling.
5. For a UUID-backed entity, verify `mimic:mimicked_player_uuid` is a canonical
   UUID and that `mimic:mimicked_player` remains present as the display/legacy
   name. For an old name-only entity, verify playback still resolves the exact
   online name and can use the persisted clip name index when the player is
   offline.
6. With a listener within the configured distance, wait for playback. Confirm
   the phrase is heard from the Mimic's live position and follows it while the
   clip plays.
7. Move the copied identity to another player, remove/unload the entity, reload
   the integration, and toggle playback off. In each case confirm active audio
   stops and no delayed clip from the old identity starts afterward.
8. Check the server log for unexpected exceptions, repeated malformed-UUID
   warnings, or Bukkit/entity access from a storage worker.

## Result record

Record the Paper, Mimic, Simple Voice Chat, Java, addon, and client versions;
the server log excerpt for enable/status; whether online and offline legacy
resolution worked; whether UUID precedence and identity-change cancellation
worked; and the playback/listener result. Redact player names, UUIDs, IPs, and
other personal or private server data before sharing logs.

This runbook is a manual smoke test, not a claim of automated end-to-end UDP
coverage. Automated CI runs the deterministic tests and builds the addon only.
