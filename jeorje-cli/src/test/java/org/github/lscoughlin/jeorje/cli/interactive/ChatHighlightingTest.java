package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.ui.screen.ChatView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatHighlightingTest {
    private static final AgentId A = new AgentId("a");

    @TempDir
    Path tmp;

    private JeorjeConfig config(String yaml) throws Exception {
        var home = Files.createDirectories(tmp.resolve("home"));
        var cwd = Files.createDirectories(tmp.resolve("work"));
        if (!yaml.isEmpty()) {
            Files.createDirectories(cwd.resolve(".jeorje"));
            Files.writeString(cwd.resolve(".jeorje/config.yaml"), yaml);
        }
        return ConfigLoader.load(new ConfigOptions(home, cwd, Map.of(), Map.of(), null, true)).config();
    }

    @Test
    void settingsFollowTheConfigKeys() throws Exception {
        var cfg = config("""
                ui:
                  chat:
                    highlight:
                      maxLines: 7
                      diff: false
                      read: false
                      fences: false
                      aliases:
                        kts: kotlin
                """);
        var s = ChatHighlighting.settings(cfg, Map.of(), 256);
        assertTrue(s.enabled());
        assertEquals(7, s.maxLines());
        assertFalse(s.diff());
        assertFalse(s.read());
        assertFalse(s.fences());
        assertEquals("kotlin", s.aliases().get("kts"));
        assertFalse(s.noColor());
        assertFalse(s.fewColors());
    }

    @Test
    void noColorAndFewColorsComeFromTheEnvironment() throws Exception {
        var cfg = config("");
        assertTrue(ChatHighlighting.settings(cfg, Map.of("NO_COLOR", "1"), 256).noColor());
        assertFalse(ChatHighlighting.settings(cfg, Map.of("NO_COLOR", ""), 256).noColor());
        assertTrue(ChatHighlighting.settings(cfg, Map.of(), 8).fewColors());
        assertFalse(ChatHighlighting.settings(cfg, Map.of(), -1).fewColors());
    }

    @Test
    void editorSyntaxOffRemovesCodeColorsButKeepsDiffs() throws Exception {
        var cfg = config("editor:\n  syntax:\n    enabled: false\n");
        var s = ChatHighlighting.settings(cfg, Map.of(), 256);
        assertFalse(s.codeColors());
        assertTrue(s.diffRendering());
    }

    @Test
    void contextHighlightsAFencedBlockInTheChatView() throws Exception {
        var cfg = config("");
        var context = ChatHighlighting.build(cfg, Map.of(), tmp.resolve("work"), tmp.resolve("home"), 256);
        assertNotNull(context);
        var chat = new ChatView(context);
        chat.apply(new AgentEvent.TokenDelta(A, "```java\npublic class A {}\n```\n"));
        var line = chat.logicalLines().stream().filter(l -> l.toString().contains("public class A")).findFirst()
                .orElseThrow();
        assertTrue(line.toAnsi().contains("\u001b["), line.toAnsi());
    }

    @Test
    void noColorStripsAllColorsFromTheChatView() throws Exception {
        var cfg = config("");
        var context = ChatHighlighting.build(cfg, Map.of("NO_COLOR", "1"), tmp.resolve("work"),
                tmp.resolve("home"), 256);
        var chat = new ChatView(context);
        chat.appendUser("hello");
        chat.apply(new AgentEvent.TokenDelta(A, "```java\npublic class A {}\n```\n"));
        for (var line : chat.logicalLines()) {
            assertFalse(line.toAnsi().matches("(?s).*\u001b\\[[0-9;]*(3[0-7]|9[0-7]|38|4[0-7]|48)[;m].*"),
                    line.toAnsi());
        }
    }

    @Test
    void editResultIsTintedAsADiff() throws Exception {
        var cfg = config("");
        var context = ChatHighlighting.build(cfg, Map.of(), tmp.resolve("work"), tmp.resolve("home"), 256);
        var chat = new ChatView(context);
        chat.apply(new AgentEvent.ToolStarted(A, "t", "edit", "A.java"));
        chat.apply(new AgentEvent.ToolFinished(A, "t", ToolResultData.ok(
                "Edited A.java (+1 -1).\n--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n-int x = 1;\n+int y = 2;\n")));
        chat.toggleResult("t");
        var added = chat.logicalLines().stream().filter(l -> l.toString().contains("int y = 2")).findFirst()
                .orElseThrow();
        var removed = chat.logicalLines().stream().filter(l -> l.toString().contains("int x = 1")).findFirst()
                .orElseThrow();
        assertTrue(added.toAnsi().matches("(?s).*\u001b\\[[0-9;]*48;.*"), added.toAnsi());
        assertTrue(removed.toAnsi().matches("(?s).*\u001b\\[[0-9;]*48;.*"), removed.toAnsi());
        assertFalse(added.toAnsi().equals(removed.toAnsi()));
    }
}
