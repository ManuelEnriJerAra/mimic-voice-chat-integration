# Project purpose

This is a Paper addon integrating the Mimic plugin with Simple Voice Chat.

# Supported build

- Java 25 (`maven.compiler.release`)
- Paper API `26.1.2.build.74-stable`
- Simple Voice Chat API `2.6.20`

# Mandatory verification

Run both commands before production changes are considered complete:

- `mvn -B -ntp clean test`
- `mvn -B -ntp clean package`

# Threading invariants

- Never perform filesystem I/O on the Bukkit main thread.
- Never move Bukkit entity/world mutation to arbitrary worker threads.
- Microphone callbacks must do bounded, minimal work.
- Preserve packet ordering for each player's Opus decoder.
- Never permit an unbounded PCM/audio backlog.
- Async callbacks touching Bukkit entities must return to the Bukkit/Paper scheduler first.

# Audio invariants

- Audio is 48 kHz mono PCM.
- Use one decoder/session per speaker stream unless the architecture is explicitly changed.
- VAD regressions require tests.
- Do not cross-fallback clips between player identities.

# Privacy invariants

- Consent/opt-out state is keyed by UUID.
- Denying recording must stop unfinished capture.
- Failures to persist privacy state must remain visible to the user/admin.

# Compatibility

- Preserve backward compatibility with existing Mimic entities and recording files wherever reasonably possible.
- Any on-disk migration must be explicit and tested.

# Git/release discipline

- Never push, tag, or release without explicit user instruction.
- Do not rewrite published tags.
- All production changes require tests.
