package app.budget.server;

import java.nio.file.Path;
import java.util.Arrays;
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
 * @param fontsDir       a folder of fonts for the PDFs the app writes (Windows' own, unless set); empty when there is none
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
        return new Config(
                text(env, "BUDGET_BIND_HOST").orElse(DEFAULT_BIND_HOST),
                number(env, "BUDGET_PORT", 0, 65_535).map(Long::intValue).orElse(DEFAULT_PORT),
                text(env, "BUDGET_DATA_DIR").map(Path::of).orElseGet(() -> defaultDataDir(env)),
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
                        .or(() -> text(env, "WINDIR").map(windows -> Path.of(windows, "Fonts"))),
                text(env, "BUDGET_DOWNLOADS_DIR").map(Path::of)
                        .or(() -> text(env, "USERPROFILE").map(home -> Path.of(home, "Downloads"))));
    }

    /** %APPDATA%\Budget on Windows; the XDG-style equivalent elsewhere (dev containers). */
    private static Path defaultDataDir(Map<String, String> env) {
        return text(env, "APPDATA")
                .map(appData -> Path.of(appData, APP_FOLDER))
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".local", "share", APP_FOLDER));
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
