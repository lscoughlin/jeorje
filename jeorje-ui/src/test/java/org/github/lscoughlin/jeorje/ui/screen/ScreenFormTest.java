package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormOption;
import org.github.lscoughlin.jeorje.core.form.FormQuestion;
import org.github.lscoughlin.jeorje.core.form.FormQuestion.Kind;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;
import org.github.lscoughlin.jeorje.ui.keys.KeyLayer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScreenFormTest {
    private static final AgentId MAIN = new AgentId("main");

    private static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(c.getAsBoolean(), "condition not reached in time");
    }

    private static FormQuestion single(String id, String... labels) {
        var options = java.util.Arrays.stream(labels).map(FormOption::of).toList();
        return new FormQuestion(id, "Pick " + id, Kind.SINGLE, options, false, true, 0, 0, null);
    }

    private static FormPrompt prompt(AgentId agent, FormQuestion... questions) {
        return new FormPrompt(agent, new Form("Setup", List.of(questions)));
    }

    private static String region(Screen screen, int rows, int cols) {
        return String.join("\n", screen.renderRegion(rows, cols).stream().map(Object::toString).toList());
    }

    @Test
    void aFormShowsAcceptsKeysAndGivesTheAnswer() throws Exception {
        try (var ft = new FakeTerminal(70, 20)) {
            var got = new AtomicReference<FormAnswer>();
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.showForm(prompt(MAIN, single("a", "x", "y", "z")), got::set);
            assertNotNull(screen.pendingForm());
            assertTrue(region(screen, 20, 70).contains("Question 1 of 1"));
            assertNull(screen.handleFormKey("down"));
            assertNull(screen.handleFormKey("enter"));
            assertNull(got.get());
            var answer = screen.handleFormKey("enter");
            assertEquals(answer, got.get());
            assertEquals(List.of("y"), answer.answers().getFirst().selected());
            assertNull(screen.pendingForm());
            assertTrue(region(screen, 20, 70).contains("form answered: y"));
        }
    }

    @Test
    void typedKeysAndEscapeSequencesReachTheForm() throws Exception {
        try (var ft = new FakeTerminal(70, 20)) {
            var got = new AtomicReference<FormAnswer>();
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.start();
            try {
                Thread.sleep(300);
                screen.showForm(prompt(MAIN, single("a", "x", "y", "z"),
                    new FormQuestion("t", "Say t", Kind.TEXT, List.of(), false, true, 0, 0, null)), got::set);
                Thread.sleep(300);
                ft.type("\u001b[B");
                Thread.sleep(150);
                ft.type("\r");
                Thread.sleep(150);
                ft.type("hé😀");
                Thread.sleep(150);
                ft.type("\u007f");
                ft.type("llo\r");
                Thread.sleep(150);
                ft.type("\r");
                await(() -> got.get() != null);
                assertEquals(FormAnswer.Status.ANSWERED, got.get().status());
                assertEquals(List.of("y"), got.get().answers().get(0).selected());
                assertEquals("héllo", got.get().answers().get(1).text());
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void aPermissionPromptShowsBeforeAForm() throws Exception {
        try (var ft = new FakeTerminal(70, 24)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.showForm(prompt(MAIN, single("a", "x", "y")), a -> { });
            var input = JsonMapper.builder().build().createObjectNode().put("path", "x");
            screen.apply(new AgentEvent.ConfirmationRequested(MAIN, new ToolUse("t9", "write", input)));
            var text = region(screen, 24, 70);
            assertTrue(text.contains("Permission needed") && !text.contains("Question 1 of 1"), text);
            screen.handlePromptKey("y");
            assertTrue(region(screen, 24, 70).contains("Question 1 of 1"));
        }
    }

    @Test
    void theTerminalCursorFollowsATextField() throws Exception {
        try (var ft = new FakeTerminal(70, 20)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.showForm(prompt(MAIN,
                new FormQuestion("t", "Say t", Kind.TEXT, List.of(), false, true, 0, 0, null)), a -> { });
            region(screen, 20, 70);
            var start = screen.formCursor();
            assertTrue(start[0] > 0, "the cursor is in the form");
            screen.handleFormKey("a");
            screen.handleFormKey("b");
            region(screen, 20, 70);
            assertEquals(start[1] + 2, screen.formCursor()[1]);
            screen.handleFormKey("home");
            region(screen, 20, 70);
            assertEquals(start[1], screen.formCursor()[1]);
            region(screen, 20, 50);
            assertEquals(start[1], screen.formCursor()[1], "a resize keeps the field column");
            screen.handleFormKey("esc");
            region(screen, 20, 70);
            assertEquals(-1, screen.formCursor()[0]);
        }
    }

    @Test
    void aClickSelectsAnOptionAndTheWheelMoves() throws Exception {
        try (var ft = new FakeTerminal(70, 20)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.showForm(prompt(MAIN, single("a", "x", "y", "z")), a -> { });
            var lines = screen.renderRegion(20, 70).stream().map(Object::toString).toList();
            var y = -1;
            for (var i = 0; i < lines.size(); i++) if (lines.get(i).contains("( ) z")) y = i;
            assertTrue(y > 0);
            assertNull(screen.handleFormKey("mouse:0:10:" + (y + 1)));
            assertTrue(region(screen, 20, 70).contains("(o) z"));
            screen.handleFormKey("mouse:64:10:5");
            assertTrue(region(screen, 20, 70).contains("> 2"), "the wheel moved the cursor up");
        }
    }

    @Test
    void aReboundFormKeyWorksAndTheOldKeyDoesNothing() throws Exception {
        var bindings = KeyBindings.resolve(List.of(new KeyLayer("test", Map.of("formToggle", "t"), Map.of())));
        try (var ft = new FakeTerminal(70, 20)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults().keyBindings(bindings));
            screen.showForm(prompt(MAIN, single("a", "x", "y", "z")), a -> { });
            screen.handleFormKey("down");
            screen.handleFormKey("space");
            assertFalse(region(screen, 20, 70).contains("(o) y"), "space no longer selects");
            screen.handleFormKey("t");
            assertTrue(region(screen, 20, 70).contains("(o) y"));
        }
    }

    @Test
    void aKeyConflictInTheFormContextIsAConfigError() {
        var layer = new KeyLayer("test", Map.of("formNext", "Down"), Map.of());
        var e = assertThrows(RuntimeException.class, () -> KeyBindings.resolve(List.of(layer)));
        assertTrue(e.getMessage().contains("formNext") && e.getMessage().contains("formDown"), e.getMessage());
    }

    @Test
    void cancelFormsRemovesTheFormsOfOneAgent() throws Exception {
        try (var ft = new FakeTerminal(70, 20)) {
            var other = new AgentId("sub");
            var calls = new AtomicReference<>(0);
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.showForm(prompt(other, single("a", "x", "y")), a -> calls.set(calls.get() + 1));
            screen.showForm(prompt(MAIN, single("b", "x", "y")), a -> calls.set(calls.get() + 1));
            assertEquals(1, screen.cancelForms(other));
            assertEquals(MAIN, screen.pendingForm().agent());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void theFormRendersAtSmallAndNormalSizes() throws Exception {
        var labels = new String[10];
        for (var i = 0; i < labels.length; i++) labels[i] = "option number " + i + " with some more words";
        for (var size : new int[][] {{80, 24}, {40, 12}, {30, 8}}) {
            try (var ft = new FakeTerminal(size[0], size[1])) {
                var screen = new Screen(ft.terminal, Screen.Options.defaults());
                screen.showForm(prompt(MAIN, single("a", labels)), a -> { });
                var rows = screen.renderScreen(size[1], size[0]);
                assertEquals(size[1], rows.size());
                for (var r : rows) assertTrue(r.columnLength() <= size[0], r.toString());
            }
        }
    }
}
