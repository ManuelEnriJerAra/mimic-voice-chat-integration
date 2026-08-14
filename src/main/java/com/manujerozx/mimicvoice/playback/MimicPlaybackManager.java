package com.manujerozx.mimicvoice.playback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.manujerozx.mimicvoice.MimicSimpleVoiceChatIntegration;
import com.manujerozx.mimicvoice.PluginSettings;
import com.manujerozx.mimicvoice.storage.ClipStore;
import com.manujerozx.mimicvoice.storage.VoiceClip;
import com.manujerozx.mimicvoice.voice.MimicVoicechatAddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;

public final class MimicPlaybackManager implements AutoCloseable {

    private static final NamespacedKey MIMIC_MARKER = new NamespacedKey("mimic", "mimic");
    private static final NamespacedKey MIMICKED_PLAYER_UUID = new NamespacedKey(
            "mimic", MimicIdentityResolver.UUID_MARKER_KEY);
    private static final NamespacedKey MIMICKED_PLAYER = new NamespacedKey(
            "mimic", MimicIdentityResolver.LEGACY_NAME_KEY);

    private final MimicSimpleVoiceChatIntegration plugin;
    private final ClipStore clipStore;
    private final Supplier<PluginSettings> settings;
    private final Supplier<VoicechatServerApi> apiSupplier;
    private final MimicIdentityResolver identityResolver = new MimicIdentityResolver();
    private final Map<UUID, Vindicator> currentMimics = new HashMap<>();
    private final Map<UUID, String> lastMalformedUuidMarkers = new HashMap<>();
    private final MimicPlaybackController controller;
    private BukkitTask task;

    public MimicPlaybackManager(MimicSimpleVoiceChatIntegration plugin, ClipStore clipStore,
                                Supplier<PluginSettings> settings,
                                Supplier<VoicechatServerApi> apiSupplier) {
        this.plugin = plugin;
        this.clipStore = clipStore;
        this.settings = settings;
        this.apiSupplier = apiSupplier;
        this.controller = new MimicPlaybackController(
                settings,
                new MimicPlaybackController.ClipSource() {
                    @Override
                    public VoiceClip select(UUID playerId, String previousClipId) {
                        return clipStore.select(playerId, previousClipId);
                    }

                    @Override
                    public CompletableFuture<short[]> read(VoiceClip clip) {
                        return clipStore.read(clip);
                    }
                },
                this::createPlayback,
                runnable -> Bukkit.getScheduler().runTask(plugin, runnable),
                System::currentTimeMillis,
                this::randomDelayMillis);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void reload() {
        controller.reload();
    }

    public int trackedMimics() {
        return controller.trackedMimics();
    }

    public int activePlaybacks() {
        return controller.activePlaybacks();
    }

    public long startedPlaybacks() {
        return controller.startedPlaybacks();
    }

    private void tick() {
        VoicechatServerApi api = apiSupplier.get();
        if (!settings.get().playback().enabled() || api == null) {
            currentApi = api;
            controller.tick(List.of(), false);
            return;
        }

        Map<UUID, Vindicator> scannedMimics = new HashMap<>();
        var targets = new ArrayList<MimicPlaybackController.Target>();
        for (World world : Bukkit.getWorlds()) {
            for (Vindicator mimic : world.getEntitiesByClass(Vindicator.class)) {
                if (!isMimic(mimic) || !mimic.isValid() || mimic.isDead()) {
                    continue;
                }
                scannedMimics.put(mimic.getUniqueId(), mimic);
                targets.add(new MimicPlaybackController.Target(
                        mimic.getUniqueId(), true, true, false,
                        nearbyVoiceListeners(api, mimic, settings.get().playback().distance()),
                        resolveIdentity(mimic)));
            }
        }
        currentApi = api;
        currentMimics.clear();
        currentMimics.putAll(scannedMimics);
        lastMalformedUuidMarkers.keySet().retainAll(scannedMimics.keySet());
        controller.tick(targets, true);
    }

    private de.maxhenkel.voicechat.api.Position position(VoicechatServerApi api, Vindicator mimic) {
        org.bukkit.Location location = mimic.getEyeLocation();
        return api.createPosition(location.getX(), location.getY(), location.getZ());
    }

    private int nearbyVoiceListeners(VoicechatServerApi api, Vindicator mimic, double range) {
        double rangeSquared = range * range;
        int listeners = 0;
        for (Player player : mimic.getWorld().getPlayers()) {
            if (player.isOnline()
                    && player.getLocation().distanceSquared(mimic.getLocation()) <= rangeSquared
                    && api.getConnectionOf(player.getUniqueId()) != null) {
                listeners++;
            }
        }
        return listeners;
    }

    private long randomDelayMillis(int minimum, int maximum) {
        int seconds = minimum == maximum ? minimum
                : ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
        return seconds * 1_000L;
    }

    private boolean isMimic(Vindicator entity) {
        return entity.getPersistentDataContainer().has(MIMIC_MARKER, PersistentDataType.BYTE);
    }

    private MimicIdentityResolver.Resolution resolveIdentity(Vindicator mimic) {
        String uuidMarker = mimic.getPersistentDataContainer()
                .get(MIMICKED_PLAYER_UUID, PersistentDataType.STRING);
        String legacyName = mimic.getPersistentDataContainer()
                .get(MIMICKED_PLAYER, PersistentDataType.STRING);
        Component name = mimic.customName();
        String displayName = name == null ? null : PlainTextComponentSerializer.plainText().serialize(name);
        MimicIdentityResolver.Resolution resolution = identityResolver.resolve(
                new MimicIdentityResolver.Markers(uuidMarker, legacyName, displayName),
                this::onlinePlayerId,
                clipStore::findPlayerId);
        if (resolution.source() == MimicIdentityResolver.Source.MALFORMED_UUID_MARKER) {
            if (!Objects.equals(lastMalformedUuidMarkers.get(mimic.getUniqueId()), uuidMarker)) {
                plugin.getLogger().warning("Ignoring malformed mimic:mimicked_player_uuid on Mimic "
                        + mimic.getUniqueId() + "; legacy name fallback is disabled until it is corrected.");
                lastMalformedUuidMarkers.put(mimic.getUniqueId(), uuidMarker);
            }
        } else {
            lastMalformedUuidMarkers.remove(mimic.getUniqueId());
        }
        return resolution;
    }

    private UUID onlinePlayerId(String playerName) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        return onlinePlayer == null ? null : onlinePlayer.getUniqueId();
    }

    private MimicPlaybackController.PlaybackHandle createPlayback(
            MimicPlaybackController.Target target, VoiceClip clip, short[] samples,
            double gain, Consumer<MimicPlaybackController.PlaybackHandle> stopped) {
        VoicechatServerApi api = apiSupplier.get();
        Vindicator mimic = currentMimics.get(target.entityId());
        if (api == null || api != currentApi || mimic == null || !mimic.isValid() || mimic.isDead()
                || !settings.get().playback().enabled()
                || !Objects.equals(resolveIdentity(mimic).identityKey(), target.identityKey())) {
            return null;
        }

        LocationalAudioChannel channel = api.createLocationalAudioChannel(
                UUID.randomUUID(), api.fromServerLevel(mimic.getWorld()), position(api, mimic));
        if (channel == null) {
            return null;
        }
        channel.setDistance(settings.get().playback().distance());
        channel.setCategory(MimicVoicechatAddon.VOLUME_CATEGORY);

        short[] adjusted = MimicPlaybackController.applyGain(samples, gain);
        AudioPlayer audioPlayer = api.createAudioPlayer(channel, api.createEncoder(), adjusted);
        if (audioPlayer == null) {
            return null;
        }
        ActivePlayback handle = new ActivePlayback(api, audioPlayer, channel,
                () -> currentMimics.get(target.entityId()), stopped);
        audioPlayer.setOnStopped(handle::finish);
        audioPlayer.startPlaying();
        return handle;
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        controller.close();
        currentMimics.clear();
        lastMalformedUuidMarkers.clear();
        currentApi = null;
    }

    private final class ActivePlayback implements MimicPlaybackController.PlaybackHandle {
        private final AudioPlayer player;
        private final VoicechatServerApi api;
        private final LocationalAudioChannel channel;
        private final Supplier<Vindicator> mimicSupplier;
        private final Consumer<MimicPlaybackController.PlaybackHandle> stopped;
        private final AtomicBoolean finished = new AtomicBoolean();

        private ActivePlayback(VoicechatServerApi api, AudioPlayer player,
                               LocationalAudioChannel channel, Supplier<Vindicator> mimicSupplier,
                               Consumer<MimicPlaybackController.PlaybackHandle> stopped) {
            this.api = api;
            this.player = player;
            this.channel = channel;
            this.mimicSupplier = mimicSupplier;
            this.stopped = stopped;
        }

        @Override
        public void update(MimicPlaybackController.Target target) {
            Vindicator mimic = mimicSupplier.get();
            if (!finished.get() && mimic != null && mimic.isValid() && !mimic.isDead()) {
                channel.updateLocation(position(api, mimic));
            }
        }

        @Override
        public void stop() {
            player.stopPlaying();
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            stopped.accept(this);
        }
    }

    private VoicechatServerApi currentApi;
}
