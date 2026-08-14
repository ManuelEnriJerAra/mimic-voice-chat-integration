package com.manujerozx.mimicvoice.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.storage.ConsentRegistry;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

class VoiceRecordingManagerTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temporaryDirectory;

    @Test
    void packetCallbackOnlyEnqueuesAndWorkerDoesDecodeAndVad() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.gatePayload(1);
        decoder.output = ignored -> speechFrame(8_000);
        try (Harness harness = new Harness(4, () -> decoder)) {
            AtomicReference<String> eventCaller = new AtomicReference<>();
            CountDownLatch callbackReturned = new CountDownLatch(1);
            Thread caller = Thread.ofPlatform().name("test-voice-event-caller").start(() -> {
                eventCaller.set(Thread.currentThread().getName());
                send(harness, 1);
                callbackReturned.countDown();
            });

            assertTrue(callbackReturned.await(1, TimeUnit.SECONDS),
                    "the packet callback must not wait for decoding");
            assertTrue(decoder.decodeEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            decoder.releaseGate();
            caller.join(TEST_TIMEOUT.toMillis());

            awaitIdle(harness);
            harness.manager.finish(harness.playerId);
            awaitIdle(harness);

            assertNotEquals(eventCaller.get(), decoder.decodeThread,
                    "Opus decoding must run on the capture worker");
            assertEquals(1, harness.saved.size());
            assertNotEquals(eventCaller.get(), harness.saved.get(0).threadName(),
                    "VAD/storage submission must run on the capture worker");
        }
    }

    @Test
    void packetsForOnePlayerAreFifoAndDecoderCallsAreNeverConcurrent() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        try (Harness harness = new Harness(512, () -> decoder)) {
            for (int batch = 0; batch < 20; batch++) {
                for (int packet = 0; packet < 100; packet++) {
                    send(harness, batch * 100 + packet);
                }
                awaitIdle(harness);
            }

            assertEquals(2_000, decoder.payloads.size());
            for (int index = 0; index < decoder.payloads.size(); index++) {
                assertEquals(index & 0xff, decoder.payloads.get(index));
            }
            assertEquals(1, decoder.maximumConcurrentCalls.get());
            assertEquals(2_000, harness.manager.receivedPackets());
            assertEquals(2_000, harness.manager.processedPackets());
            assertEquals(0, harness.manager.overloadDroppedPackets());
            assertTrue(harness.manager.queueDepth() <= harness.manager.queueCapacity());
        }
    }

    @Test
    void queueOverflowIsNonBlockingAndInvalidatesTheDecoderSession() throws Exception {
        FakeDecoder first = new FakeDecoder();
        first.gatePayload(1);
        first.output = ignored -> speechFrame(8_000);
        AtomicInteger decoderNumber = new AtomicInteger();
        try (Harness harness = new Harness(2, () -> {
            if (decoderNumber.getAndIncrement() == 0) {
                return first;
            }
            FakeDecoder replacement = new FakeDecoder();
            replacement.output = ignored -> speechFrame(12_000);
            return replacement;
        })) {
            send(harness, 1);
            assertTrue(first.decodeEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            send(harness, 2);
            send(harness, 3);

            long start = System.nanoTime();
            send(harness, 4);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < 500, "overflow handling must not block the packet caller");
            assertEquals(1, harness.manager.overloadDroppedPackets());
            assertEquals(2, harness.manager.queueDepth());

            first.releaseGate();
            awaitIdle(harness);
            send(harness, 9);
            awaitIdle(harness);
            harness.manager.finish(harness.playerId);
            awaitIdle(harness);

            List<Integer> decoded = harness.decodedPayloads();
            assertTrue(decoded.contains(1));
            assertTrue(decoded.contains(9));
            assertFalse(decoded.contains(2));
            assertFalse(decoded.contains(3));
            assertFalse(decoded.contains(4));
            assertTrue(first.closeCount.get() > 0, "overflow must close the old decoder session");
            assertEquals(1, harness.saved.size(), "the new clip must not span the dropped gap");
            assertEquals(PluginSettings.FRAME_SIZE, harness.saved.get(0).samples().length);
        }
    }

    @Test
    void consentDenyInvalidatesAlreadyQueuedPackets() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.gatePayload(1);
        decoder.output = ignored -> speechFrame(8_000);
        try (Harness harness = new Harness(4, () -> decoder)) {
            send(harness, 1);
            assertTrue(decoder.decodeEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            send(harness, 2);

            assertTrue(harness.consent.setAllowed(harness.playerId, false).get());
            harness.manager.discard(harness.playerId);
            decoder.releaseGate();
            awaitIdle(harness);

            assertEquals(List.of(1), decoder.payloads,
                    "the packet queued before consent denial must not be decoded later");
            assertTrue(harness.saved.isEmpty());
            assertTrue(decoder.closeCount.get() > 0);
        }
    }

    @Test
    void finishFlushesProcessedCaptureBeforeDroppingQueuedPackets() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.gatePayload(2);
        decoder.output = ignored -> speechFrame(8_000);
        try (Harness harness = new Harness(4, () -> decoder)) {
            send(harness, 1);
            awaitIdle(harness);
            send(harness, 2);
            assertTrue(decoder.decodeEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            send(harness, 3);

            harness.manager.finish(harness.playerId);
            decoder.releaseGate();
            awaitIdle(harness);

            assertEquals(List.of(1, 2), decoder.payloads);
            assertEquals(1, harness.saved.size());
            assertEquals(PluginSettings.FRAME_SIZE, harness.saved.get(0).samples().length);
        }
    }

    @Test
    void quitBarrierDropsLatePacketsUntilPlayerResumes() throws Exception {
        FakeDecoder first = new FakeDecoder();
        first.gatePayload(2);
        first.output = ignored -> speechFrame(8_000);
        AtomicInteger decoderNumber = new AtomicInteger();
        try (Harness harness = new Harness(4, () -> {
            FakeDecoder decoder = decoderNumber.getAndIncrement() == 0 ? first : new FakeDecoder();
            decoder.output = ignored -> speechFrame(8_000);
            return decoder;
        })) {
            send(harness, 1);
            awaitIdle(harness);
            send(harness, 2);
            assertTrue(first.decodeEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            send(harness, 3);

            harness.manager.endPlayer(harness.playerId);
            first.releaseGate();
            awaitIdle(harness);

            assertEquals(List.of(1, 2), first.payloads,
                    "a packet queued before quit must not be decoded after the quit barrier");
            assertEquals(1, harness.saved.size(), "processed audio may be flushed at quit");

            harness.manager.resumePlayer(harness.playerId);
            send(harness, 4);
            awaitIdle(harness);
            assertEquals(2, decoderNumber.get());
            assertEquals(List.of(4), harness.decoders.get(1).payloads);
        }
    }

    @Test
    void reloadInvalidatesQueuedWorkAndFreshPacketsCreateAFreshSession() throws Exception {
        FakeDecoder first = new FakeDecoder();
        first.gatePayload(1);
        first.output = ignored -> speechFrame(8_000);
        AtomicInteger decoderNumber = new AtomicInteger();
        List<FakeDecoder> created = new CopyOnWriteArrayList<>();
        try (Harness harness = new Harness(4, () -> {
            FakeDecoder decoder = decoderNumber.getAndIncrement() == 0 ? first : new FakeDecoder();
            decoder.output = ignored -> speechFrame(8_000);
            created.add(decoder);
            return decoder;
        })) {
            send(harness, 1);
            assertTrue(first.decodeEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            send(harness, 2);

            harness.manager.reload();
            first.releaseGate();
            awaitIdle(harness);

            send(harness, 3);
            awaitIdle(harness);
            assertEquals(2, created.size());
            assertEquals(List.of(1), created.get(0).payloads);
            assertEquals(List.of(3), created.get(1).payloads);
            assertTrue(first.closeCount.get() > 0);
        }
    }

    @Test
    void normalCaptureProducesOneAcceptedSegmentInPacketOrder() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.output = ignored -> speechFrame(8_000);
        try (Harness harness = new Harness(8, () -> decoder)) {
            send(harness, 10);
            send(harness, 11);
            send(harness, 12);
            awaitIdle(harness);
            harness.manager.finish(harness.playerId);
            awaitIdle(harness);

            assertEquals(List.of(10, 11, 12), decoder.payloads);
            assertEquals(1, harness.saved.size());
            assertEquals(3 * PluginSettings.FRAME_SIZE, harness.saved.get(0).samples().length);
            assertEquals(1, harness.manager.acceptedSegments());
        }
    }

    @Test
    void acceptedSpeechAndStorageSubmissionFailureAreReportedSeparately() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.output = ignored -> speechFrame(8_000);
        try (Harness harness = new Harness(8, () -> decoder, false)) {
            send(harness, 1);
            send(harness, 2);
            awaitIdle(harness);
            harness.manager.finish(harness.playerId);
            awaitIdle(harness);

            assertEquals(1, harness.manager.acceptedSegments());
            assertEquals(1, harness.manager.clipSubmissionFailures());
        }
    }

    @Test
    void generationCheckAndClipAdmissionAreAtomicAgainstDiscard() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.output = ignored -> speechFrame(8_000);
        CountDownLatch saveEntered = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        AtomicInteger saveCount = new AtomicInteger();
        VoiceRecordingManager.ClipSaver blockingSaver = (id, name, samples) -> {
            saveCount.incrementAndGet();
            saveEntered.countDown();
            try {
                releaseSave.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        };
        try (Harness harness = new Harness(8, () -> decoder, blockingSaver)) {
            send(harness, 1);
            awaitIdle(harness);
            harness.manager.finish(harness.playerId);
            assertTrue(saveEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            CountDownLatch discardStarted = new CountDownLatch(1);
            CountDownLatch discardCompleted = new CountDownLatch(1);
            Thread discard = Thread.ofPlatform().start(() -> {
                discardStarted.countDown();
                harness.manager.discard(harness.playerId);
                discardCompleted.countDown();
            });
            assertTrue(discardStarted.await(1, TimeUnit.SECONDS));
            assertFalse(discardCompleted.await(200, TimeUnit.MILLISECONDS),
                    "discard must wait for an already-admitted clip save to finish");

            releaseSave.countDown();
            assertTrue(discardCompleted.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            discard.join(TEST_TIMEOUT.toMillis());
            awaitIdle(harness);
            assertEquals(1, saveCount.get());
        }
    }

    @Test
    void closeIsIdempotentAndStopsTheCaptureWorker() throws Exception {
        Harness harness = new Harness(4, FakeDecoder::new);
        assertTrue(harness.manager.workerAlive());
        harness.manager.close();
        harness.manager.close();
        assertFalse(harness.manager.workerAlive());
        assertEquals(0, harness.manager.activeSessions());
    }

    @Test
    void fatalWorkerFailureDropsQueuedPacketsBeforeTerminating() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.gatePayload(1);
        decoder.output = ignored -> {
            throw new AssertionError("simulated fatal decoder failure");
        };
        try (Harness harness = new Harness(4, () -> decoder)) {
            send(harness, 1);
            assertTrue(decoder.decodeEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            send(harness, 2);
            decoder.releaseGate();

            long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
            while (harness.manager.workerAlive() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(harness.manager.workerAlive());
            assertEquals(0, harness.manager.queueDepth());
        }
    }

    @Test
    void manyBatchedPacketsDoNotGrowTheQueueOrViolateOrdering() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        try (Harness harness = new Harness(64, () -> decoder)) {
            for (int batch = 0; batch < 40; batch++) {
                for (int packet = 0; packet < 50; packet++) {
                    send(harness, batch * 50 + packet);
                }
                awaitIdle(harness);
                assertTrue(harness.manager.queueDepth() <= 64);
            }

            assertEquals(2_000, decoder.payloads.size());
            assertEquals(0, harness.manager.overloadDroppedPackets());
            for (int index = 0; index < decoder.payloads.size(); index++) {
                assertEquals(index & 0xff, decoder.payloads.get(index));
            }
        }
    }

    private static void send(Harness harness, int payload) {
        harness.manager.onMicrophonePacket(harness.api, harness.playerId,
                new byte[] {(byte) payload}, false);
    }

    private static void awaitIdle(Harness harness) throws InterruptedException {
        assertTrue(harness.manager.awaitIdle(TEST_TIMEOUT), "capture worker did not reach its FIFO barrier");
    }

    private static short[] speechFrame(int amplitude) {
        short[] frame = new short[PluginSettings.FRAME_SIZE];
        for (int index = 0; index < frame.length; index++) {
            double angle = 2.0 * Math.PI * 220.0 * index / PluginSettings.SAMPLE_RATE;
            frame[index] = (short) Math.round(Math.sin(angle) * amplitude);
        }
        return frame;
    }

    private static PluginSettings testSettings() {
        PluginSettings.VoiceActivity activity = new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0,
                0, 40, 20, 20, 0.25, 10, 1_000);
        return new PluginSettings(
                new PluginSettings.Recording(true, true, "", false, activity),
                new PluginSettings.Storage(false, 20, 72),
                new PluginSettings.Playback(true, 32.0F, 0.9, 0.01,
                        0, 5, 20, 5, 20, 10));
    }

    private static VoicechatApi fakeApi() {
        return (VoicechatApi) Proxy.newProxyInstance(
                VoicechatApi.class.getClassLoader(), new Class<?>[] {VoicechatApi.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }

    private record SavedClip(UUID playerId, String playerName, short[] samples, String threadName) {
    }

    private static final class Harness implements AutoCloseable {
        private final UUID playerId = UUID.randomUUID();
        private final VoicechatApi api = fakeApi();
        private final ConsentRegistry consent;
        private final PlayerSnapshotCache playerSnapshots = new PlayerSnapshotCache();
        private final List<SavedClip> saved = new CopyOnWriteArrayList<>();
        private final List<FakeDecoder> decoders = new CopyOnWriteArrayList<>();
        private volatile PluginSettings settings = testSettings();
        private final VoiceRecordingManager manager;

        private Harness(int queueCapacity, Supplier<FakeDecoder> decoderSupplier) {
            this(queueCapacity, decoderSupplier, true);
        }

        private Harness(int queueCapacity, Supplier<FakeDecoder> decoderSupplier,
                        boolean acceptClipSubmissions) {
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.OFF);
            consent = new ConsentRegistry(Path.of(System.getProperty("java.io.tmpdir"),
                    "mimic-voice-test-" + UUID.randomUUID()).toFile(), logger);
            playerSnapshots.put(playerId, "TestPlayer", true);
            manager = new VoiceRecordingManager(logger, () -> settings,
                    (id, name, samples) -> {
                        saved.add(new SavedClip(id, name, samples, Thread.currentThread().getName()));
                        return acceptClipSubmissions;
                    },
                    ignored -> {
                        FakeDecoder decoder = decoderSupplier.get();
                        decoders.add(decoder);
                        return decoder.asDecoder();
                    }, consent, ignored -> true, playerSnapshots, queueCapacity);
        }

        private Harness(int queueCapacity, Supplier<FakeDecoder> decoderSupplier,
                        VoiceRecordingManager.ClipSaver clipSaver) {
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.OFF);
            consent = new ConsentRegistry(Path.of(System.getProperty("java.io.tmpdir"),
                    "mimic-voice-test-" + UUID.randomUUID()).toFile(), logger);
            playerSnapshots.put(playerId, "TestPlayer", true);
            manager = new VoiceRecordingManager(logger, () -> settings, clipSaver,
                    ignored -> {
                        FakeDecoder decoder = decoderSupplier.get();
                        decoders.add(decoder);
                        return decoder.asDecoder();
                    }, consent, ignored -> true, playerSnapshots, queueCapacity);
        }

        private List<Integer> decodedPayloads() {
            List<Integer> payloads = new ArrayList<>();
            decoders.forEach(decoder -> payloads.addAll(decoder.payloads));
            return payloads;
        }

        @Override
        public void close() {
            decoders.forEach(FakeDecoder::releaseGate);
            manager.close();
            consent.close();
        }
    }

    private static final class FakeDecoder implements InvocationHandler {
        private final List<Integer> payloads = new CopyOnWriteArrayList<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch decodeEntered = new CountDownLatch(1);
        private volatile CountDownLatch releaseGate = new CountDownLatch(0);
        private volatile Integer gatedPayload;
        private volatile IntFunction<short[]> output = ignored -> new short[0];
        private volatile String decodeThread;

        private OpusDecoder asDecoder() {
            return (OpusDecoder) Proxy.newProxyInstance(
                    OpusDecoder.class.getClassLoader(), new Class<?>[] {OpusDecoder.class}, this);
        }

        private void gatePayload(int payload) {
            gatedPayload = payload & 0xff;
            releaseGate = new CountDownLatch(1);
        }

        private void releaseGate() {
            releaseGate.countDown();
        }

        private Object invokeDecode(byte[] encoded) {
            int payload = encoded == null || encoded.length == 0 ? -1 : encoded[0] & 0xff;
            payloads.add(payload);
            decodeThread = Thread.currentThread().getName();
            int concurrent = inFlight.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(concurrent, Math::max);
            try {
                if (gatedPayload != null && gatedPayload == payload) {
                    decodeEntered.countDown();
                    releaseGate.await();
                }
                return output.apply(payload);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fake decoder interrupted", exception);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("decode") && method.getParameterCount() == 1) {
                return invokeDecode((byte[]) arguments[0]);
            }
            if (method.getName().equals("decode")) {
                return new short[0][];
            }
            if (method.getName().equals("close")) {
                closeCount.incrementAndGet();
                return null;
            }
            if (method.getName().equals("resetState")) {
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }
}
