package com.manujerozx.mimicvoice.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.audio.VoiceActivitySegmenter;
import com.manujerozx.mimicvoice.storage.ClipStore;
import com.manujerozx.mimicvoice.storage.VoiceClip;

/**
 * Component coverage for the real VAD/storage boundary and the deterministic
 * playback state machine. This deliberately does not start Paper, Mimic, or a
 * networked Simple Voice Chat server.
 */
class MimicVoicePipelineComponentTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTITY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path temporaryDirectory;

    @Test
    void syntheticSpeechBecomesStoredClipAndPlaybackRequest() throws Exception {
        PluginSettings settings = settings();
        VoiceActivitySegmenter segmenter = new VoiceActivitySegmenter(settings.recording().voiceActivity());
        for (int index = 0; index < 24; index++) {
            assertNull(segmenter.accept(sineFrame(8_000, 220.0 + index)));
        }
        short[] acceptedSpeech = segmenter.flush();

        assertNotNull(acceptedSpeech);
        assertTrue(acceptedSpeech.length >= 24 * PluginSettings.FRAME_SIZE);

        try (ClipStore store = new ClipStore(temporaryDirectory.resolve("recordings"),
                Logger.getAnonymousLogger(), () -> settings)) {
            assertTrue(store.saveOwned(PLAYER_ID, "SyntheticSpeaker", acceptedSpeech));
            store.awaitIdle().get();

            VoiceClip storedClip = store.select(PLAYER_ID, null);
            assertNotNull(storedClip);
            assertEquals(PLAYER_ID, storedClip.speakerId());

            ManualMainThread mainThread = new ManualMainThread();
            CapturingPlaybackSink playback = new CapturingPlaybackSink();
            MimicPlaybackController controller = new MimicPlaybackController(
                    () -> settings,
                    new MimicPlaybackController.ClipSource() {
                        @Override
                        public VoiceClip select(UUID playerId, String previousClipId) {
                            return store.select(playerId, previousClipId);
                        }

                        @Override
                        public CompletableFuture<short[]> read(VoiceClip clip) {
                            return store.read(clip);
                        }
                    },
                    playback,
                    mainThread,
                    () -> 0L,
                    (minimum, maximum) -> 0L);

            try {
                controller.tick(List.of(target()), true);
                mainThread.runAll();

                assertEquals(1, playback.started.size());
                assertEquals(PLAYER_ID, playback.started.get(0).target().playerId());
                assertEquals(storedClip.id(), playback.started.get(0).clip().id());
                assertEquals(acceptedSpeech.length, playback.started.get(0).samples().length);
            } finally {
                controller.close();
            }
        }
    }

    private static MimicPlaybackController.Target target() {
        MimicIdentityResolver.Resolution identity = new MimicIdentityResolver.Resolution(
                PLAYER_ID, PLAYER_ID.toString(), "SyntheticSpeaker", null, "SyntheticSpeaker",
                MimicIdentityResolver.Source.UUID_MARKER);
        return new MimicPlaybackController.Target(ENTITY_ID, true, true, false, 1, identity);
    }

    private static PluginSettings settings() {
        PluginSettings.VoiceActivity activity = new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0,
                0, 40, 20, 20, 0.25, 10, 1_000);
        return new PluginSettings(
                new PluginSettings.Recording(true, true, "", false, activity),
                new PluginSettings.Storage(false, 20, 72),
                new PluginSettings.Playback(true, 32.0F, 1.0, 0.01,
                        0, 0, 0, 1, 1, 5));
    }

    private static short[] sineFrame(int amplitude, double frequency) {
        short[] frame = new short[PluginSettings.FRAME_SIZE];
        for (int index = 0; index < frame.length; index++) {
            double angle = 2.0 * Math.PI * frequency * index / PluginSettings.SAMPLE_RATE;
            frame[index] = (short) Math.round(Math.sin(angle) * amplitude);
        }
        return frame;
    }

    private static final class ManualMainThread implements MimicPlaybackController.MainThreadExecutor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static final class CapturingPlaybackSink implements MimicPlaybackController.PlaybackSink {
        private final List<Started> started = new ArrayList<>();

        @Override
        public MimicPlaybackController.PlaybackHandle start(
                MimicPlaybackController.Target target, VoiceClip clip, short[] samples,
                double gain, Consumer<MimicPlaybackController.PlaybackHandle> stopped) {
            Started request = new Started(target, clip, samples, gain);
            started.add(request);
            return new MimicPlaybackController.PlaybackHandle() {
                @Override
                public void update(MimicPlaybackController.Target ignored) {
                    // The component only verifies the playback request boundary.
                }

                @Override
                public void stop() {
                    // The component only verifies the playback request boundary.
                }
            };
        }

        private record Started(MimicPlaybackController.Target target, VoiceClip clip,
                               short[] samples, double gain) {
        }
    }
}
