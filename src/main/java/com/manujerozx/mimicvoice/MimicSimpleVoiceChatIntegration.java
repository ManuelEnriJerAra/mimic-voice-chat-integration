package com.manujerozx.mimicvoice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.manujerozx.mimicvoice.playback.MimicPlaybackManager;
import com.manujerozx.mimicvoice.storage.ClipStore;
import com.manujerozx.mimicvoice.storage.ConsentRegistry;
import com.manujerozx.mimicvoice.voice.MimicVoicechatAddon;
import com.manujerozx.mimicvoice.voice.VoiceRecordingManager;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;

public final class MimicSimpleVoiceChatIntegration extends JavaPlugin implements Listener, TabExecutor {

    private volatile PluginSettings settings;
    private ClipStore clipStore;
    private ConsentRegistry consentRegistry;
    private VoiceRecordingManager recordingManager;
    private MimicVoicechatAddon voicechatAddon;
    private MimicPlaybackManager playbackManager;
    private final Set<UUID> privacyNotifiedPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean capturePaused = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = PluginSettings.from(getConfig());
        consentRegistry = new ConsentRegistry(getDataFolder(), getLogger());
        Path recordingDirectory = getDataFolder().toPath().resolve("recordings");
        clipStore = new ClipStore(recordingDirectory, getLogger(), this::settings);
        recordingManager = new VoiceRecordingManager(
                getLogger(), this::settings, clipStore, consentRegistry, this::captureAllowed);
        voicechatAddon = new MimicVoicechatAddon(recordingManager, getLogger());

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
        Bukkit.getOnlinePlayers().forEach(this::sendPrivacyNotice);
        capturePaused = false;
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
    }

    @Override
    public void onDisable() {
        capturePaused = true;
        if (voicechatAddon != null) {
            voicechatAddon.close();
        }
        if (playbackManager != null) {
            playbackManager.close();
        }
        if (recordingManager != null) {
            recordingManager.close();
        }
        if (clipStore != null) {
            clipStore.close();
        }
        privacyNotifiedPlayers.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        privacyNotifiedPlayers.remove(event.getPlayer().getUniqueId());
        recordingManager.finish(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sendPrivacyNotice(event.getPlayer());
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
                recordingManager.reload();
                playbackManager.reload();
                Bukkit.getOnlinePlayers().forEach(this::sendPrivacyNotice);
            } finally {
                capturePaused = false;
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
            boolean persisted = consentRegistry.setAllowed(player.getUniqueId(), true);
            String message = persisted
                    ? "Your future voice activity may be recorded for Mimics."
                    : "Recording is allowed for this session, but your preference could not be saved.";
            sender.sendMessage(Component.text(message,
                    persisted ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        } else if (action.equals("deny") || action.equals("off")) {
            boolean persisted = consentRegistry.setAllowed(player.getUniqueId(), false);
            recordingManager.discard(player.getUniqueId());
            String message = persisted
                    ? "Your future voice activity will not be recorded."
                    : "Recording is disabled for this session, but your opt-out could not be saved; "
                            + "contact an administrator before reconnecting.";
            sender.sendMessage(Component.text(message,
                    persisted ? NamedTextColor.GREEN : NamedTextColor.RED));
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
        sender.sendMessage(Component.text("Packets processed: " + recordingManager.receivedPackets()
                + "; speech clips accepted: " + recordingManager.savedClips() + ".",
                NamedTextColor.GRAY));
    }

    private void clearClips(CommandSender sender, String target) {
        if (target.equalsIgnoreCase("all")) {
            clipStore.clearAll().thenAccept(count -> sendAsync(sender,
                    "Removed " + count + " Mimic voice clip(s)."));
            return;
        }

        UUID playerId = clipStore.findPlayerId(target);
        Player online = Bukkit.getPlayerExact(target);
        if (playerId == null && online != null) {
            playerId = online.getUniqueId();
        }
        if (playerId == null) {
            sender.sendMessage(Component.text("No saved clips were found for " + target + ".",
                    NamedTextColor.YELLOW));
            return;
        }
        UUID resolvedId = playerId;
        clipStore.clearPlayer(resolvedId).thenAccept(count -> sendAsync(sender,
                "Removed " + count + " Mimic voice clip(s) for " + target + "."));
    }

    private void sendAsync(CommandSender sender, String message) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this,
                    () -> sender.sendMessage(Component.text(message, NamedTextColor.GREEN)));
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

    private boolean captureAllowed(UUID playerId) {
        return !capturePaused && (!settings.recording().privacyNotice()
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
