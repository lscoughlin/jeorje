package org.github.lscoughlin.jeorje.tools.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.state.FileState;
import org.github.lscoughlin.jeorje.core.state.FileStateTable;
import tools.jackson.databind.json.JsonMapper;

/** Shared code of the file tools. */
final class FileSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FileSupport() {}

    /** Thrown when a tool must refuse. The message goes to the model. */
    static final class Refused extends Exception {
        Refused(String message) { super(message); }
    }

    /** Resolves a path against the working directory. The policy can deny it. */
    static Path resolve(String path, ToolContext context, PermissionPolicy policy, String tool) throws Refused {
        if (path == null || path.isBlank()) throw new Refused("The path is empty.");
        var base = context.workingDirectory().toAbsolutePath().normalize();
        var resolved = base.resolve(path).normalize();
        if (policy != null) {
            var input = JSON.createObjectNode().put("path", resolved.toString());
            if (policy.check(new ToolUse("file-" + tool, tool, input)) == PermissionPolicy.Decision.DENY) {
                throw new Refused("Refused: the permission policy does not allow " + tool + " on " + path + ".");
            }
        }
        return resolved;
    }

    static String display(ToolContext context, Path file) {
        var base = context.workingDirectory().toAbsolutePath().normalize();
        return file.startsWith(base) && !file.equals(base)
                ? base.relativize(file).toString().replace('\\', '/') : file.toString();
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static FileState stateOf(Path file, byte[] bytes) throws IOException {
        return new FileState(Files.getLastModifiedTime(file).toMillis(), hash(bytes));
    }

    /** Writes the content to a temporary file in the same directory and moves it into place. */
    static void atomicWrite(Path file, String content) throws IOException {
        var dir = file.getParent();
        Files.createDirectories(dir);
        var temp = Files.createTempFile(dir, ".jeorje-", ".tmp");
        try {
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            if (Files.exists(file)) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // Permissions are best effort.
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The error text for a file that the agent may not change, or null if the change is allowed. */
    static String staleCheck(Path file, byte[] current, FileStateTable table, String shown) {
        var known = table.get(file);
        if (known.isEmpty()) {
            return "Refused: " + shown + " exists and you did not read it in this session. Read it with the read tool first.";
        }
        if (!known.get().hash().equals(hash(current))) {
            return "Refused: " + shown + " changed on disk after you read it. Read it again with the read tool first.";
        }
        return null;
    }
}
