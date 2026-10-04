package app.budget.server;

import app.budget.server.auth.SessionStore;
import app.budget.server.db.Database;
import app.budget.server.lifecycle.InstanceLock;
import app.budget.server.lifecycle.ParentWatch;
import java.nio.file.Files;
import java.time.Clock;
import org.slf4j.LoggerFactory;

/**
 * Backend entry point. Startup order matters:
 * lock the data folder → migrate the database → listen → announce {@code READY port=NNNN}.
 *
 * <p>The desktop shell reads stdout waiting for that one line, so nothing else may print there.
 */
public final class Main {

    static final int EXIT_STARTUP_FAILED = 1;
    static final int EXIT_ALREADY_RUNNING = 2;

    private Main() {}

    public static void main(String[] args) {
        try {
            Config config = Config.fromEnvironment(System.getenv());
            Files.createDirectories(config.dataDir());
            // Must be set before the first logger is created: logback.xml reads it to place the log file.
            System.setProperty("budget.logDir", config.dataDir().resolve("logs").toString());

            InstanceLock lock = InstanceLock.acquire(config.dataDir());
            Database database = Database.open(config.dataDir());
            Clock clock = Clock.systemDefaultZone();
            // Both "the shell asked us to stop" and "the shell died" end the same way; the
            // shutdown hook below then closes things down in order.
            Runnable exit = () -> System.exit(0);
            BudgetServer server = new BudgetServer(
                    config, database.jdbi(), new SessionStore(clock, config.sessionIdle()), clock, exit);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                try {
                    lock.close();
                } catch (Exception e) {
                    // The OS drops the lock when the process ends anyway.
                }
            }, "budget-stop"));

            int port = server.start();
            config.parentPid().ifPresent(pid -> ParentWatch.watch(pid, exit));
            System.out.println("READY port=" + port);
            System.out.flush();
        } catch (InstanceLock.AlreadyRunningException e) {
            System.err.println("ALREADY_RUNNING");
            System.exit(EXIT_ALREADY_RUNNING);
        } catch (Config.InvalidConfigException e) {
            System.err.println("STARTUP_FAILED " + e.getMessage());
            System.exit(EXIT_STARTUP_FAILED);
        } catch (Exception e) {
            LoggerFactory.getLogger(Main.class).error("Budget could not start", e);
            System.err.println("STARTUP_FAILED");
            System.exit(EXIT_STARTUP_FAILED);
        }
    }
}
