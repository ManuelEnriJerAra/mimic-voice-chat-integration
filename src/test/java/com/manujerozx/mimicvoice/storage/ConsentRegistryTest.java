package com.manujerozx.mimicvoice.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
        ConsentRegistry registry = new ConsentRegistry(temporaryDirectory.toFile(),
                Logger.getAnonymousLogger());

        assertTrue(registry.setAllowed(playerId, false));
        assertFalse(new ConsentRegistry(temporaryDirectory.toFile(), Logger.getAnonymousLogger())
                .mayRecord(playerId));
        assertTrue(registry.setAllowed(playerId, true));
        assertTrue(new ConsentRegistry(temporaryDirectory.toFile(), Logger.getAnonymousLogger())
                .mayRecord(playerId));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void failedPersistenceIsReportedButOptOutStillAppliesToCurrentSession() throws Exception {
        Path invalidDataFolder = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(invalidDataFolder, "blocking file");
        UUID playerId = UUID.randomUUID();
        Logger quietLogger = Logger.getAnonymousLogger();
        quietLogger.setLevel(Level.OFF);
        ConsentRegistry registry = new ConsentRegistry(invalidDataFolder.toFile(), quietLogger);

        assertFalse(registry.setAllowed(playerId, false));
        assertFalse(registry.mayRecord(playerId));
    }
}
