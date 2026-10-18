package org.github.lscoughlin.jeorje.ui.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeyBindingsTest {

    private static KeyLayer ui(String source, Map<String, ?> keys) {
        return new KeyLayer(source, keys, Map.of());
    }

    private static KeyLayer editor(String source, Map<String, ?> keys) {
        return new KeyLayer(source, Map.of(), keys);
    }

    private static String error(List<KeyLayer> layers) {
        return assertThrows(KeyConfigException.class, () -> KeyBindings.resolve(layers)).getMessage();
    }

    @Test
    void registryHasAllDocumentedActions() {
        var registry = ActionRegistry.defaults();
        for (var id : List.of("send", "newline", "interrupt", "exit", "agents", "files", "expandResult", "redraw",
                "follow", "copySelection", "scrollUp", "scrollDown")) {
            assertEquals(KeyContext.CHAT, registry.find(KeyScope.UI, id).orElseThrow().context(), id);
        }
        for (var id : List.of("browserOpen", "browserClose", "browserEdit", "browserAdd", "browserFilter",
                "browserHidden", "browserExit")) {
            assertEquals(KeyContext.BROWSER, registry.find(KeyScope.UI, id).orElseThrow().context(), id);
        }
        for (var id : List.of("menu", "menuFile", "menuEdit", "menuSearch", "menuView", "menuOptions", "menuHelp",
                "save", "close", "undo", "redo", "cut", "copy", "paste", "selectAll", "find", "repeatFind", "change",
                "goToLine", "help", "switchWindow", "toggleInsert", "indent", "unindent", "wordLeft", "wordRight",
                "fileStart", "fileEnd", "left", "right", "up", "down", "lineStart", "lineEnd", "pageUp", "pageDown",
                "backspace", "delete")) {
            assertTrue(registry.find(KeyScope.EDITOR, id).isPresent(), id);
        }
        assertTrue(registry.find(KeyScope.EDITOR, "send").isEmpty());
    }

    @Test
    void defaultsMatchTheDesign() {
        var b = KeyBindings.defaults();
        assertEquals("Enter", b.display(KeyScope.UI, "send"));
        assertEquals("Alt-Enter", b.display(KeyScope.UI, "newline"));
        assertEquals("Ctrl-X a", b.display(KeyScope.UI, "agents"));
        assertEquals("Ctrl-X f", b.display(KeyScope.UI, "files"));
        assertEquals("Ctrl-X o", b.display(KeyScope.UI, "expandResult"));
        assertEquals("Ctrl-X b", b.display(KeyScope.UI, "follow"));
        assertEquals("Ctrl-X c", b.display(KeyScope.UI, "copySelection"));
        assertEquals("Ctrl-L", b.display(KeyScope.UI, "redraw"));
        assertEquals("Ctrl-Q f", b.display(KeyScope.EDITOR, "find"));
        assertEquals("Shift-Del", b.display(KeyScope.EDITOR, "cut"));
        assertEquals("Alt-X", b.display(KeyScope.EDITOR, "close"));
        assertTrue(b.warnings().isEmpty());
        assertEquals("default", b.binding(KeyScope.UI, "send").orElseThrow().source());
    }

    @Test
    void everyDefaultKeyCanBeSentAndNoActionIsEmpty() {
        for (var action : ActionRegistry.defaults().all()) {
            assertFalse(action.defaults().isEmpty(), action.id());
            for (var key : action.defaults()) {
                assertFalse(key.sequences(false).isEmpty(), action.id() + " " + key);
            }
        }
    }

    @Test
    void overrideReplacesDefaultsOfOneAction() {
        var b = KeyBindings.resolve(List.of(ui("user config", Map.of("agents", "Ctrl-X g"))));
        assertEquals("Ctrl-X g", b.display(KeyScope.UI, "agents"));
        assertEquals("user config", b.binding(KeyScope.UI, "agents").orElseThrow().source());
        assertEquals("Ctrl-X f", b.display(KeyScope.UI, "files"));
        assertEquals("default", b.binding(KeyScope.UI, "files").orElseThrow().source());
    }

    @Test
    void listValueGivesSeveralKeys() {
        var b = KeyBindings.resolve(List.of(ui("user config", Map.of("agents", List.of("Ctrl-X g", "F4")))));
        assertEquals("Ctrl-X g, F4", b.display(KeyScope.UI, "agents"));
    }

    @Test
    void noneRemovesKeys() {
        var b = KeyBindings.resolve(List.of(ui("user config", Map.of("agents", "none", "files", List.of()))));
        assertEquals("none", b.display(KeyScope.UI, "agents"));
        assertEquals("none", b.display(KeyScope.UI, "files"));
        assertTrue(b.binding(KeyScope.UI, "agents").orElseThrow().keys().isEmpty());
    }

    @Test
    void layersMergePerAction() {
        var user = ui("user config", Map.of("agents", "Ctrl-X g", "files", "Ctrl-X h"));
        var project = ui("project config", Map.of("files", "Ctrl-X j"));
        var b = KeyBindings.resolve(List.of(user, project));
        assertEquals("Ctrl-X g", b.display(KeyScope.UI, "agents"));
        assertEquals("user config", b.binding(KeyScope.UI, "agents").orElseThrow().source());
        assertEquals("Ctrl-X j", b.display(KeyScope.UI, "files"));
        assertEquals("project config", b.binding(KeyScope.UI, "files").orElseThrow().source());
        assertEquals("Ctrl-X b", b.display(KeyScope.UI, "follow"));
    }

    @Test
    void sameKeyForTwoActionsIsAnError() {
        var message = error(List.of(ui("user config", Map.of("agents", "Ctrl-X f"))));
        assertTrue(message.contains("'agents'") && message.contains("'files'") && message.contains("Ctrl-X f"),
                message);
    }

    @Test
    void sameKeyInDifferentContextsIsAllowed() {
        // Enter is send in the chat screen and browserEdit in the file browser.
        var b = KeyBindings.defaults();
        assertEquals("Enter", b.display(KeyScope.UI, "browserEdit"));
    }

    @Test
    void completeBindingThatIsAChordPrefixIsAnError() {
        var message = error(List.of(ui("user config", Map.of("redraw", "Ctrl-X"))));
        assertTrue(message.contains("chord prefix") && message.contains("'redraw'") && message.contains("Ctrl-X"),
                message);
        // The other way round: a chord that starts with an existing complete key.
        var message2 = error(List.of(ui("user config", Map.of("agents", "Ctrl-C x"))));
        assertTrue(message2.contains("chord prefix") && message2.contains("'interrupt'"), message2);
    }

    @Test
    void chordsWithTheSamePrefixAreAllowed() {
        var b = KeyBindings.resolve(List.of(ui("user config", Map.of("agents", "Ctrl-X z"))));
        assertEquals("Ctrl-X z", b.display(KeyScope.UI, "agents"));
    }

    @Test
    void requiredActionsMustKeepAKey() {
        for (var id : List.of("send", "interrupt", "exit")) {
            var message = error(List.of(ui("user config", Map.of(id, "none"))));
            assertTrue(message.contains("'" + id + "'") && message.contains("at least one key"), message);
        }
        for (var id : List.of("save", "close")) {
            var message = error(List.of(editor("user config", Map.of(id, List.of()))));
            assertTrue(message.contains("'" + id + "'") && message.contains("at least one key"), message);
        }
        // Other actions may be removed.
        KeyBindings.resolve(List.of(editor("user config", Map.of("find", "none"))));
    }

    @Test
    void aliasFormsAreRejectedInConfig() {
        for (var key : List.of("Ctrl-I", "Ctrl-M", "Ctrl-[", "Ctrl-H")) {
            var message = error(List.of(editor("user config", Map.of("find", key))));
            assertTrue(message.contains("Use '"), message);
        }
    }

    @Test
    void unknownActionAndWrongScopeAreErrors() {
        assertTrue(error(List.of(ui("user config", Map.of("nope", "x")))).contains("Unknown action 'nope'"));
        var message = error(List.of(ui("user config", Map.of("save", "Ctrl-S"))));
        assertTrue(message.contains("belongs to editor.keys"), message);
    }

    @Test
    void allErrorsAreReported() {
        var e = assertThrows(KeyConfigException.class,
                () -> KeyBindings.resolve(List.of(ui("user config", Map.of("nope", "x", "send", "none")))));
        assertEquals(2, e.errors().size());
    }

    @Test
    void actionOnAnEditingKeyWinsWithWarning() {
        var b = KeyBindings.resolve(List.of(ui("user config", Map.of("follow", "Ctrl-A"))));
        assertEquals("Ctrl-A", b.display(KeyScope.UI, "follow"));
        assertEquals(1, b.warnings().size());
        assertTrue(b.warnings().getFirst().contains("beginning-of-line"), b.warnings().getFirst());
        var map = new KeyMapBuilder(b, false).build(KeyContext.CHAT);
        assertEquals("follow", map.getBound("\001"));
    }

    @Test
    void defaultChatKeysDoNotReplaceEditingKeys() {
        var keys = new HashSet<String>();
        for (var b : KeyBindings.defaults().bindings(KeyContext.CHAT)) {
            b.keys().forEach(k -> keys.add(k.toString()));
        }
        assertTrue(KeyBindings.defaults().warnings().isEmpty());
        assertTrue(keys.contains("Ctrl-X a"));
    }

    @Test
    void keyMapBuilderBindsChordsAndNamedKeys() {
        var map = new KeyMapBuilder(KeyBindings.defaults(), false).build(KeyContext.CHAT);
        assertEquals("agents", map.getBound("\030a"));
        assertEquals("files", map.getBound("\030f"));
        assertEquals("send", map.getBound("\r"));
        assertEquals("newline", map.getBound("\033\r"));
        assertEquals("interrupt", map.getBound("\003"));
        assertEquals("exit", map.getBound("\004"));
        assertEquals("scrollUp", map.getBound("\033[5~"));
        assertEquals("redraw", map.getBound("\014"));
    }

    @Test
    void keyMapUsesOverrides() {
        var b = KeyBindings.resolve(List.of(ui("user config", Map.of("agents", List.of("Ctrl-X g", "F4")))));
        var map = new KeyMapBuilder(b, false).build(KeyContext.CHAT);
        assertEquals("agents", map.getBound("\030g"));
        assertEquals("agents", map.getBound("\033OS"));
        assertTrue(map.getBound("\030a") == null || !"agents".equals(map.getBound("\030a")));
    }

    @Test
    void browserAndEditorKeyMaps() {
        var b = KeyBindings.defaults();
        var browser = new KeyMapBuilder(b, false).build(KeyContext.BROWSER);
        assertEquals("browserEdit", browser.getBound("\r"));
        assertEquals("browserAdd", browser.getBound("a"));
        var editor = new KeyMapBuilder(b, false).build(KeyContext.EDITOR);
        assertEquals("save", editor.getBound("\023"));
        assertEquals("find", editor.getBound("\021f"));
        assertEquals("change", editor.getBound("\021a"));
        assertEquals("close", editor.getBound("\033x"));
        assertEquals("copy", editor.getBound("\033[2;5~"));
        assertEquals("cut", editor.getBound("\033[3;2~"));
        assertEquals("paste", editor.getBound("\033[2;2~"));
        assertEquals("unindent", editor.getBound("\033[Z"));
        assertEquals("menu", editor.getBound("\033[21~"));
    }

    @Test
    void editorMovementHasDerivedSelectionKeys() {
        var editor = new KeyMapBuilder(KeyBindings.defaults(), false).build(KeyContext.EDITOR);
        assertEquals("left", editor.getBound("\033[D"));
        assertEquals("select:left", editor.getBound("\033[1;2D"));
        assertEquals("select:pageDown", editor.getBound("\033[6;2~"));
        assertEquals("wordLeft", editor.getBound("\033[1;5D"));
    }

    @Test
    void derivedSelectionKeyYieldsToExplicitBinding() {
        var b = KeyBindings.resolve(List.of(editor("user config", Map.of("wordLeft", "Shift-Left"))));
        var editor = new KeyMapBuilder(b, false).build(KeyContext.EDITOR);
        assertEquals("wordLeft", editor.getBound("\033[1;2D"));
    }

    @Test
    void kittyFormsAreBoundOnlyWhenEnabled() {
        var b = KeyBindings.resolve(List.of(editor("user config", Map.of("find", "Ctrl-Shift-F"))));
        var plain = new KeyMapBuilder(b, false).build(KeyContext.EDITOR);
        assertTrue(plain.getBound("\033[102;6u") == null);
        var kitty = new KeyMapBuilder(b, true).build(KeyContext.EDITOR);
        assertEquals("find", kitty.getBound("\033[102;6u"));
        assertEquals("save", kitty.getBound("\023"));
        assertEquals("save", kitty.getBound("\033[115;5u"));
    }

    @Test
    void keyboardProtocolParsing() {
        assertEquals(KeyboardProtocol.AUTO, KeyboardProtocol.parse(null));
        assertEquals(KeyboardProtocol.AUTO, KeyboardProtocol.parse("Auto"));
        assertEquals(KeyboardProtocol.ON, KeyboardProtocol.parse("on"));
        assertEquals(KeyboardProtocol.OFF, KeyboardProtocol.parse("OFF"));
        assertThrows(IllegalArgumentException.class, () -> KeyboardProtocol.parse("maybe"));
    }

    @Test
    void keyHelpShowsKeysAndSources() {
        var b = KeyBindings.resolve(List.of(
                ui("user config", Map.of("agents", "Ctrl-X g")),
                new KeyLayer("project config", Map.of("files", "none"), Map.of("save", "F2"))));
        var help = KeyHelp.render(b);
        assertTrue(help.contains("ui.keys") && help.contains("editor.keys"), help);
        assertTrue(help.contains("chat screen") && help.contains("file browser") && help.contains("editor"), help);
        var lines = help.lines().toList();
        var agents = lines.stream().filter(l -> l.trim().startsWith("agents ")).findFirst().orElseThrow();
        assertTrue(agents.contains("Ctrl-X g") && agents.contains("(user config)"), agents);
        var files = lines.stream().filter(l -> l.trim().startsWith("files ")).findFirst().orElseThrow();
        assertTrue(files.contains("none") && files.contains("(project config)"), files);
        var save = lines.stream().filter(l -> l.trim().startsWith("save ")).findFirst().orElseThrow();
        assertTrue(save.contains("F2") && save.contains("(project config)"), save);
        var send = lines.stream().filter(l -> l.trim().startsWith("send ")).findFirst().orElseThrow();
        assertTrue(send.contains("Enter") && send.contains("(default)"), send);
        // The help shows the current keys, not the defaults.
        assertFalse(agents.contains("Ctrl-X a"));
    }

    @Test
    void keyHelpForOneScope() {
        var help = KeyHelp.render(KeyBindings.defaults(), KeyScope.EDITOR);
        assertTrue(help.startsWith("editor.keys"));
        assertFalse(help.contains("agents"));
    }
}
