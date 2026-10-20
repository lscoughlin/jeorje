package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormOption;
import org.github.lscoughlin.jeorje.core.form.FormQuestion;
import org.github.lscoughlin.jeorje.core.form.FormQuestion.Kind;
import org.junit.jupiter.api.Test;

class PlainFormAskerTest {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private static List<FormOption> options(String... labels) {
        return java.util.Arrays.stream(labels).map(FormOption::of).toList();
    }

    private static final Form FORM = new Form("Setup", List.of(
        new FormQuestion("db", "Which database?", Kind.SINGLE, options("sqlite", "postgres"), true, true, 0, 0, null),
        new FormQuestion("checks", "Which checks?", Kind.MULTI, options("unit", "lint", "e2e"), false, true, 1, 2, null),
        new FormQuestion("note", "Anything else?", Kind.TEXT, List.of(), false, false, 0, 0, null)));

    private FormAnswer ask(String input) {
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        return new PlainFormAsker(new BufferedReader(new StringReader(input)), out).ask(FORM);
    }

    private String output() { return bytes.toString(StandardCharsets.UTF_8); }

    @Test
    void goodAnswersGiveAnAnswerForEachQuestion() {
        var answer = ask("2\nunit, 3\nkeep it short\n\n");
        assertEquals(FormAnswer.Status.ANSWERED, answer.status());
        assertEquals(List.of("postgres"), answer.answers().get(0).selected());
        assertEquals(List.of("unit", "e2e"), answer.answers().get(1).selected());
        assertEquals("keep it short", answer.answers().get(2).text());
        assertTrue(output().contains("Your answers:") && output().contains("Submit? [Y/n/c]"), output());
    }

    @Test
    void aBadAnswerIsExplainedAndAskedAgain() {
        var answer = ask("mysql-ish\n\nlint,e2e,unit\nlint\n\ny\n");
        assertTrue(output().contains("This question needs an answer."), output());
        assertTrue(output().contains("Question checks: choose 1 to 2 options."), output());
        assertEquals(List.of("lint"), answer.answers().get(1).selected());
        assertEquals("mysql-ish", answer.answers().get(0).other(), "the first line is an own answer");
        assertEquals(2, answer.answers().size(), "the optional question stays empty");
    }

    @Test
    void chatReturnsThePartialAnswersAndTheNote() {
        var answer = ask("1\nc\nwhat is lint?\n");
        assertEquals(FormAnswer.Status.CHAT, answer.status());
        assertEquals("what is lint?", answer.note());
        assertEquals(1, answer.answers().size());
        assertEquals(FormAnswer.Status.CHAT, ask("1\n1\n\nc\n\n").status(), "chat at the submit step");
    }

    @Test
    void qAndAnEndOfInputDeclineAndNAtSubmitDeclines() {
        assertEquals(FormAnswer.Status.DECLINED, ask("q\n").status());
        assertEquals(FormAnswer.Status.DECLINED, ask("1\n").status());
        assertEquals(FormAnswer.Status.DECLINED, ask("1\n1\n\nn\n").status());
    }

    @Test
    void answerValuesAreParsedAndChecked() {
        assertEquals(Map.of("a", "1", "b", "x=y"), FormAnswers.parse(List.of("a=1", " b = x=y ")));
        assertThrows(IllegalArgumentException.class, () -> FormAnswers.parse(List.of("=1")));
        var single = FORM.questions().getFirst();
        assertEquals(List.of("sqlite"), FormAnswers.fromValue(single, "1").selected());
        assertEquals("duckdb", FormAnswers.fromValue(single, "duckdb").other());
        var multi = FORM.questions().get(1);
        assertThrows(IllegalArgumentException.class, () -> FormAnswers.fromValue(multi, "unit,lint,e2e"));
        assertThrows(IllegalArgumentException.class, () -> FormAnswers.fromValue(multi, "nope"));
        assertNull(FormAnswers.resolve(FORM, Map.of(), false).orElse(null));
    }
}
