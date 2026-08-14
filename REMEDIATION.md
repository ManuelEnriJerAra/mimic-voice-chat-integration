# Independent Review Remediation

This file maps every Critical, High, and Medium finding from the independent review to its reproduction, disposition, fix, and regression coverage. The independent review artifacts remain unchanged in the sibling review directory.

## Current remediation

### `IDENTITY-001` — ambiguous persisted legacy names

- Reproduction: two memory-only clips for different UUIDs with the same `ReusedName` made the old last-writer-wins `Map<String, UUID>` return the most recently registered UUID for offline resolution.
- Root cause: the persisted name index stored only one UUID and overwrote earlier owners.
- Fix: `ClipStore` now stores a UUID set per normalized name and `findPlayerId()` returns a UUID only for a unique owner. The clear command checks an exact online player before consulting the offline index.
- Regression tests: `ClipStoreTest.legacyNameIndexIsUnresolvedWhenNameBelongsToMultiplePlayers`, `MimicIdentityResolverTest.exactOnlineLegacyNameBeatsConflictingPersistedIndex`.
- Status: **resolved**; valid UUID markers remain authoritative and name-only Mimics remain backward compatible when the name is unique.

### `PRIVACY-001` — clear falsely confirmed after deletion failure

- Reproduction: replacing an accepted clip and quarantined clip with non-empty directories caused the old `clearPlayer()` future to complete successfully while paths remained.
- Root cause: ordinary deletion helpers logged `IOException` and returned normally; clear removed index state before deletion and treated the count as confirmation.
- Fix: explicit clear paths now collect and propagate accepted-file, quarantined-file, and targeted cleanup failures through the future. Successfully deleted active clips are removed; failed active entries and name metadata remain available for retry. The command's asynchronous failure message remains on the Bukkit thread.
- Regression test: `ClipStoreTest.clearReportsAcceptedAndQuarantinedDeletionFailures` asserts an exceptional future, retained paths, and retained retryable playback state.
- Status: **resolved**; deletion failures are visible and never presented as confirmed privacy deletion.

### `CAPTURE-001` — shutdown dropped accepted work

- Reproduction: a gated decoder accepted two packets; shutdown began while the first decode was blocked. The old implementation set `closed` before draining, processed only one packet, emptied the queue, and rejected the active-session flush.
- Root cause: `closed` combined the no-new-admission boundary with the worker's drain/publish state, and normal shutdown interrupted the worker immediately.
- Fix: `shutdownRequested` now closes packet/control admission while `closed` remains false during normal worker drain. The worker processes accepted FIFO work, flushes active sessions, then sets terminal `closed`. Normal shutdown no longer interrupts a decoder; the bounded timeout fallback still closes/interrupts non-cooperative decoders, marks the manager fail-closed, and clears work that could not be drained.
- Regression test: `VoiceRecordingManagerTest.normalShutdownDrainsAcceptedPacketsAndFlushesActiveSession` verifies accepted packets drain, active output is flushed, and a late packet is rejected.
- Status: **resolved** for the normal graceful path; non-cooperative external decoder termination remains bounded and visibly fail-closed as documented.

## Prior High and Medium findings revalidated

These findings were already remediated before this change. They were rechecked against the current tree and their regression tests still pass.

| ID | Reproduction/disposition | Current regression coverage | Status |
|---|---|---|---|
| `VOICE-001` | The stale-generation save race is not reproducible because generation validation and bounded clip admission share the state lock. | `VoiceRecordingManagerTest.generationCheckAndClipAdmissionAreAtomicAgainstDiscard` | Resolved |
| `THREAD-001` | Consent persistence remains off Bukkit and blocked persistence does not block the decision path. | `ConsentRegistryTest.consentChangeDoesNotWaitForBlockingPersistence`, `blockedPersistenceRetainsOnlyBoundedWaitersAndClearsLoadOverrides` | Resolved |
| `THREAD-002` | The microphone callback uses SVC UUID/packet data and does not access Bukkit `Player`. | `MimicVoicechatAddonTest.microphoneCallbackUsesVoicechatUuidAndDoesNotTouchBukkitPlayer` | Resolved |
| `STORAGE-001` | Clear invalidates playback before deletion and late disk-read completions fail identity/version validation. | `MimicPlaybackControllerTest.clearPlayerInvalidatesInFlightReadAndStopsActivePlayback`, `identityChangeInvalidatesInFlightRead` | Resolved |
| `QUEUE-001` | Capture, storage writes, and disk reads retain fixed or byte-bounded admission. | `ClipStoreTest.diskPlaybackReadsHaveASeparateBoundedAdmissionQueue`, `VoiceRecordingManagerTest.queueOverflowIsNonBlockingAndInvalidatesTheDecoderSession` | Resolved |
| `LIFE-001` | A non-cooperative decoder still cannot be force-killed safely, but shutdown has bounded waits, visible failure, and fail-closed publication/admission. | `VoiceRecordingManagerTest.nonCooperativeDecoderShutdownFailsClosedAndBlocksPostShutdownSaves` | Mitigated with documented limitation |
| `LIFE-002` | A non-cooperative storage task can outlive the deadline, but post-close registration/index mutation is blocked and shutdown reports non-terminal state. | `ClipStoreTest.storageShutdownReportsNonCooperativeReaderAndBlocksPostCloseMutation`, `storageShutdownDoesNotRegisterAPendingSaveAfterClose` | Mitigated with documented limitation |
| `CONSENT-001` | Consent waiters and initial-load overrides are bounded and overflow is visible. | `ConsentRegistryTest.blockedPersistenceRetainsOnlyBoundedWaitersAndClearsLoadOverrides` | Resolved |
| `CLEAR-001` | Reserved bounded control admission protects clear requests under bulk saturation; exceptional futures are reported. | `ClipStoreTest.clearControlAdmissionRemainsAvailableWhenBulkStorageIsSaturated` | Resolved; deletion I/O is covered separately by `PRIVACY-001` |
| `MEM-001` | Active playback PCM is globally budgeted and released on stop/completion/failure. | `MimicPlaybackControllerTest.activePlaybackPcmBudgetRejectsExcessAndReleasesOnStop` | Resolved |
| `PLAY-001` | Playback state cleanup survives stop exceptions; the pinned SVC player stop path flushes/cleans its player thread. | `MimicPlaybackControllerTest.playbackStopFailureDoesNotAbortStateCleanup` | Resolved |

No Critical finding was present in the independent review. The Low follow-ups (`STATE-001`, `PERF-001`, and `REL-001`) remain non-blocking and are documented in the independent report.

## Validation

- `mvn -B -ntp clean verify` — passed; 86 tests, 0 failures/errors/skips.
- `mvn -B -ntp package` — passed; 86 tests, 0 failures/errors/skips.
- Candidate JAR inspection found 71 entries, no bundled server/test APIs, and the expected plugin resources.
- Candidate JAR SHA-256: `E57B6DEF28DCF8E3310981C6ACEC36F9504B12227A3BF19490B583E5159230BA`.
- A fresh self-review checked callback/thread ownership, bounded queues, UUID/name fallback, clear failure propagation, shutdown state transitions, documentation, CI, and package contents.
