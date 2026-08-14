package com.manujerozx.mimicvoice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.manujerozx.mimicvoice.playback.MimicPlaybackManager;
import com.manujerozx.mimicvoice.storage.ClipStore;
import com.manujerozx.mimicvoice.storage.ConsentRegistry;
import com.manujerozx.mimicvoice.voice.MimicVoicechatAddon;
import com.manujerozx.mimicvoice.voice.PlayerSnapshotCache;
import com.manujerozx.mimicvoice.voice.VoiceRecordingManager;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;

public final class MimicSimpleVoiceChatIntegration extends JavaPlugin implements Listener, TabExecutor {

    private volatile PluginSettings settings;
    private ClipStore clipStore;
    private ConsentRegistry consentRegistry;
    private VoiceRecordingManager recordingManager;
    private MimicVoicechatAddon voicechatAddon;
    private MimicPlaybackManager playbackManager;
    private final PlayerSnapshotCache playerSnapshots = new PlayerSnapshotCache();
    private final Set<UUID> privacyNotifiedPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean capturePaused = true;
    private volatile boolean consentReady;
    private BukkitTask playerSnapshotTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = PluginSettings.from(getConfig());
        consentRegistry = new ConsentRegistry(getDataFolder(), getLogger());
        Path recordingDirectory = getDataFolder().toPath().resolve("recordings");
        clipStore = new ClipStore(recordingDirectory, getLogger(), this::settings);
        recordingManager = new VoiceRecordingManager(
                getLogger(), this::settings, clipStore, consentRegistry, this::captureAllowed,
                playerSnapshots);
        voicechatAddon = new MimicVoicechatAddon(recordingManager, getLogger());
        refreshPlayerSnapshots();

        BukkitVoicechatService voicechatService = getServer().getServicesManager()
                .load(BukkitVoicechatService.class);
        if (voicechatService == null) {
            getLogger().severe("Simple Voice Chat did not expose its Bukkit API service.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        voicechatService.registerPlugin(voicechatAddon);

        playbackManager = new MimicPlaybackManager(
                this, clipStore, this::settings, voicechatAddon::serverApi);
        playbackManager.start();
        getServer().getPluginManager().registerEvents(this, this);
        playerSnapshotTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshPlayerSnapshots,
                1L, 20L);
        if (getCommand("mimicvoice") != null) {
            getCommand("mimicvoice").setExecutor(this);
            getCommand("mimicvoice").setTabCompleter(this);
        }

        clipStore.initialize().thenAccept(loaded -> {
            if (isEnabled()) {
                getLogger().info("Mimic Simple Voice Chat Integration enabled with " + loaded
                        + " saved speech clip(s).");
            }
        });
        consentRegistry.initialize().whenComplete((loaded, failure) -> {
            if (!isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!isEnabled()) {
                        return;
                    }
                    if (failure != null || !Boolean.TRUE.equals(loaded)) {
                        getLogger().severe("Consent data could not be loaded; voice capture remains paused.");
                        return;
                    }
                    consentReady = true;
                    refreshPlayerSnapshots();
                    Bukkit.getOnlinePlayers().forEach(this::sendPrivacyNotice);
                    capturePaused = false;
                });
            } catch (RuntimeException exception) {
                if (isEnabled()) {
                    getLogger().log(java.util.logging.Level.WARNING,
                            "Could not dispatch consent initialization to the Bukkit thread", exception);
                }
            }
        });
    }

    @Override
    public void onDisable() {
        capturePaused = true;
        consentReady = false;
        if (playerSnapshotTask != null) {
            playerSnapshotTask.cancel();
            playerSnapshotTask = null;
        }
        if (voicechatAddon != null) {
            voicechatAddon.close();
        }
        if (playbackManager != null) {
            playbackManager.close();
        }
        if (recordingManager != null) {
            if (!recordingManager.shutdown()) {
                getLogger().severe("Voice capture shutdown did not reach a terminal worker state; "
                        + "capture is fail-closed and storage admission is disabled while the installed "
                        + "Opus implementation remains live.");
            }
        }
        if (clipStore != null) {
            if (!clipStore.shutdown()) {
                getLogger().severe("Voice clip storage shutdown did not reach a terminal worker state; "
                        + "post-close clip registration and index mutation are disabled.");
            }
        }
        if (consentRegistry != null) {
            consentRegistry.close();
        }
        playerSnapshots.replaceAll(Map.of());
        privacyNotifiedPlayers.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerSnapshots.remove(playerId);
        privacyNotifiedPlayers.remove(playerId);
        recordingManager.endPlayer(playerId);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        refreshPlayerSnapshot(player);
        recordingManager.resumePlayer(player.getUniqueId());
        if (!capturePaused) {
            sendPrivacyNotice(player);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("consent")) {
            return handleConsent(sender, args);
        }
        if (!sender.hasPermission("mimicvoice.admin")) {
            sender.sendMessage(Component.text("You do not have permission to use this command.",
                    NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            capturePaused = true;
            try {
                reloadConfig();
                privacyNotifiedPlayers.clear();
                settings = PluginSettings.from(getConfig());
                refreshPlayerSnapshots();
                recordingManager.reload();
                playbackManager.reload();
                Bukkit.getOnlinePlayers().forEach(this::sendPrivacyNotice);
            } finally {
                capturePaused = !consentReady;
            }
            sender.sendMessage(Component.text("Mimic voice settings reloaded.", NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("clear") && args.length >= 2) {
            clearClips(sender, args[1]);
            return true;
        }

        sender.sendMessage(Component.text(
                "Usage: /mimicvoice status | reload | clear <player|all> | consent <allow|deny|status>",
                NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleConsent(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can change their recording consent.",
                    NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if (action.equals("allow") || action.equals("on")) {
            consentRegistry.setAllowed(player.getUniqueId(), true)
                    .thenAccept(saved -> reportConsentPersistence(player, saved, true));
            sender.sendMessage(Component.text(
                    "Your future voice activity may be recorded for Mimics.", NamedTextColor.GREEN));
        } else if (action.equals("deny") || action.equals("off")) {
            consentRegistry.setAllowed(player.getUniqueId(), false)
                    .thenAccept(saved -> reportConsentPersistence(player, saved, false));
            recordingManager.discard(player.getUniqueId());
            sender.sendMessage(Component.text(
                    "Your future voice activity will not be recorded.", NamedTextColor.GREEN));
        } else {
            boolean allowed = consentRegistry.mayRecord(player.getUniqueId());
            sender.sendMessage(Component.text("Mimic voice recording is currently "
                    + (allowed ? "allowed" : "denied") + " for you.", NamedTextColor.GRAY));
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        boolean ready = voicechatAddon.serverApi() != null;
        sender.sendMessage(Component.text("Mimic Voice Chat: " + (ready ? "ready" : "waiting for voice chat"),
                ready ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Clips: " + clipStore.clipCount() + " from "
                + clipStore.playerCount() + " player(s); capture sessions: "
                + recordingManager.activeSessions() + "; tracked Mimics: "
                + playbackManager.trackedMimics() + "; playing: "
                + playbackManager.activePlaybacks() + "; total playbacks: "
                + playbackManager.startedPlaybacks() + ".", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Capture queue: " + recordingManager.queueDepth() + "/"
                + recordingManager.queueCapacity() + "; packets received: "
                + recordingManager.receivedPackets() + "; processed: " + recordingManager.processedPackets()
                + "; overload drops: " + recordingManager.overloadDroppedPackets()
                + "; accepted speech segments: " + recordingManager.acceptedSegments()
                + "; clip submission failures: " + recordingManager.clipSubmissionFailures()
                + "; pending storage writes: " + clipStore.pendingSaveCount()
                + " (" + clipStore.pendingSaveBytes() + " bytes)"
                + "; storage saves succeeded: " + clipStore.saveSucceeded()
                + "; storage saves failed: " + clipStore.saveFailures()
                + "; storage saves rejected by backpressure: "
                + clipStore.saveRejectedBackpressure()
                + "; pending playback reads: " + clipStore.pendingReadCount()
                + "; playback reads rejected by backpressure: " + clipStore.readRejectedBackpressure()
                + "; memory audio: " + clipStore.memoryAudioBytes() + " bytes; active playback PCM: "
                + playbackManager.activePlaybackPcmBytes() + " bytes; playback PCM rejections: "
                + playbackManager.playbackPcmRejections() + ".",
                NamedTextColor.GRAY));
    }

    private void clearClips(CommandSender sender, String target) {
        if (target.equalsIgnoreCase("all")) {
            playbackManager.clearAll();
            clipStore.clearAll().whenComplete((count, failure) -> {
                if (failure != null) {
                    getLogger().log(java.util.logging.Level.WARNING,
                            "Could not clear all Mimic voice clips", failure);
                    sendAsync(sender, "Could not clear Mimic voice clips; no deletion was confirmed.",
                            NamedTextColor.RED);
                } else {
                    sendAsync(sender, "Removed " + count + " Mimic voice clip(s).");
                }
            });
            return;
        }

        Player online = Bukkit.getPlayerExact(target);
        UUID playerId = online != null
                ? online.getUniqueId()
                : clipStore.findPlayerId(target);
        if (playerId == null) {
            sender.sendMessage(Component.text("No saved clips were found for " + target + ".",
                    NamedTextColor.YELLOW));
            return;
        }
        UUID resolvedId = playerId;
        playbackManager.clearPlayer(resolvedId);
        clipStore.clearPlayer(resolvedId).whenComplete((count, failure) -> {
            if (failure != null) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Could not clear Mimic voice clips for " + target, failure);
                sendAsync(sender, "Could not clear Mimic voice clips for " + target
                        + "; no deletion was confirmed.", NamedTextColor.RED);
            } else {
                sendAsync(sender, "Removed " + count + " Mimic voice clip(s) for " + target + ".");
            }
        });
    }

    private void sendAsync(CommandSender sender, String message) {
        sendAsync(sender, message, NamedTextColor.GREEN);
    }

    private void sendAsync(CommandSender sender, String message, NamedTextColor color) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this,
                    () -> sender.sendMessage(Component.text(message, color)));
        }
    }

    private void sendPrivacyNotice(Player player) {
        PluginSettings.Recording recording = settings.recording();
        privacyNotifiedPlayers.remove(player.getUniqueId());
        if (!recording.enabled() || !recording.privacyNotice() || !player.isOnline()) {
            return;
        }
        boolean allowed = consentRegistry.mayRecord(player.getUniqueId());
        String notice = allowed
                ? "Warning: voice chat speech is recorded by default for Mimic creatures. "
                        + "Use /mimicvoice consent deny to opt out."
                : "Mimic voice recording remains disabled for you. "
                        + "Use /mimicvoice consent allow to enable it.";
        player.sendMessage(Component.text(notice, NamedTextColor.YELLOW));
        privacyNotifiedPlayers.add(player.getUniqueId());
    }

    private void reportConsentPersistence(Player player, boolean saved, boolean allowed) {
        if (saved || !isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!isEnabled()) {
                    return;
                }
                String message = allowed
                        ? "Recording is allowed for this session, but your preference could not be saved."
                        : "Recording is disabled for this session, but your opt-out could not be saved; "
                                + "contact an administrator before reconnecting.";
                player.sendMessage(Component.text(message,
                        allowed ? NamedTextColor.YELLOW : NamedTextColor.RED));
            });
        } catch (RuntimeException exception) {
            if (isEnabled()) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Could not dispatch consent persistence status to the Bukkit thread", exception);
            }
        }
    }

    private void refreshPlayerSnapshots() {
        Map<UUID, PlayerSnapshotCache.Snapshot> replacement = new java.util.HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PluginSettings.Recording recording = settings.recording();
            replacement.put(player.getUniqueId(), new PlayerSnapshotCache.Snapshot(
                    player.getName(), recording.permission().isBlank()
                            || player.hasPermission(recording.permission())));
        }
        playerSnapshots.replaceAll(replacement);
    }

    private void refreshPlayerSnapshot(Player player) {
        PluginSettings.Recording recording = settings.recording();
        playerSnapshots.put(player.getUniqueId(), player.getName(),
                recording.permission().isBlank() || player.hasPermission(recording.permission()));
    }

    private boolean captureAllowed(UUID playerId) {
        return consentReady && !capturePaused && (!settings.recording().privacyNotice()
                || privacyNotifiedPlayers.contains(playerId));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("consent"));
            if (sender.hasPermission("mimicvoice.admin")) {
                options.addAll(List.of("status", "reload", "clear"));
            }
            return prefix(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("consent")) {
            return prefix(List.of("allow", "deny", "status"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")
                && sender.hasPermission("mimicvoice.admin")) {
            List<String> options = new ArrayList<>(List.of("all"));
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
            return prefix(options, args[1]);
        }
        return List.of();
    }

    private List<String> prefix(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private PluginSettings settings() {
        return settings;
    }
}
