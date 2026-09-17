package com.jerara04.mimicvoice.audio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class WavIO {

    private static final int MAX_DATA_BYTES = 128 * 1024 * 1024;

    private WavIO() {
    }

    public static void write(Path path, short[] samples, int sampleRate) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        int dataBytes = Math.multiplyExact(samples.length, Short.BYTES);
        try {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                ascii(output, "RIFF");
                littleInt(output, 36 + dataBytes);
                ascii(output, "WAVE");
                ascii(output, "fmt ");
                littleInt(output, 16);
                littleShort(output, 1);
                littleShort(output, 1);
                littleInt(output, sampleRate);
                littleInt(output, sampleRate * Short.BYTES);
                littleShort(output, Short.BYTES);
                littleShort(output, 16);
                ascii(output, "data");
                littleInt(output, dataBytes);
                for (short sample : samples) {
                    littleShort(output, sample);
                }
            }

            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static WavData read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            if (!"RIFF".equals(ascii(input, 4))) {
                throw new IOException("Not a RIFF file: " + path);
            }
            littleInt(input);
            if (!"WAVE".equals(ascii(input, 4))) {
                throw new IOException("Not a WAVE file: " + path);
            }

            int sampleRate = -1;
            int channels = -1;
            int bitsPerSample = -1;
            while (true) {
                String chunk;
                try {
                    chunk = ascii(input, 4);
                } catch (EOFException eof) {
                    break;
                }
                int chunkSize = littleInt(input);
                if (chunkSize < 0 || chunkSize > MAX_DATA_BYTES) {
                    throw new IOException("Invalid WAV chunk size in " + path);
                }
                if ("fmt ".equals(chunk)) {
                    int format = littleUnsignedShort(input);
                    channels = littleUnsignedShort(input);
                    sampleRate = littleInt(input);
                    littleInt(input);
                    littleUnsignedShort(input);
                    bitsPerSample = littleUnsignedShort(input);
                    skipFully(input, chunkSize - 16);
                    if (format != 1) {
                        throw new IOException("Only PCM WAV is supported: " + path);
                    }
                } else if ("data".equals(chunk)) {
                    if (sampleRate <= 0 || channels != 1 || bitsPerSample != 16 || (chunkSize & 1) != 0) {
                        throw new IOException("WAV must be 16-bit mono PCM: " + path);
                    }
                    short[] samples = new short[chunkSize / Short.BYTES];
                    for (int index = 0; index < samples.length; index++) {
                        samples[index] = (short) littleUnsignedShort(input);
                    }
                    return new WavData(sampleRate, samples);
                } else {
                    skipFully(input, chunkSize);
                }
                if ((chunkSize & 1) != 0) {
                    skipFully(input, 1);
                }
            }
        }
        throw new IOException("WAV has no audio data: " + path);
    }

    public static WavInfo inspect(Path path) throws IOException {
        WavData data = read(path);
        return new WavInfo(data.sampleRate(), data.samples().length);
    }

    private static void skipFully(DataInputStream input, int bytes) throws IOException {
        if (bytes < 0) {
            throw new IOException("Invalid WAV chunk");
        }
        input.skipNBytes(bytes);
    }

    private static void ascii(DataOutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String ascii(DataInputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException();
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void littleInt(DataOutputStream output, int value) throws IOException {
        output.writeByte(value);
        output.writeByte(value >>> 8);
        output.writeByte(value >>> 16);
        output.writeByte(value >>> 24);
    }

    private static int littleInt(DataInputStream input) throws IOException {
        return input.readUnsignedByte()
                | input.readUnsignedByte() << 8
                | input.readUnsignedByte() << 16
                | input.readUnsignedByte() << 24;
    }

    private static void littleShort(DataOutputStream output, int value) throws IOException {
        output.writeByte(value);
        output.writeByte(value >>> 8);
    }

    private static int littleUnsignedShort(DataInputStream input) throws IOException {
        return input.readUnsignedByte() | input.readUnsignedByte() << 8;
    }

    public record WavData(int sampleRate, short[] samples) {
    }

    public record WavInfo(int sampleRate, int samples) {
    }
}
