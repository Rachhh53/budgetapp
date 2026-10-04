package app.budget.server.lifecycle;

/**
 * Stops the backend when the desktop shell that started it goes away — including when the
 * shell crashes and never gets to ask politely. No stray server is ever left running.
 */
public final class ParentWatch {

    private ParentWatch() {}

    /** Runs {@code onParentExit} once the process {@code parentPid} exits (or right away if it is already gone). */
    public static void watch(long parentPid, Runnable onParentExit) {
        ProcessHandle.of(parentPid)
                .ifPresentOrElse(parent -> parent.onExit().thenRun(onParentExit), onParentExit);
    }
}
