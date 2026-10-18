package org.github.lscoughlin.jeorje.core.permissions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log. It writes one JSON line for each decision. A line has the fields {@code time},
 * {@code agent}, {@code tool}, {@code input}, {@code decision}, {@code source}, and
 * {@code status}. The scrubber removes secrets from the input.
 */
public final class AuditLog {

    static final int MAX_INPUT = 200;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final Path file;
    private final SecretScrubber scrubber;
    private final Clock clock;

    public AuditLog(Path file, SecretScrubber scrubber, Clock clock) {
        this.file = file;
        this.scrubber = scrubber;
        this.clock = clock;
    }

    /** The log of a session: {@code <cwd>/.jeorje/sessions/<sessionId>/audit.jsonl}. */
    public static AuditLog forSession(Path cwd, String sessionId, SecretScrubber scrubber, Clock clock) {
        return new AuditLog(cwd.resolve(".jeorje").resolve("sessions").resolve(sessionId)
                .resolve("audit.jsonl"), scrubber, clock);
    }

    public Path file() {
        return file;
    }

    /** Writes one line. The source is {@code rule}, {@code user}, or {@code yolo}. */
    public synchronized void record(AgentId agent, ToolUse action, Decision decision, String source,
                                    String status) {
        var line = new LinkedHashMap<String, Object>();
        line.put("time", clock.instant().toString());
        line.put("agent", agent == null ? null : agent.value());
        line.put("tool", action.tool());
        line.put("input", shorten(action));
        line.put("decision", decision.name());
        line.put("source", source);
        line.put("status", status);
        try {
            Files.createDirectories(file.getParent());
            var text = JSON.writeValueAsString(line) + "\n";
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String shorten(ToolUse action) {
        var input = action.input() == null ? "" : action.input().toString();
        var clean = scrubber.scrub(input);
        return clean.length() <= MAX_INPUT ? clean : clean.substring(0, MAX_INPUT) + "...";
    }
}
