package org.github.lscoughlin.jeorje.ui.keys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** All action ids with their default keys. */
public final class ActionRegistry {

    /** The movement actions. The Shift form of each one selects text. Jeorje derives that form. */
    public static final Set<String> MOVEMENT_ACTIONS =
            Set.of("left", "right", "up", "down", "lineStart", "lineEnd", "pageUp", "pageDown");

    private static final ActionRegistry DEFAULT = new ActionRegistry();

    private final List<Action> actions = new ArrayList<>();

    private ActionRegistry() {
        chat("send", "Send the input", true, "Enter");
        chat("newline", "New line in the input", false, "Alt-Enter");
        chat("interrupt", "Interrupt the foreground agent. Two presses in 1 s exit.", true, "Ctrl-C", "Ctrl-D");
        chat("agents", "Open or close the agent switcher", false, "Ctrl-X a");
        chat("files", "Open the file browser", false, "Ctrl-X f");
        chat("expandResult", "Expand or collapse the last tool result", false, "Ctrl-X o");
        chat("redraw", "Redraw the screen", false, "Ctrl-L");
        chat("follow", "Jump to the bottom of the chat view and follow new output", false, "Ctrl-X b");
        chat("copySelection", "Copy the selected text in the chat view", false, "Ctrl-X c");
        chat("scrollUp", "Scroll the chat view up", false, "PgUp");
        chat("scrollDown", "Scroll the chat view down", false, "PgDn");

        browser("browserOpen", "Open a directory and move into it", "Right");
        browser("browserClose", "Close a directory or move to the parent", "Left");
        browser("browserEdit", "Open the file in the editor", "Enter");
        browser("browserAdd", "Add the path to the prompt", "a");
        browser("browserFilter", "Filter by name or by glob", "/");
        browser("browserHidden", "Show or hide the data of git and of Jeorje", ".");
        browser("browserExit", "Close the browser", "Esc");

        editor("menu", "Open the menu bar", false, "F10");
        editor("menuFile", "Open the File menu", false, "Alt-F");
        editor("menuEdit", "Open the Edit menu", false, "Alt-E");
        editor("menuSearch", "Open the Search menu", false, "Alt-S");
        editor("menuView", "Open the View menu", false, "Alt-V");
        editor("menuOptions", "Open the Options menu", false, "Alt-O");
        editor("menuHelp", "Open the Help menu", false, "Alt-H");
        editor("save", "Save", true, "Ctrl-S");
        editor("close", "Close the editor and return to the chat", true, "Alt-X");
        editor("undo", "Undo", false, "Ctrl-Z");
        editor("redo", "Redo", false, "Ctrl-Y");
        editor("cut", "Cut the selection", false, "Shift-Del");
        editor("copy", "Copy the selection", false, "Ctrl-Ins");
        editor("paste", "Paste", false, "Shift-Ins");
        editor("selectAll", "Select all text", false, "Ctrl-A");
        editor("find", "Find", false, "Ctrl-Q f");
        editor("repeatFind", "Repeat the last find", false, "F3");
        editor("change", "Find and replace", false, "Ctrl-Q a");
        editor("goToLine", "Go to a line", false, "Ctrl-G");
        editor("help", "Show the key help", false, "F1");
        editor("switchWindow", "Move the cursor to the other split window", false, "F6");
        editor("toggleInsert", "Switch between insert and overwrite", false, "Ins");
        editor("indent", "Indent", false, "Tab");
        editor("unindent", "Unindent", false, "Shift-Tab");
        editor("wordLeft", "Move one word left", false, "Ctrl-Left");
        editor("wordRight", "Move one word right", false, "Ctrl-Right");
        editor("fileStart", "Go to the start of the file", false, "Ctrl-Home");
        editor("fileEnd", "Go to the end of the file", false, "Ctrl-End");
        editor("left", "Move left", false, "Left");
        editor("right", "Move right", false, "Right");
        editor("up", "Move up", false, "Up");
        editor("down", "Move down", false, "Down");
        editor("lineStart", "Go to the start of the line", false, "Home");
        editor("lineEnd", "Go to the end of the line", false, "End");
        editor("pageUp", "Move one page up", false, "PgUp");
        editor("pageDown", "Move one page down", false, "PgDn");
        editor("backspace", "Delete the character before the cursor", false, "Backspace");
        editor("delete", "Delete the character at the cursor", false, "Del");
    }

    /** The registry with the built-in actions. */
    public static ActionRegistry defaults() {
        return DEFAULT;
    }

    private void chat(String id, String description, boolean required, String... keys) {
        add(id, KeyContext.CHAT, description, required, keys);
    }

    private void browser(String id, String description, String... keys) {
        add(id, KeyContext.BROWSER, description, false, keys);
    }

    private void editor(String id, String description, boolean required, String... keys) {
        add(id, KeyContext.EDITOR, description, required, keys);
    }

    private void add(String id, KeyContext context, String description, boolean required, String... keys) {
        var specs = new ArrayList<KeySpec>();
        for (var key : keys) {
            specs.add(KeySpec.parse(key));
        }
        actions.add(new Action(id, context, description, specs, required));
    }

    /** All actions in table order. */
    public List<Action> all() {
        return List.copyOf(actions);
    }

    /** All actions of one scope in table order. */
    public List<Action> actions(KeyScope scope) {
        return actions.stream().filter(a -> a.scope() == scope).toList();
    }

    /** All actions of one context in table order. */
    public List<Action> actions(KeyContext context) {
        return actions.stream().filter(a -> a.context() == context).toList();
    }

    /** The action with this id in this scope. */
    public Optional<Action> find(KeyScope scope, String id) {
        return actions.stream().filter(a -> a.scope() == scope && a.id().equals(id)).findFirst();
    }

    /** The action ids of one scope, keyed by id. */
    public Map<String, Action> byId(KeyScope scope) {
        var map = new LinkedHashMap<String, Action>();
        actions(scope).forEach(a -> map.put(a.id(), a));
        return map;
    }
}
