package app.budget.server.backups;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lists the folders on this PC, one level at a time, for the "Choose folder" dialog.
 *
 * <p>The UI talks to the backend only over HTTP, so there is no native folder dialog to open
 * (spec §2). This stands in for it: folder names only, never files or their contents, and
 * the routes that use it answer this PC alone.
 */
public final class FolderBrowser {

    /** @param path the full path, to ask for next or to choose */
    public record Folder(String name, String path) {}

    /**
     * @param path     the folder listed; {@code null} for the top, where the drives are
     * @param parent   the folder above it; {@code null} when that is the top
     * @param folders  what is inside, by name
     * @param writable whether backups could be saved here
     */
    public record Listing(String path, String parent, List<Folder> folders, boolean writable) {}

    /** More than anyone would scroll through, and a bound on how long one listing can take. */
    private static final int MOST_LISTED = 500;

    /** Characters Windows does not allow in a folder name. */
    private static final Pattern NAME = Pattern.compile("[^\\\\/:*?\"<>|\\p{Cntrl}]{1,100}");

    private final Path home;

    public FolderBrowser(Path home) {
        this.home = home;
    }

    /** The top: the usual places first, then every drive. */
    public Listing top() {
        List<Folder> folders = new ArrayList<>();
        for (String usual : List.of("Documents", "Desktop")) {
            Path folder = home.resolve(usual);
            if (Files.isDirectory(folder)) {
                folders.add(new Folder(usual, folder.toString()));
            }
        }
        for (File root : File.listRoots()) {
            folders.add(new Folder(root.getPath(), root.getPath()));
        }
        // A Mac has one root, "/"; its other drives (a USB stick, a backup disk) are the folders in /Volumes.
        // Only asked on a system whose paths start with "/": on Windows the same text is not a valid path.
        if (File.separatorChar == '/') {
            Path volumes = Path.of("/Volumes");
            if (Files.isDirectory(volumes)) {
                folders.add(new Folder("Drives", volumes.toString()));
            }
        }
        return new Listing(null, null, List.copyOf(folders), false);
    }

    /** @return empty when the path is not a folder that can be opened */
    public Optional<Listing> list(String path) {
        Path folder;
        try {
            folder = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        List<Folder> inside = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                if (inside.size() < MOST_LISTED && isOrdinaryFolder(child)) {
                    inside.add(new Folder(child.getFileName().toString(), child.toString()));
                }
            }
        } catch (IOException | SecurityException e) {
            // A folder Windows will not let us read: shown as empty rather than as an error.
            inside.clear();
        }
        inside.sort(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER));
        Path parent = folder.getParent();
        return Optional.of(new Listing(
                folder.toString(), parent == null ? null : parent.toString(), List.copyOf(inside), Files.isWritable(folder)));
    }

    /**
     * Makes a new folder inside {@code parent}.
     *
     * @return the new folder's path; empty when the name is not one Windows allows, the
     *         parent cannot be written to, or something of that name is already there
     */
    public Optional<String> create(String parent, String name) {
        if (parent == null || name == null) {
            return Optional.empty();
        }
        String trimmed = name.strip();
        // A name ending in a dot or made only of dots is one Windows quietly changes or refuses.
        if (!NAME.matcher(trimmed).matches() || trimmed.endsWith(".")) {
            return Optional.empty();
        }
        try {
            Path folder = Path.of(parent).toAbsolutePath().normalize();
            if (!Files.isDirectory(folder)) {
                return Optional.empty();
            }
            Path created = folder.resolve(trimmed);
            if (!folder.equals(created.getParent())) {
                return Optional.empty();
            }
            Files.createDirectory(created);
            return Optional.of(created.toString());
        } catch (IOException | InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static boolean isOrdinaryFolder(Path path) {
        try {
            return Files.isDirectory(path) && !Files.isHidden(path) && Files.isReadable(path);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
