package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormOption;
import org.github.lscoughlin.jeorje.core.form.FormQuestion;
import org.github.lscoughlin.jeorje.core.form.FormQuestion.Kind;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.junit.jupiter.api.Test;

class FormPromptTest {
    private static FormQuestion single(String id, String... labels) {
        return new FormQuestion(id, "Pick " + id, Kind.SINGLE, options(labels), false, true, 0, 0, null);
    }

    private static List<FormOption> options(String... labels) {
        return java.util.Arrays.stream(labels).map(FormOption::of).toList();
    }

    private static FormQuestion multi(String id, int min, int max, boolean required, String... labels) {
        return new FormQuestion(id, "Choose " + id, Kind.MULTI, options(labels), false, required, min, max, null);
    }

    private static FormQuestion text(String id, boolean required) {
        return new FormQuestion(id, "Say " + id, Kind.TEXT, List.of(), false, required, 0, 0, null);
    }

    private static FormPrompt prompt(FormQuestion... questions) {
        return new FormPrompt(new AgentId("main"), new Form("Setup", List.of(questions)));
    }

    /** Sends keys and returns the answer of the last key that ended the form. */
    private static Optional<FormAnswer> keys(FormPrompt p, String... keys) {
        Optional<FormAnswer> last = Optional.empty();
        for (var k : keys) {
            var r = p.handleKey(k);
            if (r.isPresent()) last = r;
        }
        return last;
    }

    private static String text(FormPrompt p) {
        return String.join("\n", p.render(80, 40).stream().map(Object::toString).toList());
    }

    @Test
    void singleChoiceEnterSelectsAndMovesOnThenReviewSubmits() {
        var p = prompt(single("a", "x", "y", "z"));
        keys(p, "down", "enter");
        assertEquals(1, p.page(), "moved to the review page");
        assertTrue(text(p).contains("Pick a: y"), text(p));
        var answer = keys(p, "enter").orElseThrow();
        assertEquals(FormAnswer.Status.ANSWERED, answer.status());
        assertEquals(List.of("y"), answer.answers().getFirst().selected());
    }

    @Test
    void numberKeysAndSpaceSelect() {
        var p = prompt(single("a", "x", "y", "z"));
        keys(p, "3");
        assertTrue(text(p).contains("(o) z"), text(p));
        keys(p, "1");
        assertTrue(text(p).contains("(o) x") && text(p).contains("( ) z"), text(p));
        keys(p, "down", "space", "tab");
        assertEquals(1, p.page());
        assertEquals(List.of("y"), keys(p, "enter").orElseThrow().answers().getFirst().selected());
    }

    @Test
    void multiChoiceTogglesAndKeepsTheLabelOrder() {
        var p = prompt(multi("m", 1, 0, true, "a", "b", "c"));
        keys(p, "3", "1");
        assertTrue(text(p).contains("[x] a") && text(p).contains("[x] c"), text(p));
        keys(p, "down", "space");
        assertTrue(text(p).contains("[x] b"), text(p));
        keys(p, "space");
        assertTrue(text(p).contains("[ ] b"), text(p));
        keys(p, "tab");
        var answer = keys(p, "enter").orElseThrow();
        assertEquals(List.of("a", "c"), answer.answers().getFirst().selected());
    }

    @Test
    void minAndMaxChoicesAreEnforcedAndExplained() {
        var p = prompt(multi("m", 2, 2, true, "a", "b", "c"));
        keys(p, "1", "tab");
        assertEquals(0, p.page());
        assertEquals("Choose at least 2", p.message());
        keys(p, "2", "3");
        assertEquals("Choose at most 2", p.message());
        assertFalse(text(p).contains("[x] c"));
        keys(p, "tab");
        assertEquals(1, p.page());
    }

    @Test
    void aRequiredQuestionBlocksTheWayForwardAndAnOptionalOneDoesNot() {
        var p = prompt(single("a", "x", "y"), text("t", false));
        keys(p, "tab");
        assertEquals(0, p.page());
        assertEquals("Answer this question first.", p.message());
        keys(p, "1", "tab");
        assertEquals(1, p.page());
        keys(p, "esc", "tab");
        assertEquals(2, p.page(), "an optional question may stay empty");
        var answer = keys(p, "enter").orElseThrow();
        assertEquals(1, answer.answers().size());
    }

    @Test
    void shiftTabAndLeftGoBackWithoutAChecks() {
        var p = prompt(single("a", "x", "y"), single("b", "x", "y"));
        keys(p, "1", "tab");
        assertEquals(1, p.page());
        keys(p, "shift-tab");
        assertEquals(0, p.page());
        keys(p, "right", "left");
        assertEquals(0, p.page());
    }

    @Test
    void aTextFieldEditsUnicodeByCodePoint() {
        var p = prompt(text("t", true));
        keys(p, "h", "i", " ", "😀", "é");
        assertTrue(text(p).contains("hi 😀é"), text(p));
        keys(p, "backspace", "backspace");
        assertTrue(text(p).contains("hi ") && !text(p).contains("😀"), text(p));
        keys(p, "backspace", "backspace", "backspace", "backspace");
        keys(p, "a", "b", "c", "left", "left", "x", "end", "d");
        assertTrue(text(p).contains("axbcd"), text(p));
        keys(p, "home", "delete");
        assertTrue(text(p).contains("xbcd"), text(p));
        keys(p, "ctrl-u", "o", "k", "enter");
        var answer = keys(p, "enter").orElseThrow();
        assertEquals("ok", answer.answers().getFirst().text());
    }

    @Test
    void theTerminalCursorFollowsTheTextField() {
        var p = prompt(text("t", true));
        p.render(60, 20);
        assertTrue(p.cursorRow() > 0);
        var start = p.cursorColumn();
        keys(p, "a", "b");
        p.render(60, 20);
        assertEquals(start + 2, p.cursorColumn());
        keys(p, "home");
        p.render(60, 20);
        assertEquals(start, p.cursorColumn());
        keys(p, "esc");
        p.render(60, 20);
        assertEquals(-1, p.cursorRow(), "no cursor outside a field");
    }

    @Test
    void otherAnswerOpensAFieldAndReplacesTheSingleChoice() {
        var q = new FormQuestion("db", "Which database?", Kind.SINGLE, options("sqlite", "pg"), true, true, 0, 0, "pg");
        var p = prompt(q);
        assertTrue(text(p).contains("(o) pg"), "the default starts chosen");
        keys(p, "down", "enter", "d", "u", "c", "k", "enter");
        var answer = keys(p, "enter").orElseThrow();
        assertEquals(List.of(), answer.answers().getFirst().selected());
        assertEquals("duck", answer.answers().getFirst().other());
    }

    @Test
    void multiWithOtherKeepsBoth() {
        var q = new FormQuestion("m", "Which?", Kind.MULTI, options("a", "b"), true, true, 0, 0, null);
        var p = prompt(q);
        keys(p, "1", "down", "down", "enter", "z", "enter");
        var answer = keys(p, "enter").orElseThrow();
        assertEquals(List.of("a"), answer.answers().getFirst().selected());
        assertEquals("z", answer.answers().getFirst().other());
    }

    @Test
    void chatAboutThisEndsTheFormWithTheNoteAndThePartialAnswers() {
        var p = prompt(single("a", "x", "y"), single("b", "x", "y"));
        keys(p, "2", "tab");
        keys(p, "c");
        assertTrue(text(p).contains("Note:"), text(p));
        keys(p, "w", "h", "y", "?");
        var answer = keys(p, "enter").orElseThrow();
        assertEquals(FormAnswer.Status.CHAT, answer.status());
        assertEquals("why?", answer.note());
        assertEquals(1, answer.answers().size());
        assertEquals("a", answer.answers().getFirst().id());
    }

    @Test
    void chatRowAndEscapeInTheNoteGoBack() {
        var p = prompt(single("a", "x", "y"));
        keys(p, "down", "down", "enter");
        assertTrue(text(p).contains("Note:"));
        keys(p, "esc");
        assertFalse(text(p).contains("Note:"));
        assertEquals(0, p.page());
        var answer = keys(p, "c", "enter").orElseThrow();
        assertEquals(FormAnswer.Status.CHAT, answer.status());
        assertNull(answer.note());
    }

    @Test
    void cInATextFieldIsACharacter() {
        var p = prompt(text("t", true));
        keys(p, "c", "a", "t");
        assertEquals("cat", keys(p, "enter", "enter").orElseThrow().answers().getFirst().text());
    }

    @Test
    void escapeAsksBeforeItDeclines() {
        var p = prompt(single("a", "x", "y"));
        assertTrue(keys(p, "esc").isEmpty());
        assertEquals("Decline this form? y/n", p.message());
        assertTrue(keys(p, "n").isEmpty());
        assertEquals("", p.message());
        assertEquals(FormAnswer.Status.DECLINED, keys(p, "esc", "y").orElseThrow().status());
    }

    @Test
    void theReviewPageEditsAndSubmitsAndChecksRequiredAnswers() {
        var p = prompt(single("a", "x", "y"), text("t", false));
        keys(p, "1", "tab", "esc", "tab");
        assertEquals(2, p.page());
        assertTrue(text(p).contains("Say t: (no answer)") && text(p).contains("Submit"), text(p));
        keys(p, "down", "enter");
        assertEquals(0, p.page(), "Edit goes to the first question");
        keys(p, "2", "tab", "tab");
        assertEquals(2, p.page());
        var answer = keys(p, "enter").orElseThrow();
        assertEquals(List.of("y"), answer.answers().getFirst().selected());
        var declined = keys(prompt(single("a", "x", "y")), "1", "tab", "down", "down", "enter", "y");
        assertEquals(FormAnswer.Status.DECLINED, declined.orElseThrow().status());
    }

    @Test
    void aLongFormScrollsAndKeepsTheCursorRowVisible() {
        var labels = new String[12];
        for (var i = 0; i < 12; i++) labels[i] = "option " + i;
        var p = prompt(single("a", labels));
        for (var i = 0; i < 11; i++) keys(p, "down");
        var lines = p.render(60, 14).stream().map(Object::toString).toList();
        assertTrue(lines.size() <= 14, "rows: " + lines.size());
        var joined = String.join("\n", lines);
        assertTrue(joined.contains("> ") && joined.contains("option 11"), joined);
        assertTrue(joined.contains("^ more"), joined);
        assertEquals(12, p.render(60, 40).stream().map(Object::toString).filter(l -> l.contains("option ")).count());
    }

    @Test
    void renderNeverGivesALineWiderThanTheWidth() {
        var q = new FormQuestion("a", "A very long question text that must wrap over many lines in a narrow box "
            + "and a_word_without_any_space_that_is_longer_than_the_box_itself", Kind.SINGLE,
            List.of(new FormOption("a label that is long enough to wrap onto a second line of the box", "and a description"),
                FormOption.of("short")), true, true, 0, 0, null);
        var p = prompt(q, text("t", true), multi("m", 0, 0, true, "x", "y"));
        for (var round = 0; round < 3; round++) {
            for (var width = 20; width <= 200; width += 7) {
                for (var line : p.render(width, 30)) {
                    assertTrue(line.columnLength() <= width, width + ": " + line);
                }
            }
            keys(p, "1", "tab", "x", "enter", "1", "tab");
        }
        assertTrue(p.render(20, 6).size() <= 6);
    }
}
