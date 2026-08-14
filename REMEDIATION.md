# Independent review remediation

This file records the reproduction and disposition of the findings in the
independent review. The review artifacts remain outside this repository and were
not modified.

## Critical findings

No Critical findings were reported by the independent review.

## High and Medium findings

| ID | Reproduction | Fix | Regression test | Status |
| --- | --- | --- | --- | --- |
| VOICE-001 | A blocking `ClipSaver` was paused after the old generation check. A concurrent `discard` returned before the saver was released, proving the old check/save window was not atomic. | `VoiceRecordingManager.publishSegment` now performs the generation check and bounded save admission under the per-player state lock. `ClipStore` only accepts/clones/enqueues there; filesystem storage remains asynchronous and outside the lock. Controls cannot pass the generation boundary while admission is in progress. | `VoiceRecordingManagerTest.generationCheckAndClipAdmissionAreAtomicAgainstDiscard` | Fixed |
| THREAD-001 | The old constructor called `YamlConfiguration.loadConfiguration` synchronously and consent commands synchronously performed directory creation, write, move, and cleanup. A blocking injected persistence adapter reproduced the command-path wait. | Consent loading and persistence now run on a dedicated daemon worker. In-memory UUID policy changes are immediate; immutable snapshots are coalesced, and returned futures report durability failures. Capture remains paused until initial consent loading succeeds. Failure warnings are dispatched back to Bukkit. | `ConsentRegistryTest.consentChangeDoesNotWaitForBlockingPersistence`, plus file round-trip/failure tests | Fixed |
| THREAD-002 | The old SVC callback obtained a Bukkit `Player` and queried `getUniqueId`, `hasPermission`, and `getName` on the SVC packet thread. | The addon now uses only SVC sender UUID metadata. Bukkit name/permission values are sampled on the Bukkit main thread into `PlayerSnapshotCache`; packet admission performs only a concurrent cache lookup. | `MimicVoicechatAddonTest.microphoneCallbackUsesVoicechatUuidAndDoesNotTouchBukkitPlayer`; recording callback tests use UUID/snapshot input | Fixed |
| STORAGE-001 | A controller read was completed, clear was logically executed before the queued main-thread completion, and the old controller still started playback. An already active handle was also not stopped. | `clearPlayer`/`clearAll` invalidate the matching/global controller identity versions, clear last-clip assumptions, schedule retry, and stop active playback before storage deletion is queued. | `MimicPlaybackControllerTest.clearPlayerInvalidatesInFlightReadAndStopsActivePlayback` | Fixed |
| QUEUE-001 | The old scheduled executor accepted unbounded read/control work. A blocked reader plus more requests than the bound showed no admission rejection. | Immediate storage work uses a bounded executor queue. Disk reads use a separate bounded worker with a 64-read admission bound, retry-visible rejection metric, and no unbounded `CompletableFuture` executor backlog. Clear/control work is separated from bulk reads. | `ClipStoreTest.diskPlaybackReadsHaveASeparateBoundedAdmissionQueue` | Fixed |
| LIFE-001 | Reproduced the non-cooperative decoder case: a decoder that ignores `close()` and interruption remains live beyond the bounded shutdown wait. | Shutdown now clears queued work, marks the manager closed before cancellation, prevents post-close segment publication, attempts decoder close/interruption, and returns/logs a fail-closed non-terminal result when the worker is still alive. | `VoiceRecordingManagerTest.nonCooperativeDecoderShutdownFailsClosedAndBlocksPostShutdownSaves`; `VoiceRecordingManagerTest.fatalWorkerFailureDropsQueuedPacketsBeforeTerminating` | Remediated with an explicit non-terminal result; Java cannot force-kill a third-party decoder safely, so a decoder that ignores both cancellation mechanisms remains an operational limitation |
| LIFE-002 | Reproduced that `ClipStore.shutdown()` could return after its first wait while a non-cooperative read/save task was still running, and that post-close work could mutate storage/index state. | Storage shutdown now has a bounded follow-up wait, reports whether all workers terminated, and all save/read/index mutation paths check the closed boundary. A non-terminal result is logged visibly and the integration reports it on disable. | `ClipStoreTest.storageShutdownReportsNonCooperativeReaderAndBlocksPostCloseMutation`; `ClipStoreTest.storageShutdownDoesNotRegisterAPendingSaveAfterClose` | Fixed with fail-closed status; non-cooperative external file I/O can still keep a daemon worker alive until it exits |
| CONSENT-001 | Reproduced unbounded blocked-persistence growth: 10,000 blocked result futures and 10,001 initial-load overrides accumulated before the fix. | Pending consent result futures and pre-load overrides are bounded at 256 each. Overflow fails closed (`false`), in-memory denial remains immediate, and persistence/load failure remains visible to callers. | `ConsentRegistryTest.blockedPersistenceRetainsOnlyBoundedWaitersAndClearsLoadOverrides` | Fixed |
| CLEAR-001 | Reproduced a full storage queue rejecting `clearAll()` before the queued save work was released, leaving deletion unconfirmed and old clips selectable. | Bulk storage admissions leave a reserved control slot for clear operations. Clear-all/per-player selection is suppressed while deletion is pending, and asynchronous command completion now reports failure explicitly instead of silently claiming success. | `ClipStoreTest.clearControlAdmissionRemainsAvailableWhenBulkStorageIsSaturated`; integration clear completion path reviewed | Fixed |
| MEM-001 | Reproduced the absence of an aggregate playback PCM bound: multiple Mimics could each hand a full clip to SVC concurrently without a global reservation. | Playback reserves PCM bytes on the Bukkit-controlled state boundary before starting SVC playback, releases them on every stop/failure/completion path, and retries rejected reads. The cap is configurable and exposed in status. | `MimicPlaybackControllerTest.activePlaybackPcmBudgetRejectsExcessAndReleasesOnStop` | Fixed |
| PLAY-001 | A throwing playback handle stop aborted the old cleanup path; failed audio-player start left the local player without cleanup. | Controller cleanup now nulls state then catches/reports each handle failure and continues. The manager wraps player callback/start setup and calls `stopPlaying()` on a partially created player when setup/start fails, rethrowing the original failure for controller retry. | `MimicPlaybackControllerTest.playbackStopFailureDoesNotAbortStateCleanup`; source path audited for failed-start cleanup | Fixed |

## Low findings and deferred scope

| ID | Disposition |
| --- | --- |
| STATE-001 | Reproduced: UUID tombstones remain in `VoiceRecordingManager.states`. This was not part of the requested Critical/High/Medium remediation scope. It remains a low-severity bounded-state follow-up because expiration must preserve the quit/late-packet barrier. |
| PERF-001 | Reproduced by inspection: listener counting is `O(E + M*P)` per playback scan. No behavior-changing spatial-index refactor was included in this release-candidate remediation. |
| REL-001 | Reproduced: the repository has no `RELEASE_NOTES_V1.0.1.md` and remains version `1.0.0`. Release provenance is intentionally deferred because this task must not release or claim certification. |

## Validation notes

The final validation record, candidate JAR digest, branch, HEAD, and working-tree
status are reported in the remediation handoff after the clean verify/package
run. A fresh independent audit is still required; this file does not certify a
release.
