package org.github.lscoughlin.jeorje.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads {@code transcript.jsonl}. Only turns with a {@code turn_end} line are complete. */
public final class TranscriptReader {
    private static final Logger log = LoggerFactory.getLogger(TranscriptReader.class);

    /** A complete turn and the time it ended. */
    public record TimedTurn(Turn turn, Instant timestamp) {}

    private TranscriptReader() {}

    /** Reads the complete turns in file order. Torn or unreadable lines and incomplete turns are skipped. */
    public static List<TimedTurn> read(Path file) {
        if (!Files.exists(file)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var pending = new HashMap<String, List<Message>>();
        var out = new ArrayList<TimedTurn>();
        for (var line : lines) {
            if (line.isBlank()) continue;
            try {
                var node = TranscriptWriter.MAPPER.readTree(line);
                var key = node.get("agent").asString() + "\u0000" + node.get("turn").asInt();
                switch (node.get("type").asString()) {
                    case "message" -> pending.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(TranscriptWriter.MAPPER.treeToValue(node.get("message"), Message.class));
                    case "turn_end" -> {
                        var messages = pending.remove(key);
                        out.add(new TimedTurn(new Turn(new AgentId(node.get("agent").asString()),
                            node.get("turn").asInt(), messages == null ? List.of() : messages),
                            Instant.parse(node.get("ts").asString())));
                    }
                    default -> { }
                }
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "skipping unreadable transcript line: " + e.getMessage());
            }
        }
        return out;
    }
}
