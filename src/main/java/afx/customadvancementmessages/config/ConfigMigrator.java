package afx.customadvancementmessages.config;

import afx.customadvancementmessages.config.lib.ConfigUpdater;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a plugin's on-disk {@code config.yml} in sync with the version bundled
 * in the jar across updates, without discarding a server owner's existing
 * values or comments.
 * <p>
 * Runs in two steps, in order:
 * <ol>
 *   <li>{@link #renameKey}: fixes up any keys that were renamed between
 *   releases (e.g. {@code plugin-version} &rarr; {@code config-version}) by
 *   rewriting just that line in place, so the value, comments, and
 *   formatting around it are untouched.</li>
 *   <li>{@link ConfigUpdater#update}: adds any keys present in the bundled
 *   default but missing from the user's file (new options introduced by an
 *   update), again preserving comments and every existing value.</li>
 * </ol>
 */
public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    /**
     * Brings {@code configFile} up to date against the {@code resourceName}
     * bundled in {@code plugin}'s jar. Safe to call every {@code onEnable()};
     * it's effectively a no-op once the file is already current.
     *
     * @param plugin       the owning plugin, used to read the bundled default and for logging
     * @param configFile   the live file on disk, e.g. {@code new File(getDataFolder(), "config.yml")}
     * @param resourceName the bundled resource path, e.g. {@code "config.yml"}
     */
    public static void update(JavaPlugin plugin, File configFile, String resourceName) {
        if (!configFile.exists()) {
            // First install — saveDefaultConfig() will lay down a fresh copy
            // that's already current, nothing to migrate or merge.
            return;
        }

        try {
            // Step 1: key renames. Add one renameKey(...) call per rename
            // that's ever shipped, oldest first, so a server that skipped
            // several updates still catches up correctly.
            renameKey(configFile, "plugin-version", "config-version");

            // Step 2: add anything new. ignoredSections is empty here since
            // nothing in this config needs to be left alone during merges;
            // pass section names (e.g. "messages") if you ever want the
            // updater to leave a whole block as the user configured it.
            List<String> ignoredSections = Collections.emptyList();
            ConfigUpdater.update(plugin, resourceName, configFile, ignoredSections);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not update " + configFile.getName() + ": " + e.getMessage()
                    + ". Delete the file to regenerate it, or fix it manually.");
        }
    }

    /**
     * Renames a top-level {@code oldKey:} to {@code newKey:} by rewriting
     * only the matching line, so the value, any trailing inline comment, and
     * every other line (including comments/blank lines/indented children)
     * are byte-for-byte unchanged. No-ops if {@code oldKey} isn't present or
     * {@code newKey} is already there (already migrated).
     */
    static void renameKey(File configFile, String oldKey, String newKey) throws IOException {
        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);

        // Matches a top-level (unindented) "oldKey:" possibly followed by a
        // value and/or an inline "# comment" on the same line. Group 1 keeps
        // everything after the key name (the ": value  # comment" part) so
        // it's carried over unchanged.
        Pattern pattern = Pattern.compile("^" + Pattern.quote(oldKey) + "(\\s*:.*)$");

        boolean foundOld = false;
        boolean foundNew = false;
        int matchIndex = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(newKey + ":")) {
                foundNew = true;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                foundOld = true;
                matchIndex = i;
            }
        }

        if (!foundOld || foundNew) {
            // Nothing to rename, or the user's file was already migrated
            // (or hand-edited to have both) — leave it alone either way.
            return;
        }

        Matcher matcher = pattern.matcher(lines.get(matchIndex));
        if (matcher.matches()) {
            lines.set(matchIndex, newKey + matcher.group(1));
            Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
        }
    }
}
