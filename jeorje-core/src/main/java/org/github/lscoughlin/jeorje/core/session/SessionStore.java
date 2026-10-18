package org.github.lscoughlin.jeorje.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Creates, lists, and resumes the sessions in {@code <projectDir>/.jeorje/sessions}. */
public final class SessionStore {
    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final List<String> IGNORED = List.of("sessions/", "worktrees/");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path jeorjeDir;
    private final Clock clock;

    public SessionStore(Path projectDir) {
        this(projectDir, Clock.systemUTC());
    }

    public SessionStore(Path projectDir, Clock clock) {
        this.jeorjeDir = projectDir.resolve(".jeorje");
        this.clock = clock;
    }

    public Path sessionsDir() { return jeorjeDir.resolve("sessions"); }

    /** Creates a new session with a manifest on disk. The config secrets are not stored. */
    public Session create(JeorjeConfig config) {
        return create(snapshot(config));
    }

    /** Creates a new session with the given config snapshot. */
    public Session create(JsonNode configSnapshot) {
        ensureGitignore();
        var now = clock.instant();
        var id = STAMP.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8);
        var dir = sessionsDir().resolve(id);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var session = new Session(id, dir, SessionManifest.create(id, now, configSnapshot));
        session.save();
        log.atInfo().log(() -> "created session " + id);
        return session;
    }

    /** Lists the sessions, newest first. A directory without a readable manifest is skipped. */
    public List<Session> list() {
        var root = sessionsDir();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).map(this::load).flatMap(Optional::stream)
                .sorted(Comparator.comparing((Session s) -> s.manifest().updatedAt())
                    .thenComparing(Session::id).reversed())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads the most recently updated session of the project directory. */
    public Optional<Session> resumeLatest() {
        return list().stream().findFirst();
    }

    /** Loads one session by id. */
    public Optional<Session> resume(String id) {
        return load(sessionsDir().resolve(id));
    }

    private Optional<Session> load(Path dir) {
        var file = dir.resolve("manifest.json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(new Session(dir.getFileName().toString(), dir, SessionManifest.read(file)));
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "unreadable manifest in " + dir + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Creates {@code .jeorje/.gitignore}, or adds the missing entries to it. */
    public void ensureGitignore() {
        var file = jeorjeDir.resolve(".gitignore");
        try {
            Files.createDirectories(jeorjeDir);
            var existing = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.<String>of();
            var missing = IGNORED.stream().filter(e -> !existing.contains(e)).toList();
            if (missing.isEmpty()) return;
            var sb = new StringBuilder();
            if (!existing.isEmpty()) sb.append(String.join("\n", existing)).append('\n');
            missing.forEach(e -> sb.append(e).append('\n'));
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode snapshot(JeorjeConfig config) {
        var tree = MAPPER.valueToTree(config);
        if (tree.path("swarm") instanceof ObjectNode swarm && swarm.has("token")) swarm.put("token", "");
        return tree;
    }
}
