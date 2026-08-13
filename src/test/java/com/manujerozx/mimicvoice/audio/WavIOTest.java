package com.manujerozx.mimicvoice.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.manujerozx.mimicvoice.PluginSettings;

class WavIOTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAndReadsMonoPcm() throws Exception {
        short[] input = new short[2_013];
        for (int index = 0; index < input.length; index++) {
            input[index] = (short) (index * 31 - 16_000);
        }
        Path path = temporaryDirectory.resolve("clip.wav");

        WavIO.write(path, input, PluginSettings.SAMPLE_RATE);
        WavIO.WavData output = WavIO.read(path);

        assertEquals(PluginSettings.SAMPLE_RATE, output.sampleRate());
        assertArrayEquals(input, output.samples());
        assertEquals(input.length, WavIO.inspect(path).samples());
        try (var files = java.nio.file.Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().contains(".tmp-")));
        }
    }
}
