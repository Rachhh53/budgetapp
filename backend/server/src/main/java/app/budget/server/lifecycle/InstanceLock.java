package app.budget.server.lifecycle;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Makes sure only one backend uses a data folder at a time, so two processes can never
 * write the same SQLite file. The operating system releases the lock if the process dies,
 * so a crash never leaves the app unable to start.
 */
public final class InstanceLock implements AutoCloseable {

    static final String LOCK_FILE = "budget.lock";

    /** Another backend already holds this data folder. */
    public static final class AlreadyRunningException extends RuntimeException {
        AlreadyRunningException() {
            super("Budget is already running for this data folder.");
        }
    }

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static InstanceLock acquire(Path dataDir) throws IOException {
        FileChannel channel = FileChannel.open(
                dataDir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new AlreadyRunningException();
            }
            return new InstanceLock(channel, lock);
        } catch (OverlappingFileLockException | AlreadyRunningException e) {
            // Overlapping = held by this same JVM; null = held by another process.
            channel.close();
            throw new AlreadyRunningException();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
