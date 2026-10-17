package app.budget.server;

import app.budget.server.auth.PinReset;
import app.budget.server.db.Database;
import app.budget.server.lifecycle.InstanceLock;
import app.budget.server.lifecycle.ParentWatch;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/**
 * Backend entry point. Startup order matters:
 * lock the data folder → migrate the database → listen → announce {@code READY port=NNNN}.
 *
 * <p>The desktop shell reads stdout waiting for that one line, so nothing else may print there.
 *
 * <p>Started by hand with {@code --reset-pin}, it removes a forgotten PIN and exits instead
 * (spec §7). That is only possible at this PC's own command line, never over HTTP.
 */
public final class Main {

    static final int EXIT_STARTUP_FAILED = 1;
    static final int EXIT_ALREADY_RUNNING = 2;

    private Main() {}

    /** A little after startup, so opening the app is never held up by it. */
    private static final long FIRST_BACKUP_CHECK_SECONDS = 30;
    /** Often enough to catch midnight going by while the app stays open. */
    private static final long BACKUP_CHECK_EVERY_SECONDS = TimeUnit.HOURS.toSeconds(1);
    /** Soon enough that a phone gets in shortly after the VPN or Wi-Fi comes back. */
    private static final long PHONE_CHECK_EVERY_SECONDS = 30;

    /**
     * The daily backup (spec §7): made the first time the check runs on a calendar day, so
     * once shortly after the app opens, and again if it is left open past midnight.
     */
    private static void startDailyBackups(BudgetServer server) {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "budget-backup");
            // Never keeps the app alive: closing the window ends it whatever it is doing.
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(
                server.backups()::backUpIfDue, FIRST_BACKUP_CHECK_SECONDS, BACKUP_CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
        // Next year's plan takes effect on its first day (spec §4.10): checked straight away, so it is in
        // force by the time the first screen asks for anything, and again as often as the backup.
        timer.scheduleWithFixedDelay(server::activateDuePlan, 0, BACKUP_CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
        // Phone access follows the networks as they come and go: a VPN started after Budget, a laptop
        // back on the home Wi-Fi, a PC waking from sleep. Cheap: it only lists this PC's own networks.
        timer.scheduleWithFixedDelay(
                server::keepPhoneAccessUp, PHONE_CHECK_EVERY_SECONDS, PHONE_CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    private static long msSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    public static void main(String[] args) {
        boolean resetPin = Arrays.asList(args).contains(PinReset.FLAG);
        try {
            Config config = Config.fromEnvironment(System.getenv());
            Files.createDirectories(config.dataDir());
            // Must be set before the first logger is created: logback.xml reads it to place the log file.
            System.setProperty("budget.logDir", config.dataDir().resolve("logs").toString());

            if (resetPin) {
                // Someone is running this in a terminal, so here stdout is theirs to read.
                System.out.println(PinReset.run(config.dataDir()));
                return;
            }

            long jvmUpMs = ManagementFactory.getRuntimeMXBean().getUptime();
            long started = System.nanoTime();
            InstanceLock lock = InstanceLock.acquire(config.dataDir());
            Database database = Database.open(config.dataDir());
            long databaseMs = msSince(started);
            Clock clock = Clock.systemDefaultZone();
            // Both "the shell asked us to stop" and "the shell died" end the same way; the
            // shutdown hook below then closes things down in order.
            Runnable exit = () -> System.exit(0);
            BudgetServer server = new BudgetServer(config, database, clock, exit);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                database.close();
                try {
                    lock.close();
                } catch (Exception e) {
                    // The OS drops the lock when the process ends anyway.
                }
            }, "budget-stop"));

            long builtMs = msSince(started);
            int port = server.start();
            config.parentPid().ifPresent(pid -> ParentWatch.watch(pid, exit));
            startDailyBackups(server);
            System.out.println("READY port=" + port);
            System.out.flush();
            // After READY, the shell keeps reading: what it needs to know about the tray and Start with Windows.
            server.connectShell(line -> {
                System.out.println(line);
                System.out.flush();
            });
            // Times only, no data: where a slow start went, read from the local log.
            LoggerFactory.getLogger(Main.class).info(
                    "Started: Java up after {} ms, database {} ms, routes {} ms, listening {} ms",
                    jvmUpMs, databaseMs, builtMs - databaseMs, msSince(started) - builtMs);
        } catch (InstanceLock.AlreadyRunningException e) {
            System.err.println(resetPin ? PinReset.CLOSE_FIRST : "ALREADY_RUNNING");
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
