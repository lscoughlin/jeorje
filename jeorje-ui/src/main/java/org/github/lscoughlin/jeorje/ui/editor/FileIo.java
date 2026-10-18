package org.github.lscoughlin.jeorje.ui.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.github.lscoughlin.jeorje.core.diff.SplitText;
import org.github.lscoughlin.jeorje.core.diff.TextDiffException;
import org.github.lscoughlin.jeorje.core.state.FileState;

/** Reads and writes the files of the editor. The write is atomic, like the write of the {@code write} tool. */
final class FileIo {

    /**
     * A file that the editor read.
     *
     * @param text the text with {@code \n} as the only line end
     * @param eol the dominant line end of the file
     * @param state the state of the file on disk, or null for a file that does not exist
     * @param bytes the size of the file
     */
    record Loaded(String text, String eol, FileState state, long bytes) { }

    private FileIo() {
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads a file. A path that does not exist gives an empty text. */
    static Loaded load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new Loaded("", "\n", null, 0);
        }
        var bytes = Files.readAllBytes(path);
        var state = new FileState(Files.getLastModifiedTime(path).toMillis(), hash(bytes));
        try {
            var split = SplitText.decode(bytes);
            var normalized = SplitText.ofLines(split.lines(), "\n", split.finalNewline());
            return new Loaded(normalized.render(), split.eol(), state, bytes.length);
        } catch (TextDiffException e) {
            throw new IOException("Cannot edit " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The state of a file on disk, or null when it does not exist or cannot be read. */
    static FileState stateOf(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            var bytes = Files.readAllBytes(path);
            return new FileState(Files.getLastModifiedTime(path).toMillis(), hash(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    /** Puts the line end of the file back into the text of the buffer. */
    static String encode(String text, String eol) {
        var split = SplitText.split(text);
        return SplitText.ofLines(split.lines(), eol, split.finalNewline()).render();
    }

    /** Writes the text to a temporary file in the same directory and moves it into place. */
    static FileState save(Path path, String text, String eol) throws IOException {
        var bytes = encode(text, eol).getBytes(StandardCharsets.UTF_8);
        var dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        var temp = Files.createTempFile(dir, ".jeorje-", ".tmp");
        try {
            Files.write(temp, bytes);
            if (Files.exists(path)) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(path));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // Permissions are best effort.
                }
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return new FileState(Files.getLastModifiedTime(path).toMillis(), hash(bytes));
    }
}
