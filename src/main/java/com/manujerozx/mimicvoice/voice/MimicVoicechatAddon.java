package com.manujerozx.mimicvoice.voice;

import java.util.logging.Logger;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

public final class MimicVoicechatAddon implements VoicechatPlugin, AutoCloseable {

    public static final String PLUGIN_ID = "mimic_voice";
    public static final String VOLUME_CATEGORY = "mimic_voice";

    private final VoiceRecordingManager recordingManager;
    private final Logger logger;
    private volatile VoicechatServerApi serverApi;
    private volatile boolean active = true;

    public MimicVoicechatAddon(VoiceRecordingManager recordingManager, Logger logger) {
        this.recordingManager = recordingManager;
        this.logger = logger;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (active && api instanceof VoicechatServerApi found) {
            serverApi = found;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!active || event.getSenderConnection() == null) {
            return;
        }
        ServerPlayer sender = event.getSenderConnection().getPlayer();
        if (sender == null) {
            return;
        }
        // Only use immutable Simple Voice Chat metadata on this packet thread.
        // Bukkit Player methods are sampled into PlayerSnapshotCache on the
        // Bukkit main thread by the integration.
        recordingManager.onMicrophonePacket(event.getVoicechat(), sender.getUuid(),
                event.getPacket().getOpusEncodedData(), event.getPacket().isWhispering());
    }

    private synchronized void onServerStarted(VoicechatServerStartedEvent event) {
        if (!active) {
            return;
        }
        VoicechatServerApi api = event.getVoicechat();
        serverApi = api;
        recordingManager.resume();
        api.registerVolumeCategory(api.volumeCategoryBuilder()
                .setId(VOLUME_CATEGORY)
                .setName("Mimic voices")
                .setDescription("Recorded voices replayed by Mimic creatures")
                .build());
        logger.info("Simple Voice Chat API connected; speech capture and Mimic playback are ready.");
    }

    private synchronized void onServerStopped(VoicechatServerStoppedEvent event) {
        if (!active) {
            return;
        }
        serverApi = null;
        recordingManager.pause();
    }

    public VoicechatServerApi serverApi() {
        return active ? serverApi : null;
    }

    @Override
    public synchronized void close() {
        if (!active) {
            return;
        }
        active = false;
        VoicechatServerApi api = serverApi;
        serverApi = null;
        if (api != null) {
            try {
                api.unregisterVolumeCategory(VOLUME_CATEGORY);
            } catch (RuntimeException exception) {
                logger.warning("Could not unregister the Mimic voice volume category: "
                        + exception.getMessage());
            }
        }
    }
}
