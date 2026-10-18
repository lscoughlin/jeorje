package org.github.lscoughlin.jeorje.ui.editor;

import java.util.Map;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;
import org.github.lscoughlin.jeorje.ui.syntax.HighlightService;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;

/**
 * The connections between the chat screen and the editor that {@link QEditor#edit(org.jline.terminal.Terminal,
 * java.nio.file.Path, EditorHooks)} starts.
 *
 * @param fileStates           receives the new state of each file that the user saves
 * @param config               the flat config values with dotted keys, for example {@code editor.syntax.injections}
 * @param keys                 the key bindings of the editor
 * @param agentChangeNotice    receives the notice "file changed by the agent" when {@code externalChange} shows it
 * @param highlight            the service that colors lines, or null for plain text
 * @param catalog              the catalog that chooses a syntax by file name, or null for plain text
 * @param clipboard            the clipboard of the editor
 * @param alternateScreenActive true when the caller already uses the alternate screen; the editor then leaves it on
 *                             at the end, and the caller must draw its screen again
 */
public record EditorHooks(FileStateSink fileStates, Map<String, ?> config, KeyBindings keys,
                          Consumer<String> agentChangeNotice, HighlightService highlight, SyntaxCatalog catalog,
                          EditorClipboard clipboard, boolean alternateScreenActive) {

    public EditorHooks {
        fileStates = fileStates == null ? FileStateSink.none() : fileStates;
        config = config == null ? Map.of() : config;
        keys = keys == null ? KeyBindings.defaults() : keys;
        agentChangeNotice = agentChangeNotice == null ? text -> { } : agentChangeNotice;
        clipboard = clipboard == null ? EditorClipboard.memory() : clipboard;
    }

    /** Hooks with the default keys, no highlight service, and no callbacks. */
    public static EditorHooks defaults() {
        return new EditorHooks(null, null, null, null, null, null, null, false);
    }

    public EditorHooks withFileStates(FileStateSink value) {
        return new EditorHooks(value, config, keys, agentChangeNotice, highlight, catalog, clipboard,
                alternateScreenActive);
    }

    public EditorHooks withConfig(Map<String, ?> value) {
        return new EditorHooks(fileStates, value, keys, agentChangeNotice, highlight, catalog, clipboard,
                alternateScreenActive);
    }

    public EditorHooks withKeys(KeyBindings value) {
        return new EditorHooks(fileStates, config, value, agentChangeNotice, highlight, catalog, clipboard,
                alternateScreenActive);
    }

    public EditorHooks withAgentChangeNotice(Consumer<String> value) {
        return new EditorHooks(fileStates, config, keys, value, highlight, catalog, clipboard,
                alternateScreenActive);
    }

    public EditorHooks withHighlight(HighlightService value) {
        return new EditorHooks(fileStates, config, keys, agentChangeNotice, value, catalog, clipboard,
                alternateScreenActive);
    }

    public EditorHooks withCatalog(SyntaxCatalog value) {
        return new EditorHooks(fileStates, config, keys, agentChangeNotice, highlight, value, clipboard,
                alternateScreenActive);
    }

    public EditorHooks withClipboard(EditorClipboard value) {
        return new EditorHooks(fileStates, config, keys, agentChangeNotice, highlight, catalog, value,
                alternateScreenActive);
    }

    public EditorHooks withAlternateScreenActive(boolean value) {
        return new EditorHooks(fileStates, config, keys, agentChangeNotice, highlight, catalog, clipboard, value);
    }
}
