package app.budget.server.lifecycle;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tells the desktop shell what it needs to know about the settings that are its business, over the
 * same private pipe (the backend's stdout) it reads {@code READY port=NNNN} from. One line, sent once
 * after READY and again whenever either value changes:
 *
 * <pre>SHELL keep_in_tray=true start_with_windows=false</pre>
 *
 * <ul>
 *   <li>{@code keep_in_tray}: phone access is on, so closing the window hides it to the tray and the
 *       backend keeps running for the phone (spec §10, Phase 12).
 *   <li>{@code start_with_windows}: the shell writes or removes its Windows startup entry to match.
 * </ul>
 *
 * <p>The pipe goes only to the shell that started this process, so nothing on the network or in a
 * web page can see or send these lines, and no endpoint is needed for them.
 */
public final class ShellLink {

    static final String PREFIX = "SHELL ";

    private final BooleanSupplier keepInTray;
    private final BooleanSupplier startWithWindows;
    private Consumer<String> shell;
    private String lastSent;

    public ShellLink(BooleanSupplier keepInTray, BooleanSupplier startWithWindows) {
        this.keepInTray = keepInTray;
        this.startWithWindows = startWithWindows;
    }

    /** Starts telling {@code shell}, with the state as it is now. Called once READY has been sent. */
    public synchronized void connect(Consumer<String> shell) {
        this.shell = shell;
        lastSent = null;
        update();
    }

    /**
     * Sends the state again if it has changed since last time; nothing otherwise, and nothing before
     * {@link #connect} (a test, or a run without a shell). Safe to call after every change.
     */
    public synchronized void update() {
        if (shell == null) {
            return;
        }
        String line = PREFIX + "keep_in_tray=" + keepInTray.getAsBoolean()
                + " start_with_windows=" + startWithWindows.getAsBoolean();
        if (!line.equals(lastSent)) {
            shell.accept(line);
            lastSent = line;
        }
    }
}
