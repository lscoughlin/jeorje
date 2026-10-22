package org.github.lscoughlin.jeorje.core.subagent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.Rig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalDeliveryTest {
    @TempDir Path dir;

    private record Call(AgentId from, String fromName, AgentId to, String text, boolean question) {}

    @Test
    void messageToAnUnknownTargetGoesToTheExternalSink() {
        try (var rig = new Rig(dir)) {
            var calls = new ArrayList<Call>();
            rig.runtime.setExternalDelivery((from, name, to, text, question, cancel) -> {
                calls.add(new Call(from, name, to, text, question));
                return Optional.of(AskResult.answer("Message sent to " + to.value() + "."));
            });
            var result = rig.runtime.message(rig.root, "Session A", "fork-1", "hello");
            assertTrue(result.ok());
            assertEquals(1, calls.size());
            assertEquals("Session A", calls.getFirst().fromName());
            assertEquals("hello", calls.getFirst().text());
            assertEquals(new AgentId("fork-1"), calls.getFirst().to());
            assertFalse(calls.getFirst().question());
        }
    }

    @Test
    void askToAnUnknownTargetGoesToTheExternalSink() {
        try (var rig = new Rig(dir)) {
            rig.runtime.setExternalDelivery((from, name, to, text, question, cancel) ->
                Optional.of(AskResult.answer(name + " asked " + text)));
            var result = rig.runtime.ask(rig.root, "main", "fork-1", "why?", CancellationToken.none());
            assertEquals("main asked why?", result.answer());
        }
    }

    @Test
    void anEmptyResultFromTheSinkGivesTheUnknownFailure() {
        try (var rig = new Rig(dir)) {
            rig.runtime.setExternalDelivery((from, name, to, text, question, cancel) -> Optional.empty());
            assertEquals(AskResult.UNKNOWN, rig.runtime.message(rig.root, "nobody", "x").reason());
            assertEquals(AskResult.UNKNOWN,
                rig.runtime.ask(rig.root, "nobody", "x", CancellationToken.none()).reason());
        }
    }

    @Test
    void anExternalSenderCannotBeFoundByTheAgentsOfTheRuntime() {
        try (var rig = new Rig(dir)) {
            var calls = new ArrayList<Call>();
            rig.runtime.setExternalDelivery((from, name, to, text, question, cancel) -> {
                calls.add(new Call(from, name, to, text, question));
                return Optional.of(AskResult.answer("ok"));
            });
            assertTrue(rig.runtime.registerExternalSender(new AgentId("main"), "Session A"));
            assertFalse(rig.runtime.registerExternalSender(new AgentId("main"), "Session A"));
            // The external sender can send to the root of this runtime.
            var sent = rig.runtime.message(new AgentId("main"), "Session A", "root", "hi root");
            assertTrue(sent.ok());
            assertEquals(1, rig.rootInput.size());
            assertTrue(calls.isEmpty());
            // A message to the sender id goes to the sink, not to the sender node.
            rig.runtime.message(rig.root, "root", "main", "back");
            assertEquals(1, calls.size());
            assertEquals("back", calls.getFirst().text());
        }
    }

    @Test
    void withoutASinkTheTargetIsUnknown() {
        try (var rig = new Rig(dir)) {
            assertEquals(AskResult.UNKNOWN, rig.runtime.message(rig.root, "nobody", "x").reason());
        }
    }
}
