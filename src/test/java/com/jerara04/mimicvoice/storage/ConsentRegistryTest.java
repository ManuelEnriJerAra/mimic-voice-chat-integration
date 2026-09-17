package com.jerara04.mimicvoice.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsentRegistryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void consentChangesSurviveReloadWithoutTemporaryFiles() throws Exception {
        UUID playerId = UUID.randomUUID();
        try (ConsentRegistry registry = new ConsentRegistry(temporaryDirectory.toFile(),
                Logger.getAnonymousLogger())) {
            assertTrue(registry.initialize().get());
            assertTrue(registry.setAllowed(playerId, false).get());
            try (ConsentRegistry reloaded = new ConsentRegistry(temporaryDirectory.toFile(),
                    Logger.getAnonymousLogger())) {
                assertTrue(reloaded.initialize().get());
                assertFalse(reloaded.mayRecord(playerId));
            }
            assertTrue(registry.setAllowed(playerId, true).get());
            try (ConsentRegistry reloaded = new ConsentRegistry(temporaryDirectory.toFile(),
                    Logger.getAnonymousLogger())) {
                assertTrue(reloaded.initialize().get());
                assertTrue(reloaded.mayRecord(playerId));
            }
            try (var files = Files.list(temporaryDirectory)) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().contains(".tmp-")));
            }
        }
    }

    @Test
    void failedPersistenceIsReportedButOptOutStillAppliesToCurrentSession() throws Exception {
        Path invalidDataFolder = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(invalidDataFolder, "blocking file");
        UUID playerId = UUID.randomUUID();
        Logger quietLogger = Logger.getAnonymousLogger();
        quietLogger.setLevel(Level.OFF);
        try (ConsentRegistry registry = new ConsentRegistry(invalidDataFolder.toFile(), quietLogger)) {
            assertTrue(registry.initialize().get());
            assertFalse(registry.setAllowed(playerId, false).get());
            assertFalse(registry.mayRecord(playerId));
        }
    }

    @Test
    void consentChangeDoesNotWaitForBlockingPersistence() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch saveEntered = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        ConsentRegistry.ConsentPersistence persistence = new ConsentRegistry.ConsentPersistence() {
            @Override
            public Set<UUID> load() {
                return Set.of();
            }

            @Override
            public boolean save(Set<UUID> snapshot) {
                saveEntered.countDown();
                try {
                    releaseSave.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;
            }
        };
        try (ConsentRegistry registry = new ConsentRegistry(
                temporaryDirectory.resolve("async-consent.yml").toFile(),
                Logger.getAnonymousLogger(), persistence)) {
            assertTrue(registry.initialize().get());
            long start = System.nanoTime();
            var persisted = registry.setAllowed(playerId, false);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMillis < 500, "consent command admission must not perform persistence I/O");
            assertFalse(registry.mayRecord(playerId));
            assertTrue(saveEntered.await(5, TimeUnit.SECONDS));
            releaseSave.countDown();
            assertTrue(persisted.get());
        }
    }

    @Test
    void blockedPersistenceRetainsOnlyBoundedWaitersAndClearsLoadOverrides() throws Exception {
        UUID firstPlayer = UUID.randomUUID();
        CountDownLatch saveEntered = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        ConsentRegistry.ConsentPersistence persistence = new ConsentRegistry.ConsentPersistence() {
            @Override
            public Set<UUID> load() {
                return Set.of();
            }

            @Override
            public boolean save(Set<UUID> snapshot) {
                saveEntered.countDown();
                try {
                    releaseSave.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;
            }
        };

        try (ConsentRegistry registry = new ConsentRegistry(
                temporaryDirectory.resolve("bounded-consent.yml").toFile(),
                Logger.getAnonymousLogger(), persistence)) {
            assertTrue(registry.initialize().get());
            var firstResult = registry.setAllowed(firstPlayer, false);
            assertTrue(saveEntered.await(5, TimeUnit.SECONDS));

            List<java.util.concurrent.CompletableFuture<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 10_000; index++) {
                results.add(registry.setAllowed(UUID.randomUUID(), false));
            }

            long immediatelyFailed = results.stream()
                    .filter(java.util.concurrent.CompletableFuture::isDone)
                    .filter(result -> !result.getNow(true))
                    .count();
            assertTrue(immediatelyFailed > 0, "excess durability waiters must fail visibly");
            assertTrue(registry.pendingResultCount() <= ConsentRegistry.MAX_PENDING_RESULTS);
            assertEquals(0, registry.pendingInitialOverrideCount(),
                    "one-time load overrides must not become a lifetime UUID map");
            assertFalse(registry.mayRecord(firstPlayer));

            releaseSave.countDown();
            assertTrue(firstResult.get());
        }
    }
}
