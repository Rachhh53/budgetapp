package app.budget.server;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the server needs to know about its surroundings, read once from environment
 * variables. Nothing here is hard-coded elsewhere; see {@code .env.example} for the list.
 *
 * @param bindHost       interface to listen on; loopback unless phone access is turned on
 * @param port           first port to try; the next free one is used if it is taken
 * @param dataDir        folder holding the database, logs, backups and attachments
 * @param parentPid      the desktop shell's process id, so the server can exit when it dies
 * @param shutdownToken  per-launch secret the desktop shell presents to stop the server
 * @param allowedHosts   extra Host header names to accept beyond IP literals and localhost
 * @param maxUploadMb    the largest receipt or document that can be attached, in megabytes
 * @param fontsDir       a folder of fonts for the PDFs the app writes (the system's own, unless set); empty when there is none
 * @param downloadsDir   where exports and copies of receipts asked for from this PC are saved (the user's Downloads
 *                       folder, unless set); empty when there is none, and they are then handed to the browser
 */
public record Config(
        String bindHost,
        int port,
        Path dataDir,
        Optional<Long> parentPid,
        Optional<String> shutdownToken,
        Set<String> allowedHosts,
        int maxUploadMb,
        Optional<Path> fontsDir,
        Optional<Path> downloadsDir) {

    static final String DEFAULT_BIND_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 4873;
    static final String APP_FOLDER = "Budget";
    static final int DEFAULT_MAX_UPLOAD_MB = 25;

    /** Thrown with a message that is safe and useful to show the person starting the app. */
    public static final class InvalidConfigException extends RuntimeException {
        InvalidConfigException(String message) {
            super(message);
        }
    }

    public static Config fromEnvironment(Map<String, String> env) {
        return fromEnvironment(env, Platform.of(System.getProperty("os.name"), System.getProperty("user.home")));
    }

    /**
     * Where the operating system keeps things, for the defaults below. Kept apart from the environment
     * variables so the tests can ask what a Mac would get without running on one.
     *
     * @param mac  whether this is macOS, whose folders differ from Windows' and Linux's
     * @param home the user's home folder; empty when Java could not say
     */
    record Platform(boolean mac, Optional<Path> home) {
        static Platform of(String osName, String userHome) {
            return new Platform(
                    osName != null && osName.toLowerCase(Locale.ROOT).startsWith("mac"),
                    Optional.ofNullable(userHome).map(String::trim).filter(home -> !home.isEmpty()).map(Path::of));
        }
    }

    static Config fromEnvironment(Map<String, String> env, Platform platform) {
        return new Config(
                text(env, "BUDGET_BIND_HOST").orElse(DEFAULT_BIND_HOST),
                number(env, "BUDGET_PORT", 0, 65_535).map(Long::intValue).orElse(DEFAULT_PORT),
                text(env, "BUDGET_DATA_DIR").map(Path::of).orElseGet(() -> defaultDataDir(env, platform)),
                number(env, "BUDGET_PARENT_PID", 1, Long.MAX_VALUE),
                text(env, "BUDGET_SHUTDOWN_TOKEN"),
                text(env, "BUDGET_ALLOWED_HOSTS")
                        .map(list -> Arrays.stream(list.split(","))
                                .map(host -> host.trim().toLowerCase())
                                .filter(host -> !host.isEmpty())
                                .collect(Collectors.toUnmodifiableSet()))
                        .orElse(Set.of()),
                number(env, "BUDGET_MAX_UPLOAD_MB", 1, 200).map(Long::intValue).orElse(DEFAULT_MAX_UPLOAD_MB),
                text(env, "BUDGET_FONTS_DIR").map(Path::of)
                        .or(() -> text(env, "WINDIR").map(windows -> Path.of(windows, "Fonts")))
                        // Arial comes with every Mac, in this folder.
                        .or(() -> platform.mac() ? Optional.of(MAC_FONTS) : Optional.empty()),
                text(env, "BUDGET_DOWNLOADS_DIR").map(Path::of)
                        .or(() -> text(env, "USERPROFILE").map(home -> Path.of(home, "Downloads")))
                        .or(() -> platform.mac()
                                ? platform.home().map(home -> home.resolve("Downloads"))
                                : Optional.empty()));
    }

    static final Path MAC_FONTS = Path.of("/System/Library/Fonts/Supplemental");

    /**
     * %APPDATA%\Budget on Windows; ~/Library/Application Support/Budget on a Mac (the same folder the desktop
     * shell passes in there); the XDG-style equivalent elsewhere.
     */
    private static Path defaultDataDir(Map<String, String> env, Platform platform) {
        Path home = platform.home().orElse(Path.of(""));
        return text(env, "APPDATA")
                .map(appData -> Path.of(appData, APP_FOLDER))
                .orElseGet(() -> platform.mac()
                        ? home.resolve("Library").resolve("Application Support").resolve(APP_FOLDER)
                        : home.resolve(".local").resolve("share").resolve(APP_FOLDER));
    }

    private static Optional<String> text(Map<String, String> env, String name) {
        return Optional.ofNullable(env.get(name)).map(String::trim).filter(value -> !value.isEmpty());
    }

    private static Optional<Long> number(Map<String, String> env, String name, long min, long max) {
        return text(env, name).map(raw -> {
            try {
                long value = Long.parseLong(raw);
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Falls through to the shared message below.
            }
            // The value itself is left out: env vars can hold secrets by mistake.
            throw new InvalidConfigException(name + " needs to be a whole number between " + min + " and " + max + ".");
        });
    }
}
