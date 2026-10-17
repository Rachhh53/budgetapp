package app.budget.server.auth;

import app.budget.server.db.Database;
import app.budget.server.lifecycle.InstanceLock;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import java.nio.file.Path;
import java.time.Clock;

/**
 * {@code --reset-pin}: the way back in when both the PIN and the recovery code are lost
 * (spec §7). It removes the PIN, and phone access with it, and touches nothing else.
 *
 * <p>It runs only from this PC's command line, with the app closed, so whoever uses it could
 * already read the data file itself. It is deliberately not reachable over HTTP.
 */
public final class PinReset {

    public static final String FLAG = "--reset-pin";

    public static final String DONE =
            "The PIN has been removed. Budget will open without one; you can set a new PIN in Settings.";
    public static final String PHONE_ACCESS_OFF =
            "Phone access has been turned off too; turn it on again in Settings once there is a new PIN.";
    public static final String NOTHING_TO_DO = "No PIN was set, so nothing was changed.";
    public static final String CLOSE_FIRST = "Budget is open. Please close it, then run this again.";

    private PinReset() {}

    /**
     * @return what to tell the person who ran it
     * @throws InstanceLock.AlreadyRunningException when the app is open on this data folder
     */
    public static String run(Path dataDir) throws Exception {
        // The same lock the app takes: the PIN is never removed underneath a running app.
        try (InstanceLock ignored = InstanceLock.acquire(dataDir);
                Database database = Database.open(dataDir)) {
            AuthRepository auth = new AuthRepository(database.jdbi());
            if (!auth.read().pinSet()) {
                return NOTHING_TO_DO;
            }
            auth.clearPin();
            // Phone access needs a PIN (spec §10, Phase 12), so it goes off with it.
            SettingsRepository settings = new SettingsRepository(database.jdbi(), Clock.systemDefaultZone());
            boolean phoneAccessWasOn = !settings.get(SettingKey.PHONE_ACCESS_ADDRESS).isEmpty();
            settings.put(SettingKey.PHONE_ACCESS_ADDRESS, "");
            return phoneAccessWasOn ? DONE + " " + PHONE_ACCESS_OFF : DONE;
        }
    }
}
