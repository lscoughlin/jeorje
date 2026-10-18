package org.github.lscoughlin.jeorje.testkit;

import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A {@link Tool} with a set result. It can also throw, or block until it is cancelled. */
public final class FakeTool implements Tool {
    private enum Mode { RESULT, THROW, BLOCK }

    private final String name;
    private final Mode mode;
    private final ToolResultData result;
    private final Exception failure;
    private final boolean readOnly;
    private final List<JsonNode> inputs = new CopyOnWriteArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);

    private FakeTool(String name, Mode mode, ToolResultData result, Exception failure, boolean readOnly) {
        this.name = name;
        this.mode = mode;
        this.result = result;
        this.failure = failure;
        this.readOnly = readOnly;
    }

    public static FakeTool returning(String name, String output) {
        return new FakeTool(name, Mode.RESULT, ToolResultData.ok(output), null, true);
    }

    public static FakeTool returning(String name, ToolResultData result, boolean readOnly) {
        return new FakeTool(name, Mode.RESULT, result, null, readOnly);
    }

    public static FakeTool throwing(String name, Exception failure) {
        return new FakeTool(name, Mode.THROW, null, failure, true);
    }

    /** A tool that blocks until its context is cancelled, then throws a cancellation exception. */
    public static FakeTool blocking(String name) {
        return new FakeTool(name, Mode.BLOCK, null, null, true);
    }

    @Override
    public ToolDefinition definition() {
        var schema = JsonMapper.builder().build().createObjectNode().put("type", "object");
        return new ToolDefinition(name, "Fake tool " + name, schema);
    }

    @Override
    public boolean readOnly() { return readOnly; }

    @Override
    public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
        inputs.add(input);
        started.countDown();
        switch (mode) {
            case RESULT -> { return result; }
            case THROW -> throw failure;
            case BLOCK -> {
                var cancelled = new CountDownLatch(1);
                context.cancel().onCancel(cancelled::countDown);
                cancelled.await();
                throw new java.util.concurrent.CancellationException("cancelled");
            }
        }
        throw new IllegalStateException();
    }

    /** The inputs of all runs, in order. */
    public List<JsonNode> inputs() { return List.copyOf(inputs); }

    /** Waits until the first run started. */
    public boolean awaitStarted(long timeoutMillis) throws InterruptedException {
        return started.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
