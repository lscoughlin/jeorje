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
import java.util.HashMap;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.agent.TranscriptSink;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Turn;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends turns to {@code transcript.jsonl}, one JSON object per line. A turn is written as message
 * lines followed by a {@code turn_end} line, in one write, and then forced to disk.
 */
public final class TranscriptWriter implements TranscriptSink, AutoCloseable {
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

    /** The number of message rows that {@link #writeMessage} wrote for each turn that still runs. */
    private final Map<String, Integer> running = new HashMap<>();

    private String messageRow(AgentId agent, int turn, int seq, Message message, Instant timestamp) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "message");
        node.put("agent", agent.value());
        node.put("turn", turn);
        // The reader starts a turn again at seq 0, so the rows of a turn that stopped half way do not stay in it.
        node.put("seq", seq);
        node.put("ts", timestamp.toString());
        JsonNode tree = MAPPER.valueToTree(message);
        node.set("message", scrubber == null ? tree : scrubber.scrubTree(tree));
        return MAPPER.writeValueAsString(node) + "\n";
    }

    private void append(String text, boolean force) {
        try {
            var buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) channel.write(buf);
            if (force) channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes one message of a turn that still runs. Messages must come in order, from the first message of the
     * turn. {@link #writeTurn} later skips the rows that exist already.
     */
    @Override
    public synchronized void writeMessage(AgentId agent, int turn, Message message, Instant timestamp) {
        var key = agent.value() + "\u0000" + turn;
        var seq = running.getOrDefault(key, 0);
        append(messageRow(agent, turn, seq, message, timestamp), false);
        running.put(key, seq + 1);
    }

    /** Writes a finished turn and forces it to disk before it returns. */
    @Override
    public synchronized void writeTurn(Turn turn, Instant timestamp) {
        var sb = new StringBuilder();
        var skip = running.getOrDefault(turn.agent().value() + "\u0000" + turn.number(), 0);
        var seq = 0;
        for (var message : turn.messages()) {
            if (seq >= skip) sb.append(messageRow(turn.agent(), turn.number(), seq, message, timestamp));
            seq++;
        }
        running.remove(turn.agent().value() + "\u0000" + turn.number());
        ObjectNode end = MAPPER.createObjectNode();
        end.put("type", "turn_end");
        end.put("agent", turn.agent().value());
        end.put("turn", turn.number());
        end.put("ts", timestamp.toString());
        sb.append(MAPPER.writeValueAsString(end)).append('\n');
        append(sb.toString(), true);
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
