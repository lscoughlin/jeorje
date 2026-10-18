package org.github.lscoughlin.jeorje.core.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@code AGENTS.md} files: user files, the project file, and directory files.
 * The loader checks the modification time on each call, so an edit applies on the next request.
 */
public final class InstructionsLoader {

    private static final Logger log = LoggerFactory.getLogger(InstructionsLoader.class);
    private static final String FILE_NAME = "AGENTS.md";
    public static final int DEFAULT_MAX_BYTES = 32_768;
    public static final int DEFAULT_MAX_TOTAL_BYTES = 65_536;

    /** One loaded file. */
    public record Instruction(Level level, Path path, String text, boolean truncated) {
    }

    /** Level of a file. A later level is more specific. */
    public enum Level { USER, PROJECT, DIRECTORY }

    private record Cached(long modified, long size, String text) {
    }

    private final List<Path> userFiles;
    private final int maxBytes;
    private final int maxTotalBytes;
    private final Map<Path, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicInteger diskReads = new AtomicInteger();

    public InstructionsLoader(Path home, int maxBytes, int maxTotalBytes) {
        this(List.of(home.resolve(".jeorje").resolve(FILE_NAME), home.resolve(".agents").resolve(FILE_NAME)),
                maxBytes, maxTotalBytes);
    }

    public InstructionsLoader(List<Path> userFiles, int maxBytes, int maxTotalBytes) {
        this.userFiles = List.copyOf(userFiles);
        this.maxBytes = maxBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    /** Number of times a file was read from disk. Used to check the reload rule. */
    public int diskReads() {
        return diskReads.get();
    }

    /** The user files and the project file, in the order Jeorje puts them in the system prompt. */
    public List<Instruction> systemPromptFiles(Path workspaceRoot) {
        var out = new ArrayList<Instruction>();
        for (var file : userFiles) {
            read(Level.USER, file).ifPresent(out::add);
        }
        read(Level.PROJECT, workspaceRoot.resolve(FILE_NAME)).ifPresent(out::add);
        return out;
    }

    /** The system-prompt text. The total is limited by maxTotalBytes. Returns an empty text if there is no file. */
    public String systemPrompt(Path workspaceRoot) {
        var files = systemPromptFiles(workspaceRoot);
        var sb = new StringBuilder();
        var remaining = maxTotalBytes;
        for (var file : files) {
            if (remaining <= 0) {
                sb.append("[Further instructions omitted: the total size limit is reached.]\n");
                break;
            }
            var section = "Instructions from " + file.path() + " (" + file.level().name().toLowerCase() + "):\n"
                    + file.text() + "\n\n";
            var cut = TextFiles.cut(section, remaining, "[Instructions cut: the total size limit is reached.]");
            sb.append(cut);
            remaining -= cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The AGENTS.md files on the path below the root down to the directory (the root file is not included).
     * The closest file is last.
     */
    public List<Instruction> directoryFiles(Path workspaceRoot, Path directory) {
        var root = workspaceRoot.toAbsolutePath().normalize();
        var dir = directory.toAbsolutePath().normalize();
        var out = new ArrayList<Instruction>();
        if (!dir.startsWith(root) || dir.equals(root)) {
            return out;
        }
        var chain = new ArrayList<Path>();
        for (var p = dir; p != null && !p.equals(root); p = p.getParent()) {
            chain.addFirst(p);
        }
        for (var p : chain) {
            read(Level.DIRECTORY, p.resolve(FILE_NAME)).ifPresent(out::add);
        }
        return out;
    }

    /** All files that apply to a directory, in the order Jeorje uses them (the more specific last). */
    public List<Instruction> applicable(Path workspaceRoot, Path directory) {
        var out = new ArrayList<>(systemPromptFiles(workspaceRoot));
        out.addAll(directoryFiles(workspaceRoot, directory));
        return out;
    }

    private java.util.Optional<Instruction> read(Level level, Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                cache.remove(file);
                return java.util.Optional.empty();
            }
            var modified = Files.getLastModifiedTime(file).toMillis();
            var size = Files.size(file);
            var cached = cache.get(file);
            if (cached == null || cached.modified() != modified || cached.size() != size) {
                diskReads.incrementAndGet();
                var raw = TextFiles.read(file);
                var text = TextFiles.cut(raw, maxBytes, "[This file is cut at " + maxBytes + " bytes.]");
                cached = new Cached(modified, size, text);
                cache.put(file, cached);
            }
            var truncated = size > maxBytes;
            return java.util.Optional.of(new Instruction(level, file, cached.text(), truncated));
        } catch (IOException | RuntimeException e) {
            log.atWarn().log(() -> "Cannot read " + file + ": " + e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
