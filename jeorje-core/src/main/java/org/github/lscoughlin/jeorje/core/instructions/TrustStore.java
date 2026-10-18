package org.github.lscoughlin.jeorje.core.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Stores trusted projects in {@code ${HOME}/.jeorje/trust.yaml}, by real path.
 * A git worktree inherits the trust of its main project.
 */
public final class TrustStore {

    private static final Logger log = LoggerFactory.getLogger(TrustStore.class);
    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    private final Path file;

    public TrustStore(Path home) {
        this.file = home.resolve(".jeorje").resolve("trust.yaml");
    }

    public Path file() {
        return file;
    }

    /** True if the project (or the main project of a worktree) is trusted. */
    public boolean isTrusted(Path project) {
        return load().contains(realPath(mainProject(project)));
    }

    /**
     * The trust decision for a run. The flags yes and yolo never set trust.
     * Only the stored trust or the explicit trust flag can.
     */
    public boolean isTrustedForRun(Path project, boolean trustFlag, boolean yes, boolean yolo) {
        return trustFlag || isTrusted(project);
    }

    public void trust(Path project) {
        var set = load();
        if (set.add(realPath(mainProject(project)))) {
            save(set);
        }
    }

    public void revoke(Path project) {
        var set = load();
        if (set.remove(realPath(mainProject(project)))) {
            save(set);
        }
    }

    public List<String> trustedProjects() {
        return List.copyOf(load());
    }

    /** For a git worktree, the main project directory. Otherwise the path itself. */
    public static Path mainProject(Path project) {
        var git = project.resolve(".git");
        if (Files.isRegularFile(git)) {
            try {
                var line = Files.readString(git).lines().filter(l -> l.startsWith("gitdir:")).findFirst();
                if (line.isPresent()) {
                    var gitDir = project.resolve(line.get().substring("gitdir:".length()).strip()).normalize();
                    // gitDir is <main>/.git/worktrees/<name>
                    var worktrees = gitDir.getParent();
                    if (worktrees != null && worktrees.getFileName() != null
                            && worktrees.getFileName().toString().equals("worktrees")
                            && worktrees.getParent() != null && worktrees.getParent().getParent() != null) {
                        return worktrees.getParent().getParent();
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.atDebug().log(() -> "Cannot read .git file of " + project + ": " + e.getMessage());
            }
        }
        return project;
    }

    private static String realPath(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    private Set<String> load() {
        var out = new LinkedHashSet<String>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            var tree = YAML.readTree(Files.readString(file));
            var projects = tree.get("projects");
            if (projects != null && projects.isArray()) {
                projects.forEach(n -> out.add(n.asString()));
            }
        } catch (IOException | RuntimeException e) {
            log.atWarn().log(() -> "Cannot read " + file + ": " + e.getMessage());
        }
        return out;
    }

    private void save(Set<String> projects) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, YAML.writeValueAsString(Map.of("projects", new ArrayList<>(projects))));
        } catch (IOException | RuntimeException e) {
            log.atWarn().log(() -> "Cannot write " + file + ": " + e.getMessage());
        }
    }
}
