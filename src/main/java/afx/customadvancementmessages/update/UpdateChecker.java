package afx.customadvancementmessages.update;

import afx.customadvancementmessages.CustomAdvancementMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Checks the plugin's Modrinth project for a newer published version than
 * the one currently running, by comparing {@code plugin.yml}'s version
 * against the {@code version_number} of the most recently published
 * {@code release}-type version returned by
 * {@code GET /v2/project/{slug}/version}.
 *
 * <p>All network I/O runs on a Bukkit async task — never the main thread —
 * since it's a blocking HTTP call. The {@link Consumer} passed to
 * {@link #check(Consumer)} is always invoked back on the main thread
 * afterward, since callers use it to log to console or message players,
 * both of which are only safe from the main thread.
 */
public final class UpdateChecker {

    private static final String PROJECT_SLUG = "customadvancementmessages";
    private static final String VERSIONS_API_URL =
            "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";
    private static final String PROJECT_PAGE_URL = "https://modrinth.com/plugin/" + PROJECT_SLUG;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final CustomAdvancementMessages plugin;
    private final HttpClient httpClient;

    /**
     * Result of the most recently *completed* check. Cached so callers like
     * the join listener can show an already-known result instantly instead
     * of firing a fresh Modrinth request on every single join — only the
     * startup check and {@code /advancements update} actually hit
     * the network.
     */
    private volatile Result lastResult;

    public UpdateChecker(CustomAdvancementMessages plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /** The most recently completed check, or {@code null} if none has finished yet. */
    public Result getLastResult() {
        return lastResult;
    }

    /**
     * Runs a fresh check against Modrinth and invokes {@code callback} on
     * the main thread once it completes, whether it succeeded or failed.
     */
    public void check(Consumer<Result> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result = fetchLatestRelease();
            lastResult = result;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    private Result fetchLatestRelease() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VERSIONS_API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    // Modrinth blocks/deprioritizes generic HTTP-client user
                    // agents — they ask for the app name and, ideally, a
                    // way to reach it. See docs.modrinth.com/api/#overview.
                    .header("User-Agent", "AFXPlugins/CustomAdvancementMessages/"
                            + plugin.getDescription().getVersion() + " (" + PROJECT_PAGE_URL + ")")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return Result.failed("the Modrinth project '" + PROJECT_SLUG + "' was not found");
            }
            if (response.statusCode() != 200) {
                return Result.failed("Modrinth API returned HTTP " + response.statusCode());
            }

            JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
            if (versions.isEmpty()) {
                return Result.failed("no versions have been published on Modrinth yet");
            }

            JsonObject latestRelease = null;
            Instant latestPublished = null;
            for (JsonElement element : versions) {
                JsonObject version = element.getAsJsonObject();
                String type = jsonString(version, "version_type");
                // Only consider full releases (skip alpha/beta builds) so a
                // pre-release never triggers an update notice. Response
                // order isn't documented as guaranteed, so pick by
                // date_published ourselves rather than trusting array
                // position.
                if (type != null && !type.equalsIgnoreCase("release")) {
                    continue;
                }
                String publishedRaw = jsonString(version, "date_published");
                Instant published = publishedRaw != null ? Instant.parse(publishedRaw) : Instant.EPOCH;
                if (latestPublished == null || published.isAfter(latestPublished)) {
                    latestPublished = published;
                    latestRelease = version;
                }
            }

            if (latestRelease == null) {
                return Result.failed("no full release versions have been published on Modrinth yet");
            }

            String versionNumber = jsonString(latestRelease, "version_number");
            if (versionNumber == null || versionNumber.isBlank()) {
                return Result.failed("latest Modrinth version had no version number");
            }

            String releaseUrl = PROJECT_PAGE_URL + "/version/" + versionNumber;
            String currentVersion = plugin.getDescription().getVersion();
            boolean updateAvailable = isNewer(versionNumber, currentVersion);
            return Result.ok(updateAvailable, stripLeadingV(versionNumber), releaseUrl);
        } catch (IOException e) {
            return Result.failed("could not reach Modrinth (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("update check was interrupted");
        } catch (RuntimeException e) {
            // Malformed JSON, unparseable date, unexpected response shape, etc.
            return Result.failed("unexpected response from Modrinth (" + e.getMessage() + ")");
        }
    }

    private static String jsonString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    /**
     * True if {@code latestVersionNumber} represents a newer version than
     * {@code currentVersion}. Both are normalized by stripping a leading
     * {@code v}/{@code V} and compared component-by-component as dotted
     * integers (missing trailing components treated as {@code 0}, so
     * {@code 1.2} < {@code 1.2.1}). Falls back to a plain string inequality
     * check if either side isn't purely dotted numbers, so a non-numeric
     * version scheme still gets flagged as "different" instead of silently
     * assumed to be up to date.
     */
    static boolean isNewer(String latestVersionNumber, String currentVersion) {
        String latest = stripLeadingV(latestVersionNumber);
        String current = stripLeadingV(currentVersion);

        int[] latestParts = parseVersionParts(latest);
        int[] currentParts = parseVersionParts(current);

        if (latestParts == null || currentParts == null) {
            return !latest.equalsIgnoreCase(current);
        }

        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int l = i < latestParts.length ? latestParts[i] : 0;
            int c = i < currentParts.length ? currentParts[i] : 0;
            if (l != c) {
                return l > c;
            }
        }
        return false;
    }

    /** Splits on '.' and requires every component to be purely digits; {@code null} if not. */
    private static int[] parseVersionParts(String version) {
        String[] pieces = version.split("\\.");
        int[] parts = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            if (!pieces[i].matches("\\d+")) {
                return null;
            }
            parts[i] = Integer.parseInt(pieces[i]);
        }
        return parts;
    }

    private static String stripLeadingV(String version) {
        if (version == null) {
            return "";
        }
        return (version.startsWith("v") || version.startsWith("V")) ? version.substring(1) : version;
    }

    /** Outcome of a single update check. */
    public static final class Result {
        private final boolean success;
        private final boolean updateAvailable;
        private final String latestVersion;
        private final String releaseUrl;
        private final String failureReason;

        private Result(boolean success, boolean updateAvailable, String latestVersion,
                       String releaseUrl, String failureReason) {
            this.success = success;
            this.updateAvailable = updateAvailable;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.failureReason = failureReason;
        }

        static Result ok(boolean updateAvailable, String latestVersion, String releaseUrl) {
            return new Result(true, updateAvailable, latestVersion, releaseUrl, null);
        }

        static Result failed(String reason) {
            return new Result(false, false, null, null, reason);
        }

        public boolean isSuccess() {
            return success;
        }

        /** Only meaningful when {@link #isSuccess()} is {@code true}. */
        public boolean isUpdateAvailable() {
            return success && updateAvailable;
        }

        /** The latest published version (leading 'v' stripped), or {@code null} on failure. */
        public String getLatestVersion() {
            return latestVersion;
        }

        /** Link to the latest release page, or {@code null} on failure. */
        public String getReleaseUrl() {
            return releaseUrl;
        }

        /** Human-readable reason the check failed, or {@code null} on success. */
        public String getFailureReason() {
            return failureReason;
        }
    }
}