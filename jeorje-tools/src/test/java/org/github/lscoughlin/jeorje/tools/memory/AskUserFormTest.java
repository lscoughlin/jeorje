package org.github.lscoughlin.jeorje.tools.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormAnswer.QuestionAnswer;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AskUserFormTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TWO = "{\"title\":\"Setup\",\"questions\":["
        + "{\"id\":\"db\",\"prompt\":\"Which database?\",\"options\":[\"sqlite\",{\"label\":\"postgres\",\"description\":\"server\"}],\"allowOther\":true},"
        + "{\"id\":\"checks\",\"prompt\":\"Which checks?\",\"kind\":\"multi\",\"options\":[\"unit\",\"lint\",\"e2e\"],\"minChoices\":1,\"maxChoices\":2},"
        + "{\"prompt\":\"Anything else?\",\"required\":false}]}";

    CopyOnWriteArrayList<UserQuestion> questions;
    UserQuestionBroker broker;
    ToolRegistry registry;
    ToolContext context;

    @BeforeEach
    void setUp() {
        questions = new CopyOnWriteArrayList<>();
        broker = new UserQuestionBroker(questions::add);
        registry = ToolRegistry.builder().register(new AskUserTool(broker)).build();
        context = new ToolContext(Path.of("."), new AgentId("root"), CancellationToken.none(),
            new InMemoryContextStore(), Clock.systemUTC());
    }

    ToolResultData call(String json) {
        return registry.execute(new ToolUse("id", "ask_user", JSON.readTree(json)), context);
    }

    ToolResultData askAndAnswer(String json, FormAnswer answer) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = pool.submit(() -> call(json));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (questions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, questions.size());
            assertTrue(broker.answer(questions.getFirst().id(), answer));
            return future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aFormGivesStructuredText() throws Exception {
        var answer = new FormAnswer(FormAnswer.Status.ANSWERED, List.of(
            new QuestionAnswer("db", List.of("sqlite"), null, null),
            new QuestionAnswer("checks", List.of("unit", "lint"), null, null)), null);
        var result = askAndAnswer(TWO, answer);
        assertFalse(result.error());
        assertEquals("User answers:\n- db \"Which database?\": sqlite\n- checks \"Which checks?\": unit; lint\n"
            + "- q3 \"Anything else?\": (no answer)", result.output());
        var form = questions.getFirst().form();
        assertEquals("Setup", form.title());
        assertEquals(3, form.questions().size());
        assertEquals("postgres", form.questions().get(0).options().get(1).label());
        assertEquals("server", form.questions().get(0).options().get(1).description());
        assertTrue(form.questions().get(0).allowOther());
        assertFalse(form.questions().get(2).required());
    }

    @Test
    void anOtherAnswerIsShown() throws Exception {
        var answer = new FormAnswer(FormAnswer.Status.ANSWERED, List.of(
            new QuestionAnswer("db", List.of(), "duckdb", null)), null);
        assertTrue(askAndAnswer(TWO, answer).output().contains("- db \"Which database?\": duckdb"));
    }

    @Test
    void theOldInputKeepsTheOldText() throws Exception {
        var result = askAndAnswer("{\"question\":\"Which db?\",\"choices\":[\"pg\",\"mysql\"]}", FormAnswer.text("pg"));
        assertEquals("User answer: pg", result.output());
        assertEquals("Which db?", questions.getFirst().question());
        assertEquals(List.of("pg", "mysql"), questions.getFirst().choices());
    }

    @Test
    void aChatAnswerTellsTheAgentToAnswerInChat() throws Exception {
        var answer = new FormAnswer(FormAnswer.Status.CHAT,
            List.of(new QuestionAnswer("db", List.of("sqlite"), null, null)), "What is the difference?");
        var out = askAndAnswer(TWO, answer).output();
        assertTrue(out.startsWith("The user wants to talk about this before answering."), out);
        assertTrue(out.contains("Note from the user: What is the difference?"), out);
        assertTrue(out.contains("- checks \"Which checks?\""), out);
        assertTrue(out.contains("Answers so far:\n- db \"Which database?\": sqlite"), out);
        assertTrue(out.endsWith("Then call ask_user again if you still need an answer."), out);
        var legacy = askAndAnswerAgain("{\"question\":\"Which?\",\"choices\":[\"a\",\"b\"]}",
            new FormAnswer(FormAnswer.Status.CHAT, List.of(), null));
        assertTrue(legacy.startsWith("The user wants to talk about this"), legacy);
    }

    private String askAndAnswerAgain(String json, FormAnswer answer) throws Exception {
        questions.clear();
        return askAndAnswer(json, answer).output();
    }

    @Test
    void aDeclinedFormSaysSo() throws Exception {
        assertEquals("The user declined to answer.", askAndAnswer(TWO, FormAnswer.declined()).output());
    }

    @Test
    void badInputGivesAShortError() {
        var cases = List.of(
            "{}", "Give question or questions.",
            "{\"question\":\"  \"}", "The question is empty.",
            "{\"question\":\"a\",\"questions\":[{\"prompt\":\"b\"}]}", "Use question or questions, not both.",
            "{\"questions\":[{\"prompt\":\"b\",\"options\":[\"x\"]}]}", "Question q1 needs at least 2 options.",
            "{\"questions\":[{\"prompt\":\"b\",\"options\":[\"x\",\"x\"]}]}", "Question q1 has the option x twice.",
            "{\"questions\":[{\"prompt\":\" \"}]}", "Question q1 has an empty prompt.",
            "{\"questions\":[{\"id\":\"a\",\"prompt\":\"p\"},{\"id\":\"a\",\"prompt\":\"p\"}]}", "Question ids must be unique: a",
            "{\"questions\":[{\"prompt\":\"p\",\"kind\":\"text\",\"options\":[\"a\",\"b\"]}]}", "Question q1 is a text question and has options.",
            "{\"questions\":[{\"prompt\":\"p\",\"kind\":\"radio\"}]}", "Invalid input for ask_user: input.questions[0].kind must be one of [single, multi, text] but is radio",
            "{\"questions\":[{\"prompt\":\"p\",\"options\":[\"a\",\"b\"],\"minChoices\":1}]}", "Question q1 is not a multi question. Remove minChoices and maxChoices.",
            "{\"questions\":[{\"prompt\":\"p\",\"kind\":\"multi\",\"options\":[\"a\",\"b\"],\"minChoices\":2,\"maxChoices\":1}]}", "Question q1 has bad minChoices or maxChoices. Use 0 <= min <= max <= 2.",
            "{\"questions\":[{\"prompt\":\"p\",\"kind\":\"multi\",\"options\":[\"a\",\"b\"],\"maxChoices\":3}]}", "Question q1 has bad minChoices or maxChoices. Use 0 <= min <= max <= 2.",
            "{\"questions\":[{\"prompt\":\"p\",\"options\":[\"a\",\"b\"],\"defaultValue\":\"c\"}]}", "The default of question q1 is not one of its options.",
            "{\"questions\":[{\"prompt\":\"p\",\"options\":[5,\"b\"]}]}", "An option must be a text or an object with a label.");
        for (var i = 0; i < cases.size(); i += 2) {
            var r = call(cases.get(i));
            assertTrue(r.error(), cases.get(i));
            assertEquals(cases.get(i + 1), r.output(), cases.get(i));
        }
        var many = new StringBuilder("{\"questions\":[");
        for (var i = 0; i < 9; i++) many.append(i == 0 ? "" : ",").append("{\"prompt\":\"p\"}");
        assertEquals("The form has 9 questions. The most is 8.", call(many + "]}").output());
        var opts = new StringBuilder("{\"questions\":[{\"prompt\":\"p\",\"options\":[");
        for (var i = 0; i < 13; i++) opts.append(i == 0 ? "" : ",").append("\"o").append(i).append('"');
        assertEquals("Question q1 has 13 options. The most is 12.", call(opts + "]}]}").output());
        assertEquals(0, questions.size(), "an invalid form never reaches the user");
    }

    @Test
    void theBrokerStopsOnCancel() throws Exception {
        var cancel = new CancellationToken();
        var cancellable = new ToolContext(Path.of("."), new AgentId("root"), cancel, new InMemoryContextStore(), Clock.systemUTC());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = pool.submit(() -> registry.execute(new ToolUse("id", "ask_user", JSON.readTree(TWO)), cancellable));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (questions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            cancel.cancel();
            assertTrue(future.get(5, TimeUnit.SECONDS).error());
            var wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (broker.pendingCount() > 0 && System.nanoTime() < wait) Thread.sleep(10);
            assertEquals(0, broker.pendingCount());
        }
    }

    @Test
    void theSchemaDescribesTheForm() {
        var schema = new AskUserTool(broker).definition().inputSchema().toString();
        assertTrue(schema.contains("questions") && schema.contains("minChoices") && schema.contains("single"), schema);
    }
}
