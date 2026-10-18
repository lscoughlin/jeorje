package org.github.lscoughlin.jeorje.tools.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.FakeTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RegistryTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    record Echo(@Required @Describe("Text to echo") String text,
                Integer times,
                @Allowed({"upper", "lower"}) String mode) {}

    static final class EchoTool extends RecordTool<Echo> {
        EchoTool() {
            super("echo", "Echo text", Echo.class, true);
        }

        @Override
        protected ToolResultData run(Echo input, ToolContext context) {
            var times = input.times() == null ? 1 : input.times();
            return ToolResultData.ok(input.text().repeat(times));
        }
    }

    /** A tool that runs code. */
    record LambdaTool(String name, boolean readOnly, Duration timeout, Body body) implements TimedTool {
        interface Body {
            ToolResultData run(ToolContext context) throws Exception;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "test", JSON.createObjectNode().put("type", "object"));
        }

        @Override
        public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
            return body.run(context);
        }
    }

    static ToolContext context() {
        return context(CancellationToken.none());
    }

    static ToolContext context(CancellationToken cancel) {
        return new ToolContext(Path.of("."), new AgentId("a"), cancel, null, Clock.systemUTC());
    }

    static ToolUse use(String tool, String json) {
        return new ToolUse("id", tool, JSON.readTree(json));
    }

    @Test
    void generatesSchemaFromRecord() {
        var schema = new EchoTool().definition().inputSchema();
        assertEquals("object", schema.get("type").asString());
        assertEquals("string", schema.at("/properties/text/type").asString());
        assertEquals("Text to echo", schema.at("/properties/text/description").asString());
        assertEquals("integer", schema.at("/properties/times/type").asString());
        assertEquals("upper", schema.at("/properties/mode/enum/0").asString());
        assertEquals(1, schema.get("required").size());
        assertFalse(schema.get("additionalProperties").asBoolean());
    }

    @Test
    void runsValidInput() {
        var registry = ToolRegistry.builder().register(new EchoTool()).build();
        var result = registry.execute(use("echo", "{\"text\":\"ab\",\"times\":2}"), context());
        assertFalse(result.error());
        assertEquals("abab", result.output());
    }

    @Test
    void refusesInvalidInputWithoutCallingTool() {
        var fake = FakeTool.returning("fake", "ok");
        var registry = ToolRegistry.builder().register(new EchoTool()).register(fake).build();
        var missing = registry.execute(use("echo", "{}"), context());
        assertTrue(missing.error());
        assertTrue(missing.output().contains("input.text is required"), missing.output());
        var wrongType = registry.execute(use("echo", "{\"text\":5}"), context());
        assertTrue(wrongType.error());
        assertTrue(wrongType.output().contains("must be of type string"), wrongType.output());
        var unknownField = registry.execute(use("echo", "{\"text\":\"a\",\"bogus\":1}"), context());
        assertTrue(unknownField.output().contains("input.bogus is not a known field"), unknownField.output());
        var badEnum = registry.execute(use("echo", "{\"text\":\"a\",\"mode\":\"x\"}"), context());
        assertTrue(badEnum.output().contains("must be one of"), badEnum.output());
        var strictFake = registry.execute(use("fake", "[1]"), context());
        assertTrue(strictFake.error());
        assertTrue(fake.inputs().isEmpty());
    }

    @Test
    void unknownToolListsAvailableTools() {
        var registry = ToolRegistry.builder().register(new EchoTool()).build();
        var result = registry.execute(use("nope", "{}"), context());
        assertTrue(result.error());
        assertTrue(result.output().contains("echo"));
    }

    @Test
    void duplicateNamesAreRejected() {
        var builder = ToolRegistry.builder().register(new EchoTool());
        assertThrows(IllegalArgumentException.class, () -> builder.register(new EchoTool()));
    }

    @Test
    void filtersForSubagents() {
        var registry = ToolRegistry.builder()
                .register(new EchoTool())
                .register(FakeTool.returning("r", ToolResultData.ok("x"), true))
                .register(FakeTool.returning("w", ToolResultData.ok("x"), false))
                .build();
        assertEquals(List.of("echo", "r", "w"), registry.names());
        assertEquals(List.of("r"), registry.only(List.of("r", "missing")).names());
        assertEquals(List.of("echo", "w"), registry.without(List.of("r")).names());
        assertEquals(List.of("echo", "r"), registry.readOnlyTools().names());
        assertEquals(3, registry.definitions().size());
        assertTrue(registry.only(List.of("r")).execute(use("w", "{}"), context()).error());
    }

    @Test
    void constrainsEnumInSchemaAndValidation() {
        var registry = ToolRegistry.builder().register(new EchoTool()).build()
                .constrain("echo", "text", List.of("yes", "no"));
        var schema = registry.definitions().get(0).inputSchema();
        assertEquals("no", schema.at("/properties/text/enum/1").asString());
        assertTrue(registry.execute(use("echo", "{\"text\":\"maybe\"}"), context()).error());
        assertEquals("yes", registry.execute(use("echo", "{\"text\":\"yes\"}"), context()).output());
        assertThrows(IllegalArgumentException.class, () -> registry.constrain("echo", "nothing", List.of("a")));
    }

    @Test
    void capsLongOutputWithNote() {
        var registry = ToolRegistry.builder().register(new EchoTool()).build();
        var result = registry.execute(use("echo", "{\"text\":\"x\",\"times\":31000}"), context());
        assertFalse(result.error());
        assertTrue(result.output().startsWith("x".repeat(30_000) + "\n[Output cut"));
        assertTrue(result.output().contains("showing 30000 of 31000"));
        assertTrue(result.output().contains("offset"));
        assertTrue(result.output().contains("recall"));
    }

    @Test
    void doesNotCapOutputAtTheLimit() {
        var registry = ToolRegistry.builder().register(new EchoTool()).build();
        var result = registry.execute(use("echo", "{\"text\":\"x\",\"times\":30000}"), context());
        assertEquals(30_000, result.output().length());
    }

    @Test
    void customCapAndErrorFlagSurvive() {
        var registry = ToolRegistry.builder().maxOutputChars(10)
                .register(FakeTool.returning("e", ToolResultData.error("e".repeat(50)), true)).build();
        var result = registry.execute(use("e", "{}"), context());
        assertTrue(result.error());
        assertTrue(result.output().startsWith("eeeeeeeeee\n[Output cut"));
    }

    @Test
    void refusesBinaryOutput() {
        var registry = ToolRegistry.builder()
                .register(FakeTool.returning("bin", "abc\0def")).build();
        var result = registry.execute(use("bin", "{}"), context());
        assertTrue(result.error());
        assertTrue(result.output().contains("binary"));
    }

    @Test
    void timeoutInterruptsTool() {
        var interrupted = new AtomicInteger();
        var slow = new LambdaTool("slow", true, Duration.ofMillis(100), c -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                throw e;
            }
            return ToolResultData.ok("late");
        });
        var registry = ToolRegistry.builder().register(slow).build();
        var start = System.nanoTime();
        var result = registry.execute(use("slow", "{}"), context());
        assertTrue(result.error());
        assertTrue(result.output().contains("timed out"), result.output());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5);
    }

    @Test
    void builderTimeoutOverridesAndDefaultIs120Seconds() {
        var registry = ToolRegistry.builder().register(new EchoTool())
                .register(new LambdaTool("t", true, Duration.ofSeconds(7), c -> ToolResultData.ok("")))
                .timeout("echo", Duration.ofMinutes(10)).build();
        assertEquals(Duration.ofMinutes(10), registry.timeoutFor("echo"));
        assertEquals(Duration.ofSeconds(7), registry.timeoutFor("t"));
        assertEquals(Duration.ofSeconds(120), ToolRegistry.builder().register(FakeTool.returning("f", "x"))
                .build().timeoutFor("f"));
    }

    @Test
    void thrownExceptionBecomesErrorResult() {
        var registry = ToolRegistry.builder()
                .register(FakeTool.throwing("boom", new IllegalStateException("bad state"))).build();
        var result = registry.execute(use("boom", "{}"), context());
        assertTrue(result.error());
        assertTrue(result.output().contains("bad state"));
    }

    @Test
    void cancellationStopsBlockedTool() throws Exception {
        var blocking = FakeTool.blocking("wait");
        var registry = ToolRegistry.builder().register(blocking).build();
        var cancel = new CancellationToken();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                assertTrue(blocking.awaitStarted(5_000));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            cancel.cancel();
        });
        var result = registry.execute(use("wait", "{}"), context(cancel));
        thread.join();
        assertTrue(result.error());
        assertTrue(result.output().contains("cancelled"));
    }

    @Test
    void readOnlyCallsRunAtTheSameTime() {
        var barrier = new CyclicBarrier(2);
        // If the calls ran one after the other, the first would wait for the barrier and time out.
        Tool a = new LambdaTool("a", true, Duration.ofSeconds(5), c -> {
            barrier.await(3, TimeUnit.SECONDS);
            return ToolResultData.ok("a");
        });
        Tool b = new LambdaTool("b", true, Duration.ofSeconds(5), c -> {
            barrier.await(3, TimeUnit.SECONDS);
            return ToolResultData.ok("b");
        });
        var registry = ToolRegistry.builder().register(a).register(b).build();
        var uses = List.of(use("a", "{}"), use("b", "{}"));
        assertTrue(registry.canRunTogether(uses));
        var results = registry.executeAll(uses, context());
        assertEquals(List.of("a", "b"), results.stream().map(ToolResultData::output).toList());
    }

    @Test
    void callsThatChangeFilesRunOneAfterTheOther() {
        var running = new AtomicInteger();
        var overlap = new AtomicInteger();
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        LambdaTool.Body body = c -> {
            if (running.incrementAndGet() > 1) overlap.incrementAndGet();
            Thread.sleep(50);
            running.decrementAndGet();
            return ToolResultData.ok("done");
        };
        var reader = new LambdaTool("reader", true, Duration.ofSeconds(5), c -> {
            order.add("reader");
            return body.run(c);
        });
        var writer = new LambdaTool("writer", false, Duration.ofSeconds(5), c -> {
            order.add("writer");
            return body.run(c);
        });
        var registry = ToolRegistry.builder().register(reader).register(writer).build();
        var uses = List.of(use("reader", "{}"), use("writer", "{}"), use("reader", "{}"));
        assertFalse(registry.canRunTogether(uses));
        registry.executeAll(uses, context());
        assertEquals(0, overlap.get());
        assertEquals(List.of("reader", "writer", "reader"), order);
    }

    @Test
    void readOnlyFlagsOfRegisteredTools() {
        assertTrue(new EchoTool().readOnly());
        assertFalse(FakeTool.returning("w", ToolResultData.ok(""), false).readOnly());
    }
}
