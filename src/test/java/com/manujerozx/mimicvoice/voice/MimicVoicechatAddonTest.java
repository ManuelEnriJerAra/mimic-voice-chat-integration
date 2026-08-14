package com.manujerozx.mimicvoice.voice;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.storage.ConsentRegistry;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

class MimicVoicechatAddonTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void closeMakesAddonInertAndUnregistersVolumeCategory() {
        AtomicBoolean categoryUnregistered = new AtomicBoolean();
        VoicechatServerApi api = (VoicechatServerApi) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {VoicechatServerApi.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("unregisterVolumeCategory")) {
                        categoryUnregistered.set(true);
                    }
                    return null;
                });
        MimicVoicechatAddon addon = new MimicVoicechatAddon(null, Logger.getAnonymousLogger());

        addon.initialize(api);
        assertSame(api, addon.serverApi());
        addon.close();
        addon.close();

        assertNull(addon.serverApi());
        assertTrue(categoryUnregistered.get());
    }

    @Test
    void microphoneCallbackUsesVoicechatUuidAndDoesNotTouchBukkitPlayer() throws Exception {
        UUID playerId = UUID.randomUUID();
        Logger logger = Logger.getAnonymousLogger();
        ConsentRegistry consent = new ConsentRegistry(temporaryDirectory.toFile(), logger);
        PlayerSnapshotCache snapshots = new PlayerSnapshotCache();
        snapshots.put(playerId, "SnapshotName", true);
        VoiceRecordingManager manager = new VoiceRecordingManager(logger, MimicVoicechatAddonTest::settings,
                (id, name, samples) -> true,
                ignored -> (OpusDecoder) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {OpusDecoder.class},
                        (proxy, method, arguments) -> defaultValue(method.getReturnType())),
                consent, ignored -> true, snapshots, 2);
        MimicVoicechatAddon addon = new MimicVoicechatAddon(manager, logger);
        AtomicReference<java.util.function.Consumer<MicrophonePacketEvent>> handler = new AtomicReference<>();
        EventRegistration registration = (EventRegistration) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {EventRegistration.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("registerEvent") && arguments.length >= 2
                            && arguments[1] instanceof java.util.function.Consumer<?> consumer
                            && arguments[0] == MicrophonePacketEvent.class) {
                        @SuppressWarnings("unchecked")
                        java.util.function.Consumer<MicrophonePacketEvent> typed =
                                (java.util.function.Consumer<MicrophonePacketEvent>) consumer;
                        handler.set(typed);
                    }
                    return null;
                });
        addon.registerEvents(registration);

        ServerPlayer sender = (ServerPlayer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ServerPlayer.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUuid")) {
                        return playerId;
                    }
                    if (method.getName().equals("getPlayer")) {
                        throw new AssertionError("Bukkit Player access is forbidden on the SVC callback thread");
                    }
                    return defaultValue(method.getReturnType());
                });
        VoicechatConnection connection = (VoicechatConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {VoicechatConnection.class},
                (proxy, method, arguments) -> method.getName().equals("getPlayer")
                        ? sender : defaultValue(method.getReturnType()));
        MicrophonePacket packet = (MicrophonePacket) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {MicrophonePacket.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getOpusEncodedData" -> new byte[] {1};
                    case "isWhispering" -> false;
                    default -> defaultValue(method.getReturnType());
                });
        VoicechatServerApi api = (VoicechatServerApi) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {VoicechatServerApi.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
        MicrophonePacketEvent event = (MicrophonePacketEvent) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {MicrophonePacketEvent.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getSenderConnection" -> connection;
                    case "getPacket" -> packet;
                    case "getVoicechat" -> api;
                    default -> defaultValue(method.getReturnType());
                });

        try {
            handler.get().accept(event);
            assertEquals(1L, manager.receivedPackets());
        } finally {
            manager.close();
            consent.close();
        }
    }

    private static PluginSettings settings() {
        PluginSettings.VoiceActivity activity = new PluginSettings.VoiceActivity(
                -42.0, -32.0, 10.0, 5.0, 0, 40, 20, 20, 0.25, 10, 1_000);
        return new PluginSettings(
                new PluginSettings.Recording(true, true, "", false, activity),
                new PluginSettings.Storage(false, 20, 72),
                new PluginSettings.Playback(true, 32.0F, 1.0, 0.01, 0, 5, 20, 5, 20, 10));
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
}
