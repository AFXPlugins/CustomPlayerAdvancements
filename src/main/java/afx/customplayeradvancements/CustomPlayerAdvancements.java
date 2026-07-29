package afx.customplayeradvancements;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CustomPlayerAdvancements extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final String MODRINTH_PROJECT_SLUG = "customplayeradvancements";
    private static final String MODRINTH_VERSIONS_API = "https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_SLUG + "/version";
    private static final String MODRINTH_PROJECT_PAGE = "https://modrinth.com/project/" + MODRINTH_PROJECT_SLUG + "/versions";

    private final Map<String, String> decoratedNameCache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private ProtocolManager protocolManager;
    private BukkitTask refreshTask;

    private String nameFormat;

    private String taskMessageFormat;
    private String goalMessageFormat;
    private String challengeMessageFormat;

    private volatile boolean placeholderApiEnabled;
    private volatile boolean updateAvailable;
    private volatile String latestVersion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadSettings();

        protocolManager = ProtocolLibrary.getProtocolManager();

        placeholderApiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

        if (placeholderApiEnabled) {
            getLogger().info("PlaceholderAPI hooked successfully.");
        } else {
            getLogger().info("PlaceholderAPI not found. Placeholder support disabled.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("ancustomizer") != null) {
            getCommand("ancustomizer").setExecutor(this);
            getCommand("ancustomizer").setTabCompleter(this);
        }

        refreshAllOnlinePlayers();
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshAllOnlinePlayers, 20L, 20L * 60L);

        checkForUpdatesAsync(null);

        protocolManager.addPacketListener(new PacketAdapter(
                this,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SYSTEM_CHAT,
                PacketType.Play.Server.CHAT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                boolean systemChat = event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT;
                rewriteAdvancementMessage(event, systemChat);
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
        getLogger().info("CustomPlayerAdvancements disabled.");
    }

    private void loadSettings() {
        nameFormat = getConfig().getString("format", "%luckperms_prefix%%essentials_nickname%");

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
     * Queries the Modrinth API for the latest published version of this plugin and compares
     * it against the currently running version. Runs off the main thread.
     *
     * @param requester optional sender to report the result back to (may be null, e.g. on startup)
     */
    private void checkForUpdatesAsync(CommandSender requester) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String newestVersion = fetchLatestModrinthVersion();

            if (newestVersion == null) {
                getLogger().warning("Could not check for updates on Modrinth.");
                if (requester != null) {
                    Bukkit.getScheduler().runTask(this, () ->
                            sendMessage(requester, ChatColor.RED + "Could not reach Modrinth to check for updates."));
                }
                return;
            }

            String currentVersion = getDescription().getVersion();
            boolean isNewer = isVersionNewer(newestVersion, currentVersion);

            latestVersion = newestVersion;
            updateAvailable = isNewer;

            if (isNewer) {
                getLogger().warning("A new version of CustomPlayerAdvancements is available: "
                        + newestVersion + " (running " + currentVersion + "). Download: " + MODRINTH_PROJECT_PAGE);
            } else {
                getLogger().info("CustomPlayerAdvancements is up to date (" + currentVersion + ").");
            }

            if (requester != null) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (isNewer) {
                        sendMessage(requester, ChatColor.GREEN + "A new version (" + newestVersion
                                + ") is available. You are running " + currentVersion + ".");
                        sendMessage(requester, ChatColor.AQUA + MODRINTH_PROJECT_PAGE);
                    } else {
                        sendMessage(requester, ChatColor.GREEN + "You are running the latest version ("
                                + currentVersion + ").");
                    }
                });
            }
        });
    }

    /**
     * Fetches every published version from Modrinth and returns the version_number of the most
     * recently published one, or null if the lookup failed.
     */
    private String fetchLatestModrinthVersion() {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(MODRINTH_VERSIONS_API).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "CustomPlayerAdvancements/" + getDescription().getVersion() + " (Modrinth update checker)");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int status = connection.getResponseCode();
            if (status != 200) {
                connection.disconnect();
                return null;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            } finally {
                connection.disconnect();
            }

            JsonElement rootElement = JsonParser.parseString(body.toString());
            if (!rootElement.isJsonArray()) {
                return null;
            }

            JsonArray versions = rootElement.getAsJsonArray();
            String newestVersionNumber = null;
            String newestDatePublished = null;

            for (JsonElement versionElement : versions) {
                if (!versionElement.isJsonObject()) {
                    continue;
                }

                JsonObject versionObject = versionElement.getAsJsonObject();
                String versionNumber = getString(versionObject, "version_number");
                String datePublished = getString(versionObject, "date_published");

                if (versionNumber == null || datePublished == null) {
                    continue;
                }

                if (newestDatePublished == null || datePublished.compareTo(newestDatePublished) > 0) {
                    newestDatePublished = datePublished;
                    newestVersionNumber = versionNumber;
                }
            }

            return newestVersionNumber;
        } catch (Exception ex) {
            getLogger().warning("Error checking Modrinth for updates: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Compares two dotted/numeric version strings (e.g. "1.2.10" vs "1.3"), ignoring any
     * non-numeric suffix such as "-beta". Returns true if 'candidate' is newer than 'current'.
     */
    private boolean isVersionNewer(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }

        int[] candidateParts = parseVersionParts(candidate);
        int[] currentParts = parseVersionParts(current);
        int length = Math.max(candidateParts.length, currentParts.length);

        for (int i = 0; i < length; i++) {
            int candidatePart = i < candidateParts.length ? candidateParts[i] : 0;
            int currentPart = i < currentParts.length ? currentParts[i] : 0;

            if (candidatePart != currentPart) {
                return candidatePart > currentPart;
            }
        }

        return false;
    }

    private int[] parseVersionParts(String version) {
        String numericPrefix = version.trim();
        int cut = numericPrefix.length();
        for (int i = 0; i < numericPrefix.length(); i++) {
            char c = numericPrefix.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                cut = i;
                break;
            }
        }
        numericPrefix = numericPrefix.substring(0, cut);

        String[] rawParts = numericPrefix.split("\\.");
        int[] parts = new int[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            try {
                parts[i] = rawParts[i].isEmpty() ? 0 : Integer.parseInt(rawParts[i]);
            } catch (NumberFormatException ex) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshPlayerCache(event.getPlayer());

        Player player = event.getPlayer();
        if (updateAvailable && (player.isOp() || player.hasPermission("ancustomizer.update"))) {
            sendMessage(player, ChatColor.YELLOW + "[CustomPlayerAdvancements] "
                    + ChatColor.GREEN + "A new version (" + latestVersion + ") is available. "
                    + ChatColor.GRAY + "You are running " + getDescription().getVersion() + ". "
                    + ChatColor.AQUA + MODRINTH_PROJECT_PAGE);
        }
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

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!hasPermission(sender, "ancustomizer.reload")) {
                    sendMessage(sender, ChatColor.RED + "You do not have permission to do that.");
                    return true;
                }

                reloadConfig();
                loadSettings();
                decoratedNameCache.clear();
                refreshAllOnlinePlayers();

                sendMessage(sender, ChatColor.GREEN + "CustomPlayerAdvancements reloaded.");
                return true;
            }

            case "update" -> {
                if (!hasPermission(sender, "ancustomizer.update")) {
                    sendMessage(sender, ChatColor.RED + "You do not have permission to do that.");
                    return true;
                }

                sendMessage(sender, ChatColor.GRAY + "Checking Modrinth for updates...");
                checkForUpdatesAsync(sender);
                return true;
            }

            default -> {
                sendUsage(sender, label);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("reload");
            options.add("update");
            return filterCompletions(options, args[0]);
        }

        return Collections.emptyList();
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
        sendMessage(sender, ChatColor.YELLOW + "Usage:");
        sendMessage(sender, ChatColor.GRAY + "/" + label + " reload");
        sendMessage(sender, ChatColor.GRAY + "/" + label + " update");
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

    private void rewriteAdvancementMessage(PacketEvent event, boolean preferStrings) {
        PacketContainer packet = event.getPacket();

        String json = readJson(packet, preferStrings);
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

        writeJson(packet, GsonComponentSerializer.gson().serialize(rebuilt), preferStrings);
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

    private String readJson(PacketContainer packet, boolean preferStrings) {
        if (preferStrings) {
            try {
                return packet.getStrings().read(0);
            } catch (Exception ignored) {
            }

            try {
                WrappedChatComponent component = packet.getChatComponents().read(0);
                return component == null ? null : component.getJson();
            } catch (Exception ignored) {
            }
        } else {
            try {
                WrappedChatComponent component = packet.getChatComponents().read(0);
                if (component != null) {
                    return component.getJson();
                }
            } catch (Exception ignored) {
            }

            try {
                return packet.getStrings().read(0);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private void writeJson(PacketContainer packet, String json, boolean preferStrings) {
        if (preferStrings) {
            try {
                packet.getStrings().write(0, json);
                return;
            } catch (Exception ignored) {
            }

            try {
                packet.getChatComponents().write(0, WrappedChatComponent.fromJson(json));
                return;
            } catch (Exception ex) {
                getLogger().warning("Failed to rewrite system chat packet: " + ex.getMessage());
            }
        } else {
            try {
                packet.getChatComponents().write(0, WrappedChatComponent.fromJson(json));
                return;
            } catch (Exception ignored) {
            }

            try {
                packet.getStrings().write(0, json);
            } catch (Exception ex) {
                getLogger().warning("Failed to rewrite chat packet: " + ex.getMessage());
            }
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
        TASK,
        GOAL,
        CHALLENGE;

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
