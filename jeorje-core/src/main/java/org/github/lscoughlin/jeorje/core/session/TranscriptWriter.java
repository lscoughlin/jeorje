package org.github.lscoughlin.jeorje.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.Turn;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends turns to {@code transcript.jsonl}, one JSON object per line. A turn is written as message
 * lines followed by a {@code turn_end} line, in one write, and then forced to disk.
 */
public final class TranscriptWriter implements AutoCloseable {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final FileChannel channel;
    private final SecretScrubber scrubber;

    public TranscriptWriter(Path file) {
        this(file, null);
    }

    /** @param scrubber removes secrets from every text before it is written, or null for no scrubbing */
    public TranscriptWriter(Path file, SecretScrubber scrubber) {
        this.scrubber = scrubber;
        try {
            var parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
            repairTail(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A torn last line from an earlier crash gets a line break, so new lines start clean. */
    private void repairTail(Path file) throws IOException {
        var size = Files.size(file);
        if (size == 0) return;
        try (var in = FileChannel.open(file, StandardOpenOption.READ)) {
            var one = ByteBuffer.allocate(1);
            in.read(one, size - 1);
            if (one.get(0) != '\n') {
                channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
                channel.force(true);
            }
        }
    }

    /** Writes a finished turn and forces it to disk before it returns. */
    public synchronized void writeTurn(Turn turn, Instant timestamp) {
        var sb = new StringBuilder();
        for (var message : turn.messages()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "message");
            node.put("agent", turn.agent().value());
            node.put("turn", turn.number());
            node.put("ts", timestamp.toString());
            JsonNode tree = MAPPER.valueToTree(message);
            node.set("message", scrubber == null ? tree : scrubber.scrubTree(tree));
            sb.append(MAPPER.writeValueAsString(node)).append('\n');
        }
        ObjectNode end = MAPPER.createObjectNode();
        end.put("type", "turn_end");
        end.put("agent", turn.agent().value());
        end.put("turn", turn.number());
        end.put("ts", timestamp.toString());
        sb.append(MAPPER.writeValueAsString(end)).append('\n');
        try {
            var buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) channel.write(buf);
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
