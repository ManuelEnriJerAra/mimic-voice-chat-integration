package com.manujerozx.mimicvoice.playback;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

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
    private static final NamespacedKey MIMICKED_PLAYER = new NamespacedKey("mimic", "mimicked_player");

    private final MimicSimpleVoiceChatIntegration plugin;
    private final ClipStore clipStore;
    private final Supplier<PluginSettings> settings;
    private final Supplier<VoicechatServerApi> apiSupplier;
    private final Map<UUID, PlaybackState> states = new HashMap<>();
    private long startedPlaybacks;
    private BukkitTask task;

    public MimicPlaybackManager(MimicSimpleVoiceChatIntegration plugin, ClipStore clipStore,
                                Supplier<PluginSettings> settings,
                                Supplier<VoicechatServerApi> apiSupplier) {
        this.plugin = plugin;
        this.clipStore = clipStore;
        this.settings = settings;
        this.apiSupplier = apiSupplier;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void reload() {
        long now = System.currentTimeMillis();
        states.values().forEach(state -> {
            state.identityVersion++;
            state.loading = false;
            state.nextPlaybackAt = now;
            stopPlayback(state);
        });
    }

    public int trackedMimics() {
        return states.size();
    }

    public int activePlaybacks() {
        return (int) states.values().stream().filter(state -> state.playback != null).count();
    }

    public long startedPlaybacks() {
        return startedPlaybacks;
    }

    private void tick() {
        PluginSettings.Playback playbackSettings = settings.get().playback();
        VoicechatServerApi api = apiSupplier.get();
        if (!playbackSettings.enabled() || api == null) {
            suspendAllPlaybacks();
            return;
        }

        long now = System.currentTimeMillis();
        Set<UUID> seen = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (Vindicator mimic : world.getEntitiesByClass(Vindicator.class)) {
                if (!isMimic(mimic) || !mimic.isValid() || mimic.isDead()) {
                    continue;
                }
                seen.add(mimic.getUniqueId());
                PlaybackState state = states.computeIfAbsent(mimic.getUniqueId(), ignored -> {
                    PlaybackState created = new PlaybackState();
                    created.nextPlaybackAt = now + randomDelayMillis(
                            playbackSettings.firstMinimumSeconds(), playbackSettings.firstMaximumSeconds());
                    return created;
                });
                tickMimic(api, mimic, state, playbackSettings, now);
            }
        }

        for (UUID entityId : new HashSet<>(states.keySet())) {
            if (!seen.contains(entityId)) {
                PlaybackState removed = states.remove(entityId);
                stopPlayback(removed);
            }
        }
    }

    private void tickMimic(VoicechatServerApi api, Vindicator mimic, PlaybackState state,
                           PluginSettings.Playback playbackSettings, long now) {
        UUID mimickedPlayerId = mimickedPlayerId(mimic);
        if (!Objects.equals(state.mimickedPlayerId, mimickedPlayerId)) {
            state.mimickedPlayerId = mimickedPlayerId;
            state.identityVersion++;
            state.loading = false;
            state.lastClipId = null;
            stopPlayback(state);
            state.nextPlaybackAt = now + randomDelayMillis(
                    playbackSettings.firstMinimumSeconds(), playbackSettings.firstMaximumSeconds());
        }
        if (state.loading || state.playback != null || now < state.nextPlaybackAt) {
            if (state.playback != null) {
                state.playback.updateLocation(mimic);
            }
            return;
        }
        if (nearbyVoiceListeners(api, mimic, playbackSettings.distance())
                < playbackSettings.minimumNearbyListeners()) {
            state.nextPlaybackAt = now + 1_000L;
            return;
        }

        VoiceClip clip = clipStore.select(mimickedPlayerId, state.lastClipId);
        if (clip == null) {
            state.nextPlaybackAt = now + playbackSettings.retryWithoutClipSeconds() * 1_000L;
            return;
        }

        long identityVersion = state.identityVersion;
        state.loading = true;
        state.nextPlaybackAt = Long.MAX_VALUE;
        clipStore.read(clip).whenComplete((samples, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (state.identityVersion != identityVersion) {
                    return;
                }
                state.loading = false;
                if (failure != null) {
                    plugin.getLogger().log(Level.WARNING, "Could not load a Mimic voice clip", failure);
                    scheduleRetry(state, playbackSettings, System.currentTimeMillis());
                    return;
                }
                if (samples == null || !mimic.isValid() || states.get(mimic.getUniqueId()) != state
                        || state.playback != null || apiSupplier.get() != api
                        || !settings.get().playback().enabled()
                        || !mimickedPlayerId.equals(mimickedPlayerId(mimic))) {
                    scheduleRetry(state, playbackSettings, System.currentTimeMillis());
                    return;
                }
                play(api, mimic, state, clip, samples, playbackSettings);
            });
        });
    }

    private void play(VoicechatServerApi api, Vindicator mimic, PlaybackState state,
                      VoiceClip clip, short[] samples, PluginSettings.Playback playbackSettings) {
        LocationalAudioChannel channel = api.createLocationalAudioChannel(
                UUID.randomUUID(), api.fromServerLevel(mimic.getWorld()), position(api, mimic));
        if (channel == null) {
            scheduleRetry(state, playbackSettings, System.currentTimeMillis());
            return;
        }
        channel.setDistance(playbackSettings.distance());
        channel.setCategory(MimicVoicechatAddon.VOLUME_CATEGORY);

        short[] adjusted = applyGain(samples, playbackSettings.volume());
        AudioPlayer audioPlayer = api.createAudioPlayer(channel, api.createEncoder(), adjusted);
        ActivePlayback handle = new ActivePlayback(api, audioPlayer, channel, state);
        audioPlayer.setOnStopped(handle::finish);
        state.playback = handle;
        state.lastClipId = clip.id();
        startedPlaybacks++;
        audioPlayer.startPlaying();
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

    private String mimicName(Vindicator mimic) {
        String persistedName = mimic.getPersistentDataContainer()
                .get(MIMICKED_PLAYER, PersistentDataType.STRING);
        if (persistedName != null && !persistedName.isBlank()) {
            return persistedName;
        }
        Component name = mimic.customName();
        if (name == null) {
            return null;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        return plain.isBlank() ? null : plain;
    }

    private UUID mimickedPlayerId(Vindicator mimic) {
        String playerName = mimicName(mimic);
        if (playerName == null) {
            return null;
        }
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        return onlinePlayer == null ? clipStore.findPlayerId(playerName) : onlinePlayer.getUniqueId();
    }

    private short[] applyGain(short[] samples, double gain) {
        if (Math.abs(gain - 1.0) < 1.0e-6) {
            return samples;
        }
        short[] adjusted = new short[samples.length];
        for (int index = 0; index < samples.length; index++) {
            int scaled = (int) Math.round(samples[index] * gain);
            adjusted[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
        return adjusted;
    }

    private void stopAllPlaybacks() {
        states.values().forEach(this::stopPlayback);
    }

    private void suspendAllPlaybacks() {
        long retryAt = System.currentTimeMillis() + 1_000L;
        states.values().forEach(state -> {
            if (state.loading) {
                state.identityVersion++;
                state.loading = false;
            }
            state.nextPlaybackAt = retryAt;
            stopPlayback(state);
        });
    }

    private void scheduleRetry(PlaybackState state, PluginSettings.Playback playbackSettings, long now) {
        state.nextPlaybackAt = now + playbackSettings.retryWithoutClipSeconds() * 1_000L;
    }

    private void stopPlayback(PlaybackState state) {
        if (state != null && state.playback != null) {
            ActivePlayback playback = state.playback;
            state.playback = null;
            playback.stop();
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        stopAllPlaybacks();
        states.clear();
    }

    private static final class PlaybackState {
        private long nextPlaybackAt;
        private String lastClipId;
        private UUID mimickedPlayerId;
        private long identityVersion;
        private boolean loading;
        private ActivePlayback playback;
    }

    private final class ActivePlayback {
        private final AudioPlayer player;
        private final VoicechatServerApi api;
        private final LocationalAudioChannel channel;
        private final PlaybackState state;
        private final AtomicBoolean finished = new AtomicBoolean();

        private ActivePlayback(VoicechatServerApi api, AudioPlayer player,
                               LocationalAudioChannel channel, PlaybackState state) {
            this.api = api;
            this.player = player;
            this.channel = channel;
            this.state = state;
        }

        private void updateLocation(Vindicator mimic) {
            if (!finished.get()) {
                channel.updateLocation(position(api, mimic));
            }
        }

        private void stop() {
            player.stopPlaying();
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (state.playback == this) {
                        state.playback = null;
                        PluginSettings.Playback playbackSettings = settings.get().playback();
                        state.nextPlaybackAt = System.currentTimeMillis() + randomDelayMillis(
                                playbackSettings.repeatMinimumSeconds(),
                                playbackSettings.repeatMaximumSeconds());
                    }
                });
            }
        }
    }
}
