package org.github.lscoughlin.jeorje.ui.syntax;

import java.nio.file.Path;
import java.util.List;

/**
 * Where {@link SyntaxCatalog} looks for syntax files.
 *
 * @param projectDir  {@code ${CWD}/.jeorje/syntax} (may be null)
 * @param userDir     {@code ${HOME}/.jeorje/syntax} (may be null)
 * @param configDirs  the directories of {@code editor.syntax.dirs}
 * @param systemDirs  the directories of an installed GNU nano (empty when the step is off)
 * @param embedded    true when the embedded set is the last step
 * @param cacheRoot   the directory that holds the extracted embedded sets
 * @param version     the Jeorje version, the name of the extraction directory
 */
public record SyntaxSettings(Path projectDir, Path userDir, List<Path> configDirs, List<Path> systemDirs,
                             boolean embedded, Path cacheRoot, String version) {

    public static final List<Path> NANO_DIRS = List.of(
            Path.of("/usr/share/nano"), Path.of("/usr/local/share/nano"), Path.of("/opt/homebrew/share/nano"));

    public SyntaxSettings {
        configDirs = List.copyOf(configDirs);
        systemDirs = List.copyOf(systemDirs);
    }

    /** The default settings for a working directory and a home directory. */
    public static SyntaxSettings defaults(Path cwd, Path home, String version) {
        return new SyntaxSettings(cwd.resolve(".jeorje/syntax"), home.resolve(".jeorje/syntax"), List.of(),
                NANO_DIRS, true, home.resolve(".cache/jeorje/syntax"), version);
    }

    public SyntaxSettings withConfigDirs(List<Path> dirs) {
        return new SyntaxSettings(projectDir, userDir, dirs, systemDirs, embedded, cacheRoot, version);
    }

    public SyntaxSettings withSystemDirs(List<Path> dirs) {
        return new SyntaxSettings(projectDir, userDir, configDirs, dirs, embedded, cacheRoot, version);
    }

    public SyntaxSettings withEmbedded(boolean on) {
        return new SyntaxSettings(projectDir, userDir, configDirs, systemDirs, on, cacheRoot, version);
    }
}
