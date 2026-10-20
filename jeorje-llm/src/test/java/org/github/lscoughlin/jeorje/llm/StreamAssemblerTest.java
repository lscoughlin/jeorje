package org.github.lscoughlin.jeorje.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The assembler finds the right call for each fragment in the styles that providers use. */
class StreamAssemblerTest {
    private record Call(String name, String arguments) {}

    private static Map<String, Call> run(String... chunks) throws LlmException {
        var assembler = new StreamAssembler(JsonMapper.builder().build());
        var events = new ArrayList<LlmEvent>();
        for (var chunk : chunks) events.addAll(assembler.accept(chunk));
        events.addAll(assembler.flush());
        var names = new LinkedHashMap<String, String>();
        var args = new LinkedHashMap<String, StringBuilder>();
        for (var event : events) {
            switch (event) {
                case LlmEvent.ToolUseStart s -> {
                    names.put(s.id(), s.tool());
                    args.putIfAbsent(s.id(), new StringBuilder());
                }
                case LlmEvent.ToolUseInput i -> args.computeIfAbsent(i.id(), k -> new StringBuilder()).append(i.jsonFragment());
                default -> { }
            }
        }
        var out = new LinkedHashMap<String, Call>();
        names.forEach((id, name) -> out.put(id, new Call(name, args.get(id).toString())));
        return out;
    }

    private static String chunk(String toolCalls) {
        return "{\"choices\":[{\"delta\":{\"tool_calls\":[" + toolCalls + "]}}]}";
    }

    @Test
    void theStandardStyleHasTheIdOnTheFirstChunkOnly() throws Exception {
        var calls = run(
            chunk("{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"\"}}"),
            chunk("{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\"}}"),
            chunk("{\"index\":0,\"function\":{\"arguments\":\"\\\"A.java\\\"}\"}}"),
            chunk("{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"grep\",\"arguments\":\"{\\\"pattern\\\":\\\"x\\\"}\"}}"));
        assertEquals(Map.of("a", new Call("read", "{\"path\":\"A.java\"}"),
            "b", new Call("grep", "{\"pattern\":\"x\"}")), calls);
    }

    @Test
    void someProvidersRepeatTheIdAndTheNameOnEveryChunk() throws Exception {
        var calls = run(
            chunk("{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}"),
            chunk("{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"\\\"A.java\\\"}\"}}"));
        assertEquals(Map.of("a", new Call("read", "{\"path\":\"A.java\"}")), calls);
    }

    @Test
    void parallelCallsWithTheSameIndexAndDifferentIdsStaySeparate() throws Exception {
        var calls = run(
            chunk("{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"A\\\"\"}}"),
            chunk("{\"index\":0,\"function\":{\"arguments\":\"}\"}}"),
            chunk("{\"index\":0,\"id\":\"b\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"B\\\"\"}}"),
            chunk("{\"index\":0,\"function\":{\"arguments\":\"}\"}}"));
        assertEquals(Map.of("a", new Call("read", "{\"path\":\"A\"}"), "b", new Call("read", "{\"path\":\"B\"}")), calls);
    }

    @Test
    void chunksWithoutAnIndexFollowTheirIdAndThenTheLastCall() throws Exception {
        var calls = run(
            chunk("{\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}"),
            chunk("{\"function\":{\"arguments\":\"\\\"A\\\"}\"}}"),
            chunk("{\"id\":\"b\",\"function\":{\"name\":\"ls\",\"arguments\":\"{}\"}}"));
        assertEquals(Map.of("a", new Call("read", "{\"path\":\"A\"}"), "b", new Call("ls", "{}")), calls);
    }

    @Test
    void argumentsThatAreAnObjectBecomeJsonText() throws Exception {
        var calls = run(chunk("{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\","
            + "\"arguments\":{\"path\":\"A.java\",\"limit\":5}}}"));
        assertEquals(Map.of("a", new Call("read", "{\"path\":\"A.java\",\"limit\":5}")), calls);
    }

    @Test
    void aFragmentThatNeverGotAnIdOrANameIsNotLost() throws Exception {
        var calls = run(chunk("{\"index\":2,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"A\\\"}\"}}"));
        assertEquals(1, calls.size());
        var only = calls.entrySet().iterator().next();
        assertTrue(only.getKey().startsWith(StreamAssembler.LOST_ID_PREFIX), only.getKey());
        assertEquals(new Call(StreamAssembler.LOST_NAME, "{\"path\":\"A\"}"), only.getValue());
    }

    @Test
    void aCallWithAnIdAndNoNameIsReportedAtTheEnd() throws Exception {
        var calls = run(chunk("{\"index\":0,\"id\":\"a\",\"function\":{\"arguments\":\"{}\"}}"));
        assertEquals(Map.of("a", new Call(StreamAssembler.LOST_NAME, "{}")), calls);
    }

    @Test
    void theFinishReasonIsKept() throws Exception {
        var assembler = new StreamAssembler(JsonMapper.builder().build());
        assembler.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}");
        assertEquals(new LlmEvent.MessageStop("length"), assembler.finish());
        assertEquals(List.of(), assembler.flush());
    }
}
