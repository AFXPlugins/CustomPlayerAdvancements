package afx.customplayeradvancements;

import afx.customplayeradvancements.update.UpdateChecker;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CustomPlayerAdvancements extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final String ADMIN_PERMISSION = "customplayeradvancements.admin";

    private final Map<String, String> decoratedNameCache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private BukkitTask refreshTask;
    private UpdateChecker updateChecker;

    private String nameFormat;

    private String taskMessageFormat;
    private String goalMessageFormat;
    private String challengeMessageFormat;

    private volatile boolean placeholderApiEnabled;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        saveDefaultConfig();
        reloadConfig();
        loadSettings();

        placeholderApiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

        if (placeholderApiEnabled) {
            getLogger().info("PlaceholderAPI hooked successfully.");
        } else {
            getLogger().info("PlaceholderAPI not found. Placeholder support disabled.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("customplayeradvancements") != null) {
            getCommand("customplayeradvancements").setExecutor(this);
            getCommand("customplayeradvancements").setTabCompleter(this);
        }

        refreshAllOnlinePlayers();
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshAllOnlinePlayers, 20L, 20L * 60L);

        updateChecker = new UpdateChecker(this);
        updateChecker.check(this::logUpdateCheckResult);

        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
                    rewriteAdvancementMessage(event);
                }
            }
        });

        getLogger().info("CustomPlayerAdvancements enabled.");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        decoratedNameCache.clear();
        PacketEvents.getAPI().terminate();
        getLogger().info("CustomPlayerAdvancements disabled.");
    }

    private void loadSettings() {
        nameFormat = getConfig().getString("player-name-format", "%player_name%");

        taskMessageFormat = buildMessageFormat("messages.task", "&bhas made the advancement", "&a");
        goalMessageFormat = buildMessageFormat("messages.goal", "&bhas reached the goal", "&e");
        challengeMessageFormat = buildMessageFormat("messages.challenge", "&bhas completed the challenge", "&d");
    }

    private String buildMessageFormat(String path, String defaultPhrase, String defaultColor) {
        String phrase = getConfig().getString(path + ".phrase", defaultPhrase);
        String color = getConfig().getString(path + ".advancement-color", defaultColor);
        return phrase + " " + color;
    }

    /**
     * Logs the outcome of an {@link UpdateChecker} run to console. Used
     * both for the automatic startup check and for
     * {@code /customplayeradvancements update} when it's run from the console.
     */
    public void logUpdateCheckResult(UpdateChecker.Result result) {
        if (!result.isSuccess()) {
            getLogger().warning("Could not check for CustomPlayerAdvancements updates: "
                    + result.getFailureReason());
            return;
        }
        if (result.isUpdateAvailable()) {
            getLogger().warning("A new version of CustomPlayerAdvancements is available: v"
                    + result.getLatestVersion() + " (currently running v"
                    + getDescription().getVersion() + "). Get it here: " + result.getReleaseUrl());
        } else {
            getLogger().info("CustomPlayerAdvancements is up to date (v"
                    + getDescription().getVersion() + ").");
        }
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        refreshPlayerCache(player);
        notifyOfUpdate(player);
    }

    /**
     * Messages an admin on join if a newer plugin version is already known
     * to be available. Uses whatever {@link UpdateChecker} last found (from
     * the startup check, or a since-run {@code /customplayeradvancements update})
     * rather than firing a fresh Modrinth request for every join — that
     * result is cached specifically so this stays free.
     */
    private void notifyOfUpdate(Player player) {
        if (!hasPermission(player, ADMIN_PERMISSION)) {
            return;
        }
        if (updateChecker == null) {
            return;
        }
        UpdateChecker.Result result = updateChecker.getLastResult();
        if (result == null || !result.isUpdateAvailable()) {
            return;
        }
        sendMessage(player, ChatColor.YELLOW + "[CustomPlayerAdvancements] "
                + ChatColor.WHITE + "A new version is available: " + ChatColor.GREEN + "v" + result.getLatestVersion()
                + ChatColor.GRAY + " (you're running v" + getDescription().getVersion() + "). "
                + ChatColor.WHITE + result.getReleaseUrl());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        decoratedNameCache.remove(normalize(event.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        refreshPlayerCache(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            sendMessage(sender, ChatColor.RED + "You do not have permission to do that.");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadSettings();
                decoratedNameCache.clear();
                refreshAllOnlinePlayers();

                sendMessage(sender, ChatColor.GREEN + "CustomPlayerAdvancements reloaded.");
                return true;
            }

            case "update" -> {
                if (updateChecker == null) {
                    sendMessage(sender, ChatColor.RED + "The update checker isn't ready yet — try again in a moment.");
                    return true;
                }

                sendMessage(sender, ChatColor.GRAY + "Checking Modrinth for updates...");
                // Always a fresh network check (unlike the cached result used for
                // the join notice) — this is an explicit "check now" request.
                updateChecker.check(result -> sendUpdateCheckResult(sender, result));
                return true;
            }

            case "preview" -> {
                if (!(sender instanceof Player)) {
                    sendMessage(sender, ChatColor.RED + "Only players can preview advancement messages.");
                    return true;
                }

                AdvancementType type = AdvancementType.TASK;
                if (args.length >= 2) {
                    type = parsePreviewType(args[1]);
                    if (type == null) {
                        sendMessage(sender, ChatColor.RED + "Unknown preview type '" + args[1]
                                + "'. Use task, goal, or challenge.");
                        return true;
                    }
                }

                sendPreviewAdvancement((Player) sender, type);
                return true;
            }

            default -> {
                sendUsage(sender, label);
                return true;
            }
        }
    }

    /** Reports the outcome of an {@link UpdateChecker} run to whoever ran {@code /customplayeradvancements update}. */
    private void sendUpdateCheckResult(CommandSender sender, UpdateChecker.Result result) {
        if (!result.isSuccess()) {
            sendMessage(sender, ChatColor.RED + "Could not check for updates: " + result.getFailureReason());
            return;
        }
        if (result.isUpdateAvailable()) {
            sendMessage(sender, ChatColor.YELLOW + "A new version is available: " + ChatColor.GREEN + "v"
                    + result.getLatestVersion() + ChatColor.GRAY + " (you're running " + ChatColor.WHITE + "v"
                    + getDescription().getVersion() + ChatColor.GRAY + "). " + ChatColor.WHITE + result.getReleaseUrl());
        } else {
            sendMessage(sender, ChatColor.GREEN + "You're already running the latest version (v"
                    + getDescription().getVersion() + ").");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("reload");
            options.add("update");
            options.add("preview");
            return filterCompletions(options, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
            List<String> options = new ArrayList<>();
            options.add("task");
            options.add("goal");
            options.add("challenge");
            return filterCompletions(options, args[1]);
        }

        return Collections.emptyList();
    }

    /** Parses a {@code /advancements preview <type>} argument into an {@link AdvancementType}, or null if invalid. */
    private AdvancementType parsePreviewType(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "task" -> AdvancementType.TASK;
            case "goal" -> AdvancementType.GOAL;
            case "challenge" -> AdvancementType.CHALLENGE;
            default -> null;
        };
    }

    private List<String> filterCompletions(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    private void sendUsage(CommandSender sender, String label) {
        sendMessage(sender, ChatColor.RED + "Usages:");
        sendMessage(sender, ChatColor.WHITE + "/" + label + " reload " + ChatColor.YELLOW + "- Reload plugin.");
        sendMessage(sender, ChatColor.WHITE + "/" + label + " update " + ChatColor.YELLOW + "- Check plugin for updates.");
        sendMessage(sender, ChatColor.WHITE + "/" + label + " preview [task|goal|challenge] " + ChatColor.YELLOW + "- Preview an advancement message with your current config. Defaults to task.");
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission(permission);
    }

    private void refreshAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerCache(player);
        }
    }

    private void refreshPlayerCache(Player player) {
        decoratedNameCache.put(normalize(player.getName()), buildDecoratedName(player));
    }

    private String buildDecoratedName(Player player) {
        String formatted;

        try {
            if (placeholderApiEnabled) {
                formatted = PlaceholderAPI.setPlaceholders(player, nameFormat);
            } else {
                formatted = nameFormat.replace("%player_name%", player.getName());
            }
        } catch (Exception ex) {
            getLogger().warning(
                    "Error resolving placeholders for "
                            + player.getName()
                            + ": "
                            + ex.getMessage()
            );

            formatted = player.getName();
        }

        formatted = colorizeLegacy(formatted);

        if (plain(formatted).isBlank()) {
            return player.getName();
        }

        return formatted;
    }

    private void rewriteAdvancementMessage(PacketSendEvent event) {
        WrapperPlayServerSystemChatMessage packet = new WrapperPlayServerSystemChatMessage(event);

        // getMessageJson()/setMessageJson() are deprecated in favor of PacketEvents' Adventure
        // Component API, but they're still functional and let us reuse the existing Gson-based
        // JSON rewriting logic below almost unchanged.
        String json = packet.getMessageJson();
        if (json == null || json.isBlank()) {
            return;
        }

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (JsonParseException ex) {
            return;
        }

        if (!rootElement.isJsonObject()) {
            return;
        }

        JsonObject root = rootElement.getAsJsonObject();

        String translateKey = getString(root, "translate");
        AdvancementType type = AdvancementType.fromTranslationKey(translateKey);
        if (type == null) {
            return;
        }

        JsonArray with = getArray(root, "with");
        if (with == null || with.size() < 2) {
            return;
        }

        String originalName = normalize(plain(extractPlainText(with.get(0))));
        if (originalName.isEmpty()) {
            return;
        }

        String decoratedName = decoratedNameCache.get(originalName);
        if (decoratedName == null || decoratedName.isBlank()) {
            Player online = findOnlinePlayerByName(originalName);
            if (online != null) {
                decoratedName = buildDecoratedName(online);
            }
        }

        if (decoratedName == null || decoratedName.isBlank()) {
            return;
        }

        Component playerComponent = LEGACY.deserialize(decoratedName);

        String colorizedMessage = colorizeLegacy(getMessageFormat(type));
        Component phraseComponent = LEGACY.deserialize(colorizedMessage);

        Component advancementComponent = deserializeComponent(with.get(1));
        if (advancementComponent == null) {
            return;
        }

        TextColor advancementColor = parseColor(ChatColor.getLastColors(colorizedMessage), NamedTextColor.WHITE);
        advancementComponent = advancementComponent.color(advancementColor);

        Component rebuilt = Component.empty()
                .append(playerComponent)
                .append(Component.space())
                .append(phraseComponent)
                .append(advancementComponent);

        packet.setMessageJson(GsonComponentSerializer.gson().serialize(rebuilt));

        // Required whenever a packet is mutated through a wrapper - tells PacketEvents to
        // actually write the changes back out, otherwise they're silently dropped.
        event.markForReEncode(true);
    }

    /**
     * Sends {@code player} a mock advancement message built with the exact same
     * pipeline as {@link #rewriteAdvancementMessage(PacketSendEvent)} (decorated
     * name, colorized phrase, parsed advancement color), so admins can check the
     * effect of config changes without actually earning an advancement. Nothing
     * is broadcast — this goes to the invoking player only.
     * <p>
     * {@code type} picks which {@code messages.*} section is previewed and which
     * fixed, safe-to-fake vanilla advancement stands in for it: {@link AdvancementType#TASK}
     * uses "Diamonds!" (story/mine_diamond), {@link AdvancementType#GOAL} uses
     * "The End... Again..." (end/respawn_dragon), and {@link AdvancementType#CHALLENGE}
     * uses "Adventuring Time" (adventure/adventuring_time) — none of these touch a real
     * advancement or grant progress.
     */
    private void sendPreviewAdvancement(Player player, AdvancementType type) {
        Component playerComponent = LEGACY.deserialize(buildDecoratedName(player));

        String colorizedMessage = colorizeLegacy(getMessageFormat(type));
        Component phraseComponent = LEGACY.deserialize(colorizedMessage);

        TextColor advancementColor = parseColor(ChatColor.getLastColors(colorizedMessage), NamedTextColor.WHITE);

        // The hover shows the advancement title followed by its description,
        // both colored with this type's configured advancement-color, so the
        // preview reflects the admin's actual config end-to-end.
        Component hoverText = Component.text(type.getPreviewName(), advancementColor)
                .append(Component.newline())
                .append(Component.text(type.getPreviewDescription(), advancementColor));

        Component advancementComponent = Component.text("[" + type.getPreviewName() + "]")
                .color(advancementColor)
                .hoverEvent(HoverEvent.showText(hoverText));

        Component rebuilt = Component.empty()
                .append(playerComponent)
                .append(Component.space())
                .append(phraseComponent)
                .append(advancementComponent);

        player.sendMessage(rebuilt);
    }

    private String getMessageFormat(AdvancementType type) {
        return switch (type) {
            case TASK -> taskMessageFormat;
            case GOAL -> goalMessageFormat;
            case CHALLENGE -> challengeMessageFormat;
        };
    }

    private Player findOnlinePlayerByName(String normalizedName) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (normalize(player.getName()).equals(normalizedName)) {
                return player;
            }
        }
        return null;
    }

    private Component deserializeComponent(JsonElement element) {
        try {
            return GsonComponentSerializer.gson().deserialize(gson.toJson(element));
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractPlainText(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }

        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }

        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonElement child : element.getAsJsonArray()) {
                out.append(extractPlainText(child));
            }
            return out.toString();
        }

        if (!element.isJsonObject()) {
            return "";
        }

        JsonObject object = element.getAsJsonObject();
        StringBuilder out = new StringBuilder();

        if (object.has("text")) {
            out.append(getString(object, "text"));
        }

        if (object.has("selector")) {
            out.append(getString(object, "selector"));
        }

        if (object.has("translate")) {
            JsonArray with = getArray(object, "with");
            if (with != null) {
                for (JsonElement child : with) {
                    out.append(extractPlainText(child));
                }
            }
        }

        if (object.has("extra")) {
            JsonArray extra = getArray(object, "extra");
            if (extra != null) {
                for (JsonElement child : extra) {
                    out.append(extractPlainText(child));
                }
            }
        }

        return out.toString();
    }

    private JsonArray getArray(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private String getString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : null;
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private String plain(String input) {
        return ChatColor.stripColor(input == null ? "" : input);
    }

    private String colorizeLegacy(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private TextColor parseColor(String input, TextColor fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }

        String value = input.trim();

        if (value.startsWith("#")) {
            TextColor hex = TextColor.fromHexString(value);
            if (hex != null) {
                return hex;
            }
        }

        if (value.startsWith("&") || value.startsWith("§")) {
            value = value.substring(1);
        }

        if (value.isEmpty()) {
            return fallback;
        }

        return switch (Character.toLowerCase(value.charAt(value.length() - 1))) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> fallback;
        };
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(message);
    }

    private enum AdvancementType {
        // Real vanilla advancements, chosen because they're short, familiar, and
        // safe to fake for /advancements preview — none of these touch a real
        // advancement or grant progress.
        TASK("Diamonds!", "Acquire diamonds"),
        GOAL("The End... Again...", "Respawn the ender dragon"),
        CHALLENGE("Adventuring Time", "Discover every biome");

        private final String previewName;
        private final String previewDescription;

        AdvancementType(String previewName, String previewDescription) {
            this.previewName = previewName;
            this.previewDescription = previewDescription;
        }

        String getPreviewName() {
            return previewName;
        }

        String getPreviewDescription() {
            return previewDescription;
        }

        static AdvancementType fromTranslationKey(String key) {
            if (key == null) {
                return null;
            }

            return switch (key) {
                case "chat.type.advancement.task" -> TASK;
                case "chat.type.advancement.goal" -> GOAL;
                case "chat.type.advancement.challenge" -> CHALLENGE;
                default -> null;
            };
        }
    }
}