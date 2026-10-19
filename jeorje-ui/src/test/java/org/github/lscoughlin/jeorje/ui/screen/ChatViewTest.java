package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

class ChatViewTest {
    private static final AgentId A = new AgentId("a");

    private static List<String> plain(List<AttributedString> lines) {
        return lines.stream().map(AttributedString::toString).toList();
    }

    @Test
    void eventsRenderInOrder() {
        var chat = new ChatView();
        chat.appendUser("hello");
        chat.apply(new AgentEvent.TokenDelta(A, "Hi there"));
        chat.apply(new AgentEvent.ToolStarted(A, "t1", "read", "src/Main.java"));
        chat.apply(new AgentEvent.ToolFinished(A, "t1", ToolResultData.ok("one line")));
        chat.apply(new AgentEvent.TurnFinished(A, 1));
        chat.apply(new AgentEvent.Error(A, "boom"));
        var lines = plain(chat.logicalLines());
        assertEquals("> hello", lines.get(0));
        assertEquals("Hi there", lines.get(1));
        assertTrue(lines.get(2).contains("read") && lines.get(2).contains("src/Main.java"));
        assertTrue(lines.get(3).contains("one line"));
        assertEquals("! boom", lines.get(4));
    }

    @Test
    void streamingTokensAppendToOneEntry() {
        var chat = new ChatView();
        chat.apply(new AgentEvent.TokenDelta(A, "Hel"));
        chat.apply(new AgentEvent.TokenDelta(A, "lo wor"));
        chat.apply(new AgentEvent.TokenDelta(A, "ld\nsecond"));
        assertEquals(List.of("Hello world", "second"), plain(chat.logicalLines()));
        chat.apply(new AgentEvent.TurnFinished(A, 1));
        chat.apply(new AgentEvent.TokenDelta(A, "next"));
        assertEquals(List.of("Hello world", "second", "next"), plain(chat.logicalLines()));
    }

    @Test
    void wrapKeepsStyles() {
        var chat = new ChatView();
        chat.apply(new AgentEvent.TokenDelta(A, "# 1234567890abcdef"));
        var wrapped = chat.wrapped(10);
        assertEquals(List.of("# 12345678", "90abcdef"), plain(wrapped));
        for (var line : wrapped) {
            assertEquals(Markdown.HEADING.getStyle(), line.styleAt(0).getStyle());
        }
    }

    @Test
    void markdownStylesHeadingsListsCodeAndInlineCode() {
        var lines = Markdown.render("# Title\n- item `x`\n```java\n  int a;\n```\nplain **bold**");
        assertEquals("# Title", lines.get(0).toString());
        assertEquals(Markdown.HEADING.getStyle(), lines.get(0).styleAt(0).getStyle());
        assertEquals("• item x", lines.get(1).toString());
        assertEquals(Markdown.CODE.getStyle(), lines.get(1).styleAt(7).getStyle());
        assertEquals("  int a;", lines.get(3).toString());
        assertEquals(Markdown.CODE.getStyle(), lines.get(3).styleAt(2).getStyle());
        assertEquals("plain bold", lines.get(5).toString());
        assertEquals(Markdown.BOLD.getStyle(), lines.get(5).styleAt(6).getStyle());
    }

    @Test
    void toolResultCollapsesAndExpands() {
        var chat = new ChatView();
        chat.apply(new AgentEvent.ToolStarted(A, "t1", "bash", "ls"));
        chat.apply(new AgentEvent.ToolFinished(A, "t1", ToolResultData.ok("a\nb\nc")));
        assertEquals(2, chat.lineCount());
        assertTrue(plain(chat.logicalLines()).get(1).contains("3 lines"));
        assertFalse(chat.isExpanded("t1"));
        assertTrue(chat.toggleLastResult());
        assertEquals(4, chat.lineCount());
        assertEquals("  b", plain(chat.logicalLines()).get(2));
        assertTrue(chat.isExpanded("t1"));
        assertTrue(chat.toggleResult("t1"));
        assertEquals(2, chat.lineCount());
    }

    @Test
    void bufferKeepsTenThousandLines() {
        var chat = new ChatView();
        for (var i = 0; i < 12_000; i++) chat.appendUser("line " + i);
        assertEquals(ChatView.MAX_LINES, chat.lineCount());
        var lines = plain(chat.logicalLines());
        assertEquals("> line 11999", lines.get(lines.size() - 1));
        assertEquals("> line 2000", lines.get(0));
    }

    @Test
    void scrollMovesTheWindow() {
        var chat = new ChatView();
        for (var i = 0; i < 10; i++) chat.appendUser("l" + i);
        assertEquals("> l9", plain(chat.visible(80, 3)).get(2));
        chat.scrollUp(2);
        assertEquals("> l7", plain(chat.visible(80, 3)).get(2));
        chat.scrollToEnd();
        assertEquals("> l9", plain(chat.visible(80, 3)).get(2));
    }

    @Test
    void clearRemovesTheLinesAndTheScrollPosition() {
        var chat = new ChatView();
        chat.appendUser("hello");
        chat.apply(new AgentEvent.TokenDelta(A, "Hi"));
        chat.scrollUp(1);
        chat.clear();
        assertEquals(0, chat.lineCount());
        assertTrue(plain(chat.logicalLines()).isEmpty());
        assertTrue(chat.following());
        chat.appendUser("again");
        assertEquals(List.of("> again"), plain(chat.logicalLines()));
    }
}
