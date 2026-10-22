package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.subagent.AskResult;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Messages and questions between the root agents of two sessions. */
class SessionDirectoryTest {
    private static final Pattern QUESTION_ID = Pattern.compile("id (q\\d+)\\]");

    @TempDir Path dir;

    private final SessionHub hub = new SessionHub();
    private final List<AgentInput> mainInput = new CopyOnWriteArrayList<>();
    private final List<AgentInput> forkInput = new CopyOnWriteArrayList<>();
    private MainSession main;
    private MainSession fork;
    private SessionDirectory directory;

    private MainSession session(AgentId id, String name, List<AgentInput> inbox, boolean answers) {
        var session = new MainSession(id, name);
        var settings = AgentSettings.defaults("s-" + id, dir, 100_000);
        var runtime = SubagentRuntime.builder("s-" + id, settings, model -> {
            throw new IllegalStateException("no model");
        }).build();
        session.runtime(runtime);
        session.input(input -> {
            inbox.add(input);
            if (!answers || !(input instanceof AgentInput.PeerMessage peer)) return;
            var matcher = QUESTION_ID.matcher(peer.text());
            if (matcher.find()) runtime.answer(id, matcher.group(1), "the answer of " + name);
        });
        runtime.registerRoot(id, name, CancellationToken.none(), List.of(), a -> Decision.ALLOW, session.input(),
                () -> 1);
        return session;
    }

    @BeforeEach
    void setUp() {
        main = session(MainSession.MAIN, "main", mainInput, true);
        fork = session(new AgentId("fork-1"), "build", forkInput, true);
        hub.add(main, null);
        hub.add(fork, main.id());
        directory = new SessionDirectory(hub);
        main.runtime().setExternalDelivery(directory);
        fork.runtime().setExternalDelivery(directory);
    }

    @AfterEach
    void tearDown() {
        main.runtime().close();
        fork.runtime().close();
    }

    private static String textOf(AgentInput input) {
        return ((AgentInput.PeerMessage) input).text();
    }

    @Test
    void aMessageArrivesAsAPeerMessageWithTheSenderName() {
        var result = main.runtime().message(main.id(), "main", "fork-1", "hello fork");
        assertTrue(result.ok(), () -> String.valueOf(result.reason()));
        assertEquals(1, forkInput.size());
        var peer = (AgentInput.PeerMessage) forkInput.getFirst();
        assertEquals(main.id(), peer.from());
        assertTrue(peer.text().contains("hello fork"));
        assertTrue(peer.text().contains("main"));
        assertTrue(mainInput.isEmpty());
    }

    @Test
    void aMessageReachesASessionByItsName() {
        var result = main.runtime().message(main.id(), "main", "build", "by name");
        assertTrue(result.ok(), () -> String.valueOf(result.reason()));
        assertTrue(textOf(forkInput.getFirst()).contains("by name"));
    }

    @Test
    void aMessageGoesBackToTheFirstSession() {
        var result = fork.runtime().message(fork.id(), "build", "main", "report");
        assertTrue(result.ok(), () -> String.valueOf(result.reason()));
        assertEquals(1, mainInput.size());
        assertTrue(textOf(mainInput.getFirst()).contains("[message from build"));
    }

    @Test
    void anAskReturnsTheAnswerOfTheTargetAgent() {
        var result = main.runtime().ask(main.id(), "main", "fork-1", "status?", CancellationToken.none());
        assertTrue(result.ok(), () -> String.valueOf(result.reason()));
        assertEquals("the answer of build", result.answer());
        assertTrue(textOf(forkInput.getFirst()).contains("[question from main"));
        assertTrue(textOf(forkInput.getFirst()).contains("status?"));
    }

    @Test
    void anAskWorksInBothDirectionsAndMoreThanOnce() {
        var one = main.runtime().ask(main.id(), "main", "fork-1", "one?", CancellationToken.none());
        var two = fork.runtime().ask(fork.id(), "build", "main", "two?", CancellationToken.none());
        var three = main.runtime().ask(main.id(), "main", "fork-1", "three?", CancellationToken.none());
        assertEquals("the answer of build", one.answer());
        assertEquals("the answer of main", two.answer());
        assertEquals("the answer of build", three.answer());
    }

    @Test
    void anIdThatNoSessionOwnsIsUnknown() {
        assertEquals(AskResult.UNKNOWN, main.runtime().message(main.id(), "main", "fork-9", "x").reason());
        assertEquals(AskResult.UNKNOWN,
            main.runtime().ask(main.id(), "main", "fork-9", "x?", CancellationToken.none()).reason());
    }

    @Test
    void aSessionThatEndedGivesTheEndedFailure() {
        fork.end();
        var message = main.runtime().message(main.id(), "main", "fork-1", "x");
        assertFalse(message.ok());
        assertEquals(AskResult.ENDED, message.reason());
        var ask = main.runtime().ask(main.id(), "main", "fork-1", "x?", CancellationToken.none());
        assertEquals(AskResult.ENDED, ask.reason());
        assertTrue(forkInput.isEmpty());
    }
}
