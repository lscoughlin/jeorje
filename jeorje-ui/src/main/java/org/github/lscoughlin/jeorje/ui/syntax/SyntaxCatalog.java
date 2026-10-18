package org.github.lscoughlin.jeorje.ui.syntax;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds nanorc syntax files, reads the {@code syntax} lines, and selects a syntax for a file name.
 *
 * <p>Search order: project, user, config directories, system nano directories, embedded set.
 * The first directory that defines a syntax name wins.
 */
public final class SyntaxCatalog {

    private static final Logger log = LoggerFactory.getLogger(SyntaxCatalog.class);

    static final String RESOURCE_DIR = "/org/github/lscoughlin/jeorje/ui/syntax";

    private final Map<String, SyntaxInfo> byName;
    private final List<String> warnings;
    private final List<Path> searchedDirs;

    private SyntaxCatalog(Map<String, SyntaxInfo> byName, List<String> warnings, List<Path> searchedDirs) {
        this.byName = byName;
        this.warnings = List.copyOf(warnings);
        this.searchedDirs = List.copyOf(searchedDirs);
    }

    /** Builds the catalog. Extracts the embedded set when the settings ask for it. */
    public static SyntaxCatalog load(SyntaxSettings settings) {
        var dirs = new ArrayList<Path>();
        if (settings.projectDir() != null) {
            dirs.add(settings.projectDir());
        }
        if (settings.userDir() != null) {
            dirs.add(settings.userDir());
        }
        dirs.addAll(settings.configDirs());
        dirs.addAll(settings.systemDirs());
        if (settings.embedded()) {
            try {
                dirs.add(extractEmbedded(settings.cacheRoot(), settings.version()));
            } catch (IOException | RuntimeException e) {
                log.atWarn().log(() -> "Cannot extract the embedded syntax set: " + e);
            }
        }
        var byName = new LinkedHashMap<String, SyntaxInfo>();
        var warnings = new ArrayList<String>();
        for (var dir : dirs) {
            readDir(dir, byName, warnings);
        }
        return new SyntaxCatalog(byName, warnings, dirs);
    }

    /** All syntaxes, in search order. */
    public List<SyntaxInfo> syntaxes() {
        return List.copyOf(byName.values());
    }

    public Optional<SyntaxInfo> byName(String name) {
        var exact = byName.get(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        return byName.values().stream().filter(info -> info.name().equalsIgnoreCase(name)).findFirst();
    }

    /** The warnings that the catalog produced while it loaded files. */
    public List<String> warnings() {
        return warnings;
    }

    /** The directories that the catalog read, in search order. */
    public List<Path> searchedDirs() {
        return searchedDirs;
    }

    /**
     * Selects a syntax for a file name or path. A pattern that matches at the end of the name wins over a
     * pattern that matches only inside the name. Inside each group, the search order decides.
     */
    public Optional<SyntaxInfo> forFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return Optional.empty();
        }
        var normalized = fileName.replace('\\', '/');
        var base = normalized.substring(normalized.lastIndexOf('/') + 1);
        SyntaxInfo inner = null;
        for (var info : byName.values()) {
            var end = info.matchEnd(normalized);
            if (end < 0) {
                end = info.matchEnd(base);
                if (end < 0) {
                    continue;
                }
                if (end == base.length()) {
                    return Optional.of(info);
                }
            } else if (end == normalized.length()) {
                return Optional.of(info);
            }
            if (inner == null) {
                inner = info;
            }
        }
        return Optional.ofNullable(inner);
    }

    // ---- reading ----

    private static void readDir(Path dir, Map<String, SyntaxInfo> byName, List<String> warnings) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".nanorc"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            warn(warnings, "Cannot list the syntax directory " + dir + ": " + e);
            return;
        }
        // Names that an earlier directory defined win. Names inside this directory: the first file wins.
        var known = new java.util.HashSet<>(byName.keySet());
        for (var file : files) {
            readFile(file, known, byName, warnings);
        }
    }

    private static void readFile(Path file, java.util.Set<String> known, Map<String, SyntaxInfo> byName,
                                 List<String> warnings) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            warn(warnings, "Skipping the syntax file " + file + ": " + e);
            return;
        }
        var found = new ArrayList<SyntaxInfo>();
        var usesHeaderOrMagic = false;
        var lineNo = 0;
        for (var raw : lines) {
            lineNo++;
            var line = raw.strip();
            if (line.startsWith("syntax") && line.length() > 6 && Character.isWhitespace(line.charAt(6))) {
                var tokens = tokens(line.substring(6));
                if (tokens.isEmpty()) {
                    warn(warnings, "Bad syntax line in " + file + " at line " + lineNo);
                    continue;
                }
                var patterns = new ArrayList<Pattern>();
                for (var regex : tokens.subList(1, tokens.size())) {
                    try {
                        patterns.add(Pattern.compile(toJavaRegex(regex)));
                    } catch (PatternSyntaxException e) {
                        warn(warnings, "Bad file name pattern in " + file + " at line " + lineNo + ": " + regex);
                    }
                }
                // JLine reads only the first syntax of a file, so the catalog lists only that one.
                if (found.isEmpty()) {
                    found.add(new SyntaxInfo(tokens.getFirst(), file, patterns));
                }
            } else if (line.startsWith("header") || line.startsWith("magic")) {
                usesHeaderOrMagic = true;
            }
        }
        if (found.isEmpty()) {
            warn(warnings, "Skipping the syntax file " + file + ": it has no syntax line");
            return;
        }
        if (usesHeaderOrMagic) {
            warn(warnings, "The syntax file " + file + " uses header or magic. JLine ignores these keywords.");
        }
        for (var info : found) {
            var key = info.name();
            if (!known.contains(key) && !byName.containsKey(key)) {
                byName.put(key, info);
            }
        }
    }

    private static void warn(List<String> warnings, String message) {
        warnings.add(message);
        log.atWarn().log(() -> message);
    }

    /** Splits a nanorc argument list. A quoted token ends at a quote that precedes white space or the end. */
    static List<String> tokens(String text) {
        var result = new ArrayList<String>();
        var i = 0;
        var n = text.length();
        while (i < n) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
            } else if (text.charAt(i) == '"') {
                var j = i + 1;
                while (j < n && !(text.charAt(j) == '"' && (j + 1 == n || Character.isWhitespace(text.charAt(j + 1))))) {
                    j++;
                }
                result.add(text.substring(i + 1, Math.min(j, n)));
                i = j + 1;
            } else {
                var j = i;
                while (j < n && !Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                result.add(text.substring(i, j));
                i = j;
            }
        }
        return result;
    }

    /** Converts POSIX character classes to the Java form. */
    static String toJavaRegex(String regex) {
        return regex.replace("[:digit:]", "\\p{Digit}").replace("[:alpha:]", "\\p{Alpha}")
                .replace("[:alnum:]", "\\p{Alnum}").replace("[:space:]", "\\s").replace("[:upper:]", "\\p{Upper}")
                .replace("[:lower:]", "\\p{Lower}").replace("[:punct:]", "\\p{Punct}");
    }

    // ---- extraction ----

    /**
     * Writes the embedded files to {@code cacheRoot/version}. The write goes to a temporary directory and a
     * rename makes it final. An existing directory is reused. Other versions are removed.
     */
    static Path extractEmbedded(Path cacheRoot, String version) throws IOException {
        var target = cacheRoot.resolve(version);
        Files.createDirectories(cacheRoot);
        if (!Files.isDirectory(target)) {
            var tmp = Files.createTempDirectory(cacheRoot, ".tmp-" + version + "-");
            try {
                copyEmbeddedTo(tmp);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target);
                }
            } catch (IOException | RuntimeException e) {
                if (!Files.isDirectory(target)) {
                    deleteTree(tmp);
                    throw e;
                }
                // Another process won the race. Its directory is complete.
                deleteTree(tmp);
            }
        }
        removeOthers(cacheRoot, version);
        return target;
    }

    private static void removeOthers(Path cacheRoot, String version) {
        try (var stream = Files.list(cacheRoot)) {
            for (var path : stream.toList()) {
                var name = path.getFileName().toString();
                if (name.equals(version) || !Files.isDirectory(path)) {
                    continue;
                }
                // A young temporary directory may belong to a process that is still writing.
                if (name.startsWith(".tmp-") && isYoung(path)) {
                    continue;
                }
                deleteTree(path);
            }
        } catch (IOException e) {
            log.atWarn().log(() -> "Cannot clean the syntax cache " + cacheRoot + ": " + e);
        }
    }

    private static boolean isYoung(Path path) throws IOException {
        var age = java.time.Duration.between(Files.getLastModifiedTime(path).toInstant(), java.time.Instant.now());
        return age.compareTo(java.time.Duration.ofHours(1)) < 0;
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.atWarn().log(() -> "Cannot delete " + root + ": " + e);
        }
    }

    private static void copyEmbeddedTo(Path dest) throws IOException {
        // Test classes can share the package directory, so the lookup uses a marker file.
        var url = SyntaxCatalog.class.getResource(RESOURCE_DIR + "/SOURCES.md");
        if (url == null) {
            throw new IOException("The embedded syntax directory is missing");
        }
        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        if ("jar".equals(uri.getScheme())) {
            try (var fs = FileSystems.newFileSystem(uri, Map.of())) {
                copyDir(fs.getPath(RESOURCE_DIR), dest);
            } catch (java.nio.file.FileSystemAlreadyExistsException e) {
                copyDir(FileSystems.getFileSystem(uri).getPath(RESOURCE_DIR), dest);
            }
        } else {
            copyDir(Path.of(uri).getParent(), dest);
        }
    }

    private static void copyDir(Path source, Path dest) throws IOException {
        try (Stream<Path> stream = Files.list(source)) {
            for (var file : stream.toList()) {
                var name = file.getFileName().toString();
                if (!name.endsWith(".nanorc")) {
                    continue;
                }
                var parts = splitNanorc(name, Files.readAllLines(file, StandardCharsets.UTF_8));
                if (parts.size() == 1 && parts.containsKey(name)) {
                    Files.copy(file, dest.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    for (var part : parts.entrySet()) {
                        Files.write(dest.resolve(part.getKey()), part.getValue(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
    }

    /**
     * Splits a nanorc file that has several {@code syntax} lines into one file for each syntax. JLine reads
     * only the first {@code syntax} line of a file. Every part keeps the header comment (the lines before the
     * first {@code syntax} line). The {@code color} lines stay under their own syntax. A file with one
     * syntax line, or none, stays as it is under its own name.
     *
     * @return the new file names (in file order) with their lines
     */
    static Map<String, List<String>> splitNanorc(String fileName, List<String> lines) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i < lines.size(); i++) {
            if (isSyntaxLine(lines.get(i))) {
                starts.add(i);
            }
        }
        var result = new LinkedHashMap<String, List<String>>();
        if (starts.size() < 2) {
            result.put(fileName, lines);
            return result;
        }
        var header = lines.subList(0, starts.getFirst());
        var base = fileName.substring(0, fileName.length() - ".nanorc".length());
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) : lines.size();
            var body = lines.subList(starts.get(k), end);
            var tokens = tokens(lines.get(starts.get(k)).strip().substring(6));
            var syntaxName = tokens.isEmpty() ? "part" + k : tokens.getFirst();
            var part = new ArrayList<String>(header);
            part.addAll(body);
            var partName = base + "." + syntaxName.replaceAll("[^A-Za-z0-9_-]", "_") + ".nanorc";
            while (result.containsKey(partName)) {
                partName = partName.substring(0, partName.length() - ".nanorc".length()) + "_" + k + ".nanorc";
            }
            result.put(partName, part);
        }
        return result;
    }

    private static boolean isSyntaxLine(String raw) {
        var line = raw.strip();
        return line.startsWith("syntax") && line.length() > 6 && Character.isWhitespace(line.charAt(6));
    }

    /** The version of the running Jeorje build, or {@code dev}. */
    public static String currentVersion() {
        var version = SyntaxCatalog.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
