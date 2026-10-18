package org.github.lscoughlin.jeorje.ui.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.github.lscoughlin.jeorje.ui.chat.LanguageResolver;
import org.github.lscoughlin.jeorje.ui.editor.markdown.InjectionOptions;
import org.github.lscoughlin.jeorje.ui.editor.markdown.InjectionRule;
import org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownHighlighter;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;
import org.github.lscoughlin.jeorje.ui.keys.KeyContext;
import org.github.lscoughlin.jeorje.ui.keys.KeyMapBuilder;
import org.github.lscoughlin.jeorje.ui.keys.KeyScope;
import org.github.lscoughlin.jeorje.ui.keys.KeySpec;
import org.github.lscoughlin.jeorje.ui.mouse.ClickCounter;
import org.github.lscoughlin.jeorje.ui.mouse.MouseConfig;
import org.github.lscoughlin.jeorje.ui.mouse.MouseInput;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.Region;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.github.lscoughlin.jeorje.ui.mouse.RegionRegistry;
import org.github.lscoughlin.jeorje.ui.syntax.HighlightService;
import org.github.lscoughlin.jeorje.ui.syntax.HighlighterFactory;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.keymap.KeyMap;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.MouseSupport;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A modeless, menu-driven text editor in the style of the MS-DOS QBasic editor.
 *
 * <p>The editor has no command mode. A printable character always goes into the text. Commands use the menus,
 * Alt keys, function keys, and Ctrl keys. All keys come from the {@link KeyBindings}.
 *
 * <p>The class has two ways to drive it. {@link #run(Terminal)} runs the editor on a JLine terminal. Tests and
 * other callers can send keys with {@link #feed(String)} and mouse events with {@link #mouse(MouseEvent)}, and
 * read the screen with {@link #render()}.
 */
public final class QEditor {

    private static final Logger log = LoggerFactory.getLogger(QEditor.class);

    /** The start of the notice that {@link #externalChange(Path)} shows. */
    public static final String CHANGED_BY_AGENT = "File changed by the agent";

    private static final String[] MENU_ACTIONS = {"menuFile", "menuEdit", "menuSearch", "menuView", "menuOptions",
        "menuHelp"};

    /** Builds a {@link QEditor}. */
    public static final class Builder {
        private KeyBindings keys = KeyBindings.defaults();
        private boolean kitty;
        private EditorClipboard clipboard = EditorClipboard.memory();
        private SyntaxCatalog catalog;
        private HighlightService highlight;
        private FileStateSink sink = FileStateSink.none();
        private EditorOptions options = EditorOptions.defaults();
        private Path baseDir = Path.of("").toAbsolutePath();
        private LongSupplier clock = System::currentTimeMillis;
        private boolean copyOnSelect;
        private InjectionOptions injections = InjectionOptions.defaults();
        private HighlighterFactory highlighterFactory = HighlighterFactory.jline();
        private Consumer<String> noticeSink = text -> { };

        /** The settings of the Markdown injection layer ({@code editor.syntax.injections}). */
        public Builder injections(InjectionOptions value) {
            this.injections = value;
            return this;
        }

        /** The factory that makes the highlighters of the injection layer. */
        public Builder highlighterFactory(HighlighterFactory value) {
            this.highlighterFactory = value;
            return this;
        }

        /** Receives the notice that {@link #externalChange(Path)} shows. */
        public Builder noticeSink(Consumer<String> value) {
            this.noticeSink = value;
            return this;
        }

        /** True copies the selection to the clipboard when the user releases the mouse button after a drag. */
        public Builder copyOnSelect(boolean value) {
            this.copyOnSelect = value;
            return this;
        }

        public Builder keys(KeyBindings value) {
            this.keys = value;
            return this;
        }

        /** Whether the terminal runs the Kitty keyboard protocol, so that the editor binds its key forms. */
        public Builder kitty(boolean value) {
            this.kitty = value;
            return this;
        }

        public Builder clipboard(EditorClipboard value) {
            this.clipboard = value;
            return this;
        }

        /** The catalog that chooses a syntax by file name. May be null for plain text. */
        public Builder catalog(SyntaxCatalog value) {
            this.catalog = value;
            return this;
        }

        /** The service that gives styled lines. May be null for plain text. */
        public Builder highlight(HighlightService value) {
            this.highlight = value;
            return this;
        }

        /** Where the editor reports a saved file. */
        public Builder fileStates(FileStateSink value) {
            this.sink = value;
            return this;
        }

        public Builder options(EditorOptions value) {
            this.options = value;
            return this;
        }

        /** The directory that relative paths in the Save As dialog refer to. */
        public Builder baseDir(Path value) {
            this.baseDir = value.toAbsolutePath().normalize();
            return this;
        }

        /** The clock for double click and triple click detection. */
        public Builder clock(LongSupplier value) {
            this.clock = value;
            return this;
        }

        public QEditor build() {
            return new QEditor(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- services ----
    private final KeyBindings keys;
    private final boolean kitty;
    private final KeyMap<String> keyMap;
    private final EditorClipboard clipboard;
    private final SyntaxCatalog catalog;
    private final HighlightService highlight;
    private final FileStateSink sink;
    private final Path baseDir;
    private final ClickCounter clicks;
    private final boolean copyOnSelect;
    private final InjectionOptions injectionOptions;
    private final HighlighterFactory highlighterFactory;
    private final LanguageResolver resolver;
    private final Consumer<String> noticeSink;

    // ---- state ----
    private EditorOptions options;
    private Palette palette;
    private final List<EditorWindow> windows = new ArrayList<>();
    private int active;
    private boolean overwrite;
    private boolean closed;
    private volatile String notice;
    private Dialog dialog;
    private DiffView diff;
    private int menuIndex = -1;
    private int itemIndex;
    private int width = 80;
    private int height = 24;
    private String pending = "";
    private volatile boolean redraw = true;

    // ---- find state ----
    private String lastFind = "";
    private String lastReplace = "";
    private boolean matchCase;
    private boolean wholeWord;

    // ---- mouse state ----
    private RegionRegistry registry = new RegionRegistry();
    private boolean dragging;
    private int dragAnchor;
    private EditorWindow dragWindow;
    private RegionEvent lastDrag;
    private Region captured;

    private QEditor(Builder b) {
        this.keys = b.keys;
        this.kitty = b.kitty;
        this.clipboard = b.clipboard;
        this.catalog = b.catalog;
        this.highlight = b.highlight;
        this.sink = b.sink;
        this.baseDir = b.baseDir;
        this.options = b.options;
        this.palette = options.noColor() ? Palette.mono() : Palette.dark();
        this.clicks = new ClickCounter(b.clock);
        this.copyOnSelect = b.copyOnSelect;
        this.injectionOptions = b.injections;
        this.highlighterFactory = b.highlighterFactory;
        this.noticeSink = b.noticeSink;
        this.resolver = b.catalog == null ? null : new LanguageResolver(b.catalog, Map.of());
        this.keyMap = buildKeyMap();
        var doc = newDocument("");
        windows.add(new EditorWindow(doc, options.wheelLines(), () -> this.options));
    }

    // ================================================================
    // Public API
    // ================================================================

    /** Opens a file in the active window. A path that does not exist opens an empty new file. */
    public void open(Path file) throws IOException {
        var path = baseDir.resolve(file).toAbsolutePath().normalize();
        var loaded = FileIo.load(path);
        var doc = newDocument(loaded.text());
        doc.path = path;
        doc.eol = loaded.eol();
        doc.diskState = loaded.state();
        doc.bytes = loaded.bytes();
        chooseSyntax(doc);
        doc.openView();
        showNotices(doc);
        windows.get(active).setDocument(doc);
        diff = null;
        dialog = null;
        menuIndex = -1;
        applyReveal();
    }

    /** Opens a file in the other window and splits the screen when needed. */
    public void openInOtherWindow(Path file) throws IOException {
        if (windows.size() == 1) {
            toggleSplit();
        }
        var previous = active;
        active = 1 - active;
        try {
            open(file);
        } finally {
            if (windows.size() > 1) {
                active = previous;
            }
        }
    }

    /** Sets the size of the screen. */
    public void setSize(int columns, int rows) {
        this.width = Math.max(1, columns);
        this.height = Math.max(1, rows);
        redraw = true;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The text of the active window. It uses {@code \n} as the line end. */
    public String text() {
        return win().doc.buffer.text();
    }

    public boolean isModified() {
        return win().doc.isModified();
    }

    public Path path() {
        return win().doc.path;
    }

    /** The cursor as an offset in the text. */
    public int cursor() {
        return win().cursor;
    }

    /** The selected text, or an empty string. */
    public String selectedText() {
        var w = win();
        return w.hasSelection() ? w.doc.buffer.substring(w.selectionStart(), w.selectionEnd()) : "";
    }

    /** The line of the cursor, counted from 1. */
    public int line() {
        return win().doc.buffer.lineOf(win().cursor) + 1;
    }

    /** The display column of the cursor, counted from 1. */
    public int column() {
        return win().cursorColumn() + 1;
    }

    public boolean isOverwrite() {
        return overwrite;
    }

    public EditorOptions options() {
        return options;
    }

    /** The name of the syntax in use, or {@code Plain Text}. */
    public String syntaxName() {
        var syntax = win().doc.syntax;
        return syntax == null ? "Plain Text" : syntax.name();
    }

    /** The number of text windows: 1 or 2. */
    public int windowCount() {
        return windows.size();
    }

    /** The index of the window that has the cursor. */
    public int activeWindow() {
        return active;
    }

    /** The first visible row of a window. */
    public int scrollTop(int window) {
        var w = windows.get(window);
        layoutWindows();
        w.sync();
        return w.scroll.offset();
    }

    public boolean menuOpen() {
        return menuIndex >= 0;
    }

    public boolean dialogOpen() {
        return dialog != null;
    }

    public String dialogTitle() {
        return dialog == null ? "" : dialog.title;
    }

    public boolean diffOpen() {
        return diff != null;
    }

    /** The current status text of the status bar (the last screen row). */
    public String statusText() {
        var rows = screenText();
        return rows.get(rows.size() - 1);
    }

    /** True when the file on disk changed after the editor read it. */
    public boolean diskChanged() {
        return win().doc.diskChanged;
    }

    /** The screen as plain text, one string for each row. */
    public List<String> screenText() {
        var out = new ArrayList<String>();
        for (var line : render()) {
            out.add(line.toString());
        }
        return out;
    }

    /**
     * Sends keys to the editor, as a terminal would send them. The sequences are the byte forms of the keys. An
     * incomplete sequence at the end of the input counts as complete, like a key timeout.
     */
    public void feed(String input) {
        pending += input;
        decodeAll(true);
        pending = "";
    }

    /** Sends a mouse event to the editor. */
    public void mouse(MouseEvent event) {
        var count = switch (event.getType()) {
            case Pressed -> clicks.press(event.getX(), event.getY());
            case Dragged, Released -> clicks.current();
            default -> 0;
        };
        notice = event.getType() == MouseEvent.Type.Pressed ? null : notice;
        redraw = true;
        var type = event.getType();
        if ((type == MouseEvent.Type.Dragged || type == MouseEvent.Type.Released) && captured != null) {
            // A drag stays with the region where the button went down, also after the layout changed.
            var region = captured;
            var b = region.bounds();
            if (type == MouseEvent.Type.Released) {
                captured = null;
            }
            region.onMouse(new RegionEvent(type, event.getButton(), event.getModifiers(), event.getX() - b.x(),
                    event.getY() - b.y(), count));
            applyReveal();
            return;
        }
        layoutWindows();
        buildRegions();
        var target = registry.dispatch(event, count);
        captured = type == MouseEvent.Type.Pressed ? target.orElse(null) : null;
        if (type == MouseEvent.Type.Released) {
            captured = null;
        }
    }

    /**
     * Does the periodic work: it checks the file on disk and continues a scroll caused by a drag outside the window.
     */
    public void tick() {
        checkDisk();
        if (dragging && lastDrag != null && dragWindow != null) {
            dragSelect(dragWindow, lastDrag);
            redraw = true;
        }
    }

    /**
     * Tells the editor that the agent changed a file. When the file is open, the status bar shows the notice
     * "file changed by the agent" and offers Reload and Show Changes. Other paths are ignored.
     */
    public void externalChange(Path file) {
        var path = baseDir.resolve(file).toAbsolutePath().normalize();
        for (var w : windows) {
            if (path.equals(w.doc.path)) {
                w.doc.diskChanged = true;
                var text = CHANGED_BY_AGENT + ": " + displayPath(path) + ".";
                notice = text;
                redraw = true;
                noticeSink.accept(text);
                return;
            }
        }
    }

    /** Checks whether the files on disk changed. A change shows a notice and offers Reload. */
    public void checkDisk() {
        for (var w : windows) {
            diskCheck(w.doc);
        }
    }

    private void diskCheck(EditorDocument doc) {
        if (doc.path == null || doc.diskChanged) {
            return;
        }
        try {
            var known = doc.diskState;
            var exists = Files.isRegularFile(doc.path);
            if (!exists) {
                if (known != null) {
                    doc.diskChanged = true;
                    redraw = true;
                }
                return;
            }
            if (known != null && Files.getLastModifiedTime(doc.path).toMillis() == known.modifiedMillis()) {
                return;
            }
            var now = FileIo.stateOf(doc.path);
            if (now == null) {
                return;
            }
            if (known == null || !known.hash().equals(now.hash())) {
                doc.diskChanged = true;
                redraw = true;
            } else {
                doc.diskState = now;
            }
        } catch (IOException e) {
            log.atDebug().log(() -> "cannot check " + doc.path + ": " + e.getMessage());
        }
    }

    // ================================================================
    // Key map and key decoding
    // ================================================================

    private KeyMap<String> buildKeyMap() {
        var map = new KeyMapBuilder(keys, kitty).build(KeyContext.EDITOR);
        // The Shift form of the word and file movements.
        for (var id : List.of("wordLeft", "wordRight", "fileStart", "fileEnd")) {
            keys.binding(KeyScope.EDITOR, id).ifPresent(binding -> {
                for (var key : binding.keys()) {
                    if (key.keys().size() == 1 && !key.keys().getFirst().shift()) {
                        var k = key.keys().getFirst();
                        var shifted = new KeySpec(List.of(new KeySpec.Key(k.base(), k.ctrl(), k.alt(), true)));
                        for (var seq : shifted.sequences(kitty)) {
                            map.bindIfNotBound("select:" + id, seq);
                        }
                    }
                }
            });
        }
        // Fixed keys. A configured key wins.
        for (var seq : List.of("\r", "\n")) {
            map.bindIfNotBound("enter", seq);
        }
        map.bindIfNotBound("escape", "\u001b");
        map.bindIfNotBound("alias:copy", "\u0003");
        map.bindIfNotBound("alias:paste", "\u0016");
        map.bindIfNotBound("alias:cut", "\u0018");
        return map;
    }

    /**
     * Decodes the first key of {@code pending}. Returns false when nothing was consumed. With {@code flush} false,
     * a prefix of a longer key (such as Esc or the first key of a chord) stays in {@code pending} until more input
     * comes or a timeout calls the method with {@code flush} true.
     */
    private boolean decodeOne(boolean flush) {
        var first = pending.codePointAt(0);
        if (first >= KeyMap.KEYMAP_LENGTH) {
            var text = new String(Character.toChars(first));
            pending = pending.substring(text.length());
            dispatch("text", text);
            return true;
        }
        var remaining = new int[1];
        var op = keyMap.getBound(pending, remaining);
        if (remaining[0] < 0 && !flush) {
            return false;
        }
        var consumed = remaining[0] >= 0 ? pending.length() - remaining[0] : pending.length();
        if (op == null) {
            if (consumed <= 0) {
                var c = pending.charAt(0);
                pending = pending.substring(1);
                if (c >= 32 && c != 127) {
                    dispatch("text", String.valueOf(c));
                }
                return true;
            }
            var dropped = consumed;
            if (pending.startsWith("\u001b[")) {
                while (dropped < pending.length() && pending.charAt(dropped) >= 0x20 && pending.charAt(dropped) <= 0x3f) {
                    dropped++;
                }
                if (dropped < pending.length() && pending.charAt(dropped) >= 0x40 && pending.charAt(dropped) <= 0x7e) {
                    dropped++;
                }
            }
            pending = pending.substring(Math.min(dropped, pending.length()));
            return true;
        }
        var text = pending.substring(0, Math.max(1, consumed));
        pending = pending.substring(text.length());
        if (op.equals("mouse")) {
            readMouse(text);
            return true;
        }
        dispatch(op, text);
        return true;
    }

    private void decodeAll(boolean flush) {
        while (!pending.isEmpty() && !closed) {
            if (!decodeOne(flush)) {
                break;
            }
        }
    }

    // ---- terminal input, set by run ----
    private Terminal mouseTerminal;
    private NonBlockingReader inputReader;

    private void readMouse(String prefix) {
        if (mouseTerminal == null) {
            return;
        }
        var event = mouseTerminal.readMouseEvent(this::nextMouseChar, prefix);
        if (event != null) {
            mouse(event);
        }
    }

    private int nextMouseChar() {
        if (!pending.isEmpty()) {
            var c = pending.charAt(0);
            pending = pending.substring(1);
            return c;
        }
        try {
            var c = inputReader.read(100);
            return c < 0 ? -1 : c;
        } catch (IOException e) {
            return -1;
        }
    }

    // ================================================================
    // Dispatch
    // ================================================================

    private void dispatch(String op, String text) {
        if (closed) {
            return;
        }
        redraw = true;
        notice = null;
        if (dialog != null) {
            dialogKey(op, text);
        } else if (diff != null) {
            diffKey(op);
        } else if (menuIndex >= 0) {
            menuKey(op, text);
        } else {
            textKey(op, text);
        }
        for (var w : windows) {
            var all = w.doc.takeNotices();
            if (!all.isEmpty()) {
                notice = all.getLast();
            }
        }
        applyReveal();
    }

    /** Scrolls each window whose cursor must be in view. A wheel or scroll bar action does not set this flag. */
    private void applyReveal() {
        layoutWindows();
        for (var w : windows) {
            if (w.reveal) {
                w.revealCursor();
            }
        }
    }

    private EditorWindow win() {
        return windows.get(active);
    }

    private EditorDocument newDocument(String text) {
        var doc = new EditorDocument(text, highlight, this::injectionFor);
        doc.undo.markClean();
        return doc;
    }

    /** The Markdown rule for a Markdown syntax when the injection layer is on, else null. */
    private InjectionRule injectionFor(SyntaxInfo syntax) {
        if (resolver == null || !injectionOptions.enabled() || syntax == null
                || !syntax.name().equalsIgnoreCase("markdown")) {
            return null;
        }
        return new MarkdownHighlighter(syntax, resolver, highlighterFactory, injectionOptions);
    }

    private void chooseSyntax(EditorDocument doc) {
        if (doc.chosenSyntax != null) {
            doc.syntax = doc.chosenSyntax;
        } else if (catalog != null && doc.path != null) {
            doc.syntax = catalog.forFileName(doc.path.toString()).orElse(null);
        } else {
            doc.syntax = null;
        }
    }

    private void showNotices(EditorDocument doc) {
        doc.takeNotices().forEach(n -> notice = n);
    }

    // ================================================================
    // Text mode keys
    // ================================================================

    private void textKey(String op, String text) {
        switch (op) {
            case "text" -> type(text);
            case "enter" -> newline();
            case "escape" -> {
                win().anchor = -1;
            }
            case "alias:copy" -> {
                if (win().hasSelection()) {
                    command("copy");
                }
            }
            case "alias:cut" -> {
                if (win().hasSelection()) {
                    command("cut");
                }
            }
            case "alias:paste" -> {
                if (!clipboard.paste().isEmpty()) {
                    command("paste");
                }
            }
            default -> {
                if (op.startsWith(KeyMapBuilder.SELECT_PREFIX)) {
                    move(op.substring(KeyMapBuilder.SELECT_PREFIX.length()), true);
                } else {
                    command(op);
                }
            }
        }
    }

    /** Runs a command by its id. The menus and the keys both come here. */
    private void command(String id) {
        switch (id) {
            case "left", "right", "up", "down", "lineStart", "lineEnd", "pageUp", "pageDown", "wordLeft",
                 "wordRight", "fileStart", "fileEnd" -> move(id, false);
            case "backspace" -> backspace();
            case "delete" -> delete();
            case "clear" -> {
                if (win().hasSelection()) {
                    deleteSelection(UndoStack.Kind.NONE);
                } else {
                    notice = "Nothing is selected.";
                }
            }
            case "indent" -> indent();
            case "unindent" -> unindent();
            case "toggleInsert" -> overwrite = !overwrite;
            case "undo" -> undo();
            case "redo" -> redo();
            case "cut" -> cut();
            case "copy" -> copy();
            case "paste" -> paste();
            case "selectAll" -> {
                var w = win();
                w.anchor = 0;
                w.cursor = w.doc.buffer.length();
                w.undoBreak();
            }
            case "save" -> save(win().doc, null);
            case "saveAs" -> saveAsDialog(win().doc, null);
            case "revert" -> revert();
            case "reload" -> reload();
            case "close" -> closeEditor();
            case "find" -> findDialog();
            case "repeatFind" -> repeatFind();
            case "change" -> changeDialog();
            case "goToLine" -> goToLineDialog();
            case "help" -> helpDialog();
            case "about" -> aboutDialog();
            case "switchWindow" -> switchWindow();
            case "menu" -> openMenu(0);
            case "menuFile" -> openMenu(0);
            case "menuEdit" -> openMenu(1);
            case "menuSearch" -> openMenu(2);
            case "menuView" -> openMenu(3);
            case "menuOptions" -> openMenu(4);
            case "menuHelp" -> openMenu(5);
            case "toggleLineNumbers" -> options = options.withLineNumbers(!options.lineNumbers());
            case "toggleWrap" -> options = options.withWrap(!options.wrap());
            case "toggleWhitespace" -> options = options.withShowWhitespace(!options.showWhitespace());
            case "split" -> toggleSplit();
            case "showChanges" -> showChanges();
            case "tabWidth" -> tabWidthDialog();
            case "toggleSpaces" -> options = options.withUseSpaces(!options.useSpaces());
            case "toggleSyntax" -> {
                options = options.withSyntaxColors(!options.syntaxColors());
                notice = options.syntaxColors() ? "Syntax colors are on." : "Syntax colors are off.";
            }
            case "chooseSyntax" -> syntaxDialog();
            case "colors" -> {
                options = options.withNoColor(!options.noColor());
                palette = options.noColor() ? Palette.mono() : Palette.dark();
            }
            default -> log.atDebug().log(() -> "unknown editor command " + id);
        }
    }

    // ---- movement ----

    private void move(String id, boolean select) {
        var w = win();
        var buffer = w.doc.buffer;
        if (select && w.anchor < 0) {
            w.anchor = w.cursor;
        }
        var keepColumn = false;
        var target = w.cursor;
        switch (id) {
            case "left" -> target = previousOffset(w.cursor);
            case "right" -> target = nextOffset(w.cursor);
            case "up", "down" -> {
                keepColumn = true;
                target = verticalTarget(w, id.equals("up") ? -1 : 1);
            }
            case "pageUp", "pageDown" -> {
                keepColumn = true;
                var rows = Math.max(1, w.textHeight() - 1);
                var direction = id.equals("pageUp") ? -1 : 1;
                w.sync();
                w.scroll.shift(direction * rows);
                target = verticalTarget(w, direction * rows);
            }
            case "lineStart" -> target = buffer.lineStart(buffer.lineOf(w.cursor));
            case "lineEnd" -> target = buffer.lineEnd(buffer.lineOf(w.cursor));
            case "wordLeft" -> target = wordLeft(buffer, w.cursor);
            case "wordRight" -> target = wordRight(buffer, w.cursor);
            case "fileStart" -> target = 0;
            case "fileEnd" -> target = buffer.length();
            default -> { }
        }
        if (!keepColumn) {
            w.desiredColumn = -1;
        }
        w.cursor = target;
        if (!select) {
            w.anchor = -1;
        }
        w.reveal = true;
        w.undoBreak();
    }

    private int previousOffset(int offset) {
        var buffer = win().doc.buffer;
        if (offset <= 0) {
            return 0;
        }
        if (offset >= 2 && Character.isLowSurrogate(buffer.charAt(offset - 1))
                && Character.isHighSurrogate(buffer.charAt(offset - 2))) {
            return offset - 2;
        }
        return offset - 1;
    }

    private int nextOffset(int offset) {
        var buffer = win().doc.buffer;
        if (offset >= buffer.length()) {
            return buffer.length();
        }
        if (offset + 1 < buffer.length() && Character.isHighSurrogate(buffer.charAt(offset))
                && Character.isLowSurrogate(buffer.charAt(offset + 1))) {
            return offset + 2;
        }
        return offset + 1;
    }

    private int verticalTarget(EditorWindow w, int rows) {
        var row = w.cursorRow();
        var count = w.rowCount();
        var next = Math.clamp((long) row + rows, 0, count - 1);
        if (next == row) {
            return w.cursor;
        }
        if (w.desiredColumn < 0) {
            w.desiredColumn = w.cursorColumn() - w.rowStartColumn(row);
        }
        var line = w.rowLine((int) next);
        var text = w.doc.buffer.line(line);
        var absolute = w.rowStartColumn((int) next) + w.desiredColumn;
        if (w.opts().wrap()) {
            var rowEnd = w.rowStartColumn((int) next) + w.textWidth() - 1;
            var lastRowOfLine = line + 1 >= w.doc.buffer.lineCount()
                    ? next == count - 1 : next + 1 == w.firstRowOfLine(line + 1);
            if (!lastRowOfLine) {
                absolute = Math.min(absolute, rowEnd);
            }
        }
        return w.doc.buffer.lineStart(line) + w.charAtCol(text, absolute);
    }

    private static int wordLeft(TextBuffer buffer, int offset) {
        var i = offset;
        while (i > 0 && !TextSearch.isWordChar(buffer.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && TextSearch.isWordChar(buffer.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int wordRight(TextBuffer buffer, int offset) {
        var n = buffer.length();
        var i = offset;
        while (i < n && TextSearch.isWordChar(buffer.charAt(i))) {
            i++;
        }
        while (i < n && !TextSearch.isWordChar(buffer.charAt(i))) {
            i++;
        }
        return i;
    }

    // ---- editing ----

    /** Replaces a range of the active window and puts the cursor after the new text. */
    private void edit(int start, int end, String insert, int cursorAfter, UndoStack.Kind kind) {
        var w = win();
        w.doc.replace(start, end - start, insert, w.cursor, cursorAfter, kind);
        w.cursor = cursorAfter;
        w.anchor = -1;
        w.desiredColumn = -1;
        w.reveal = true;
    }

    private void deleteSelection(UndoStack.Kind kind) {
        var w = win();
        var start = w.selectionStart();
        edit(start, w.selectionEnd(), "", start, kind);
    }

    private void type(String text) {
        if (text.isEmpty() || text.charAt(0) < 32 || text.charAt(0) == 127) {
            return;
        }
        var w = win();
        var buffer = w.doc.buffer;
        if (w.hasSelection()) {
            var start = w.selectionStart();
            edit(start, w.selectionEnd(), text, start + text.length(), UndoStack.Kind.NONE);
            return;
        }
        var pos = w.cursor;
        var end = pos;
        if (overwrite && pos < buffer.lineEnd(buffer.lineOf(pos))) {
            end = nextOffset(pos);
        }
        edit(pos, end, text, pos + text.length(), UndoStack.Kind.TYPING);
    }

    private void newline() {
        var w = win();
        var buffer = w.doc.buffer;
        var start = w.selectionStart();
        var line = buffer.lineOf(start);
        var lineText = buffer.line(line);
        var upTo = Math.min(lineText.length(), start - buffer.lineStart(line));
        var indent = new StringBuilder();
        for (var i = 0; i < upTo && (lineText.charAt(i) == ' ' || lineText.charAt(i) == '\t'); i++) {
            indent.append(lineText.charAt(i));
        }
        var insert = "\n" + indent;
        edit(start, w.selectionEnd(), insert, start + insert.length(), UndoStack.Kind.NONE);
    }

    private void backspace() {
        var w = win();
        if (w.hasSelection()) {
            deleteSelection(UndoStack.Kind.NONE);
        } else if (w.cursor > 0) {
            var start = previousOffset(w.cursor);
            edit(start, w.cursor, "", start, UndoStack.Kind.BACKSPACE);
        }
    }

    private void delete() {
        var w = win();
        if (w.hasSelection()) {
            deleteSelection(UndoStack.Kind.NONE);
        } else if (w.cursor < w.doc.buffer.length()) {
            edit(w.cursor, nextOffset(w.cursor), "", w.cursor, UndoStack.Kind.DELETE);
        }
    }

    private String indentUnit() {
        return options.useSpaces() ? " ".repeat(options.tabWidth()) : "\t";
    }

    private void indent() {
        var w = win();
        var buffer = w.doc.buffer;
        if (!w.hasSelection() || buffer.lineOf(w.selectionStart()) == buffer.lineOf(w.selectionEnd())) {
            if (w.hasSelection()) {
                var start = w.selectionStart();
                var text = tabText(w, start);
                edit(start, w.selectionEnd(), text, start + text.length(), UndoStack.Kind.NONE);
            } else {
                var text = tabText(w, w.cursor);
                edit(w.cursor, w.cursor, text, w.cursor + text.length(), UndoStack.Kind.NONE);
            }
            return;
        }
        var range = selectedLines(w);
        var from = buffer.lineStart(range[0]);
        var to = buffer.lineEnd(range[1]);
        var unit = indentUnit();
        var sb = new StringBuilder();
        for (var line = range[0]; line <= range[1]; line++) {
            if (line > range[0]) {
                sb.append('\n');
            }
            var text = buffer.line(line);
            sb.append(text.isEmpty() ? text : unit + text);
        }
        replaceLines(w, from, to, sb.toString());
    }

    private String tabText(EditorWindow w, int offset) {
        if (!options.useSpaces()) {
            return "\t";
        }
        var buffer = w.doc.buffer;
        var line = buffer.lineOf(offset);
        var col = w.displayCol(buffer.line(line), offset - buffer.lineStart(line));
        return " ".repeat(options.tabWidth() - col % options.tabWidth());
    }

    private void unindent() {
        var w = win();
        var buffer = w.doc.buffer;
        var range = w.hasSelection() ? selectedLines(w) : new int[] {buffer.lineOf(w.cursor), buffer.lineOf(w.cursor)};
        var from = buffer.lineStart(range[0]);
        var to = buffer.lineEnd(range[1]);
        var sb = new StringBuilder();
        var changed = false;
        var cursorLine = buffer.lineOf(w.cursor);
        var cursorShift = 0;
        for (var line = range[0]; line <= range[1]; line++) {
            if (line > range[0]) {
                sb.append('\n');
            }
            var text = buffer.line(line);
            var remove = 0;
            if (text.startsWith("\t")) {
                remove = 1;
            } else {
                while (remove < options.tabWidth() && remove < text.length() && text.charAt(remove) == ' ') {
                    remove++;
                }
            }
            if (remove > 0) {
                changed = true;
            }
            if (line == cursorLine) {
                cursorShift = Math.min(remove, w.cursor - buffer.lineStart(line));
            }
            sb.append(text.substring(remove));
        }
        if (!changed) {
            return;
        }
        if (w.hasSelection()) {
            replaceLines(w, from, to, sb.toString());
        } else {
            var cursorAfter = w.cursor - cursorShift;
            edit(from, to, sb.toString(), cursorAfter, UndoStack.Kind.NONE);
        }
    }

    /** The first and last line of the selection. A selection that ends at the start of a line leaves that line out. */
    private int[] selectedLines(EditorWindow w) {
        var buffer = w.doc.buffer;
        var first = buffer.lineOf(w.selectionStart());
        var last = buffer.lineOf(w.selectionEnd());
        if (last > first && w.selectionEnd() == buffer.lineStart(last)) {
            last--;
        }
        return new int[] {first, last};
    }

    private void replaceLines(EditorWindow w, int from, int to, String text) {
        w.doc.replace(from, to - from, text, w.cursor, from + text.length(), UndoStack.Kind.NONE);
        w.anchor = from;
        w.cursor = from + text.length();
        w.desiredColumn = -1;
        w.reveal = true;
    }

    private void undo() {
        var w = win();
        var cursor = w.doc.undo();
        if (cursor < 0) {
            notice = "Nothing to undo.";
            return;
        }
        w.cursor = Math.clamp(cursor, 0, w.doc.buffer.length());
        w.anchor = -1;
        w.reveal = true;
    }

    private void redo() {
        var w = win();
        var cursor = w.doc.redo();
        if (cursor < 0) {
            notice = "Nothing to redo.";
            return;
        }
        w.cursor = Math.clamp(cursor, 0, w.doc.buffer.length());
        w.anchor = -1;
        w.reveal = true;
    }

    // ---- clipboard ----

    private void copy() {
        var w = win();
        if (!w.hasSelection()) {
            notice = "Nothing is selected.";
            return;
        }
        notice = clipboard.copy(w.doc.buffer.substring(w.selectionStart(), w.selectionEnd()));
    }

    private void cut() {
        var w = win();
        if (!w.hasSelection()) {
            notice = "Nothing is selected.";
            return;
        }
        var status = clipboard.copy(w.doc.buffer.substring(w.selectionStart(), w.selectionEnd()));
        deleteSelection(UndoStack.Kind.NONE);
        notice = status;
    }

    private void paste() {
        var text = clipboard.paste().replace("\r\n", "\n").replace('\r', '\n');
        if (text.isEmpty()) {
            notice = "The clipboard is empty.";
            return;
        }
        var w = win();
        var start = w.selectionStart();
        edit(start, w.selectionEnd(), text, start + text.length(), UndoStack.Kind.NONE);
    }

    // ================================================================
    // Search
    // ================================================================

    private void openDialog(Dialog next) {
        menuIndex = -1;
        dialog = next;
        next.focus = 0;
    }

    private void findDialog() {
        var w = win();
        var initial = w.hasSelection() && w.doc.buffer.lineOf(w.selectionStart()) == w.doc.buffer.lineOf(w.selectionEnd())
                ? selectedText() : lastFind;
        var d = new Dialog("Find", result -> {
            lastFind = result.values().get("find");
            matchCase = result.checks().get("case");
            wholeWord = result.checks().get("word");
            findNext(true);
            return true;
        });
        d.field("find", "Find what:", initial);
        d.check("case", "Match case", matchCase);
        d.check("word", "Whole word", wholeWord);
        d.button(Dialog.Button.okButton("ok", "OK")).button(Dialog.Button.cancelButton());
        openDialog(d);
    }

    private void repeatFind() {
        if (lastFind.isEmpty()) {
            findDialog();
        } else {
            findNext(true);
        }
    }

    /** Finds the next match after the selection and selects it. Wraps at the end of the text. */
    private boolean findNext(boolean showNotice) {
        if (lastFind.isEmpty()) {
            return false;
        }
        var w = win();
        var text = w.doc.buffer.text();
        var from = w.selectionEnd();
        var index = TextSearch.find(text, lastFind, from, matchCase, wholeWord);
        var wrapped = false;
        if (index < 0) {
            index = TextSearch.find(text, lastFind, 0, matchCase, wholeWord);
            wrapped = true;
        }
        if (index < 0) {
            if (showNotice) {
                notice = "Not found: " + lastFind;
            }
            return false;
        }
        w.anchor = index;
        w.cursor = index + lastFind.length();
        w.desiredColumn = -1;
        w.reveal = true;
        w.undoBreak();
        if (showNotice && wrapped) {
            notice = "The search wrapped to the start of the file.";
        }
        return true;
    }

    private void changeDialog() {
        var w = win();
        var initial = w.hasSelection() && w.doc.buffer.lineOf(w.selectionStart()) == w.doc.buffer.lineOf(w.selectionEnd())
                ? selectedText() : lastFind;
        var d = new Dialog("Change", result -> {
            lastFind = result.values().get("find");
            lastReplace = result.values().get("replace");
            matchCase = result.checks().get("case");
            wholeWord = result.checks().get("word");
            switch (result.button()) {
                case "next" -> {
                    findNext(true);
                    return false;
                }
                case "change" -> {
                    changeCurrent();
                    return false;
                }
                case "all" -> {
                    changeAll();
                    return true;
                }
                default -> {
                    return true;
                }
            }
        });
        d.field("find", "Find what:", initial);
        d.field("replace", "Change to:", lastReplace);
        d.check("case", "Match case", matchCase);
        d.check("word", "Whole word", wholeWord);
        d.button(Dialog.Button.okButton("next", "Find Next").staysOpen())
                .button(Dialog.Button.plainButton("change", "Change").staysOpen())
                .button(Dialog.Button.plainButton("all", "Change All"))
                .button(Dialog.Button.cancelButton());
        openDialog(d);
    }

    /** Replaces the selection when it is a match, then finds the next match. */
    private void changeCurrent() {
        var w = win();
        if (lastFind.isEmpty()) {
            return;
        }
        if (w.hasSelection() && w.selectionEnd() - w.selectionStart() == lastFind.length()
                && TextSearch.matchesAt(w.doc.buffer.text(), w.selectionStart(), lastFind, matchCase, wholeWord)) {
            var start = w.selectionStart();
            edit(start, w.selectionEnd(), lastReplace, start + lastReplace.length(), UndoStack.Kind.NONE);
        }
        findNext(true);
    }

    private void changeAll() {
        var w = win();
        if (lastFind.isEmpty()) {
            return;
        }
        var text = w.doc.buffer.text();
        var out = new StringBuilder();
        var count = 0;
        var first = -1;
        var last = -1;
        var i = 0;
        while (true) {
            var index = TextSearch.find(text, lastFind, i, matchCase, wholeWord);
            if (index < 0) {
                break;
            }
            if (first < 0) {
                first = index;
            } else {
                out.append(text, last, index);
            }
            out.append(lastReplace);
            last = index + lastFind.length();
            i = last;
            count++;
        }
        if (count == 0) {
            notice = "Not found: " + lastFind;
            return;
        }
        var replaced = out.toString();
        edit(first, last, replaced, first + replaced.length(), UndoStack.Kind.NONE);
        notice = count + (count == 1 ? " change made." : " changes made.");
    }

    private void goToLineDialog() {
        var w = win();
        var d = new Dialog("Go To Line", result -> {
            var text = result.values().get("line").trim();
            try {
                var number = Integer.parseInt(text);
                var count = w.doc.buffer.lineCount();
                var line = Math.clamp(number, 1, count);
                w.cursor = w.doc.buffer.lineStart(line - 1);
                w.anchor = -1;
                w.desiredColumn = -1;
                w.reveal = true;
                w.undoBreak();
                if (number > count) {
                    notice = "The file has " + count + " lines. Went to the last line.";
                }
            } catch (NumberFormatException e) {
                notice = "Not a line number: " + text;
            }
            return true;
        });
        d.message("Lines: 1 to " + w.doc.buffer.lineCount());
        d.field("line", "Line number:", Integer.toString(line()));
        d.button(Dialog.Button.okButton("ok", "OK")).button(Dialog.Button.cancelButton());
        openDialog(d);
    }

    // ================================================================
    // Help and options dialogs
    // ================================================================

    private void helpDialog() {
        var lines = new ArrayList<String>();
        for (var binding : keys.bindings(KeyContext.EDITOR)) {
            lines.add(String.format("%-14s %s", binding.display(), binding.action().description()));
        }
        var d = new Dialog("Keys", result -> true);
        d.list(lines, 0);
        d.listRows = Math.min(14, Math.max(4, height - 10));
        d.button(Dialog.Button.okButton("ok", "OK"));
        openDialog(d);
        d.focus = 0;
    }

    private void aboutDialog() {
        var d = new Dialog("About the Editor", result -> true);
        d.message("QEditor, the editor of Jeorje.", "It has no command mode.",
                "Menus: " + describeKey("menu") + ". Help: " + describeKey("help") + ".");
        d.button(Dialog.Button.okButton("ok", "OK"));
        openDialog(d);
    }

    private void tabWidthDialog() {
        var d = new Dialog("Tab Width", result -> {
            try {
                var value = Integer.parseInt(result.values().get("width").trim());
                options = options.withTabWidth(value);
            } catch (NumberFormatException e) {
                notice = "Not a number: " + result.values().get("width");
            }
            return true;
        });
        d.field("width", "Tab width (1 to 16):", Integer.toString(options.tabWidth()));
        d.button(Dialog.Button.okButton("ok", "OK")).button(Dialog.Button.cancelButton());
        openDialog(d);
    }

    private void syntaxDialog() {
        if (catalog == null) {
            notice = "No syntax files are available.";
            return;
        }
        var w = win();
        var infos = new ArrayList<SyntaxInfo>(catalog.syntaxes());
        infos.sort(Comparator.comparing(i -> i.name().toLowerCase(Locale.ROOT)));
        var names = new ArrayList<String>();
        names.add("Auto");
        var selected = 0;
        for (var i = 0; i < infos.size(); i++) {
            names.add(infos.get(i).name());
            if (infos.get(i).equals(w.doc.chosenSyntax)) {
                selected = i + 1;
            }
        }
        var d = new Dialog("Syntax", result -> {
            var index = result.selected();
            w.doc.chosenSyntax = index <= 0 ? null : infos.get(index - 1);
            chooseSyntax(w.doc);
            w.doc.openView();
            showNotices(w.doc);
            return true;
        });
        d.list(names, selected);
        d.listRows = Math.min(12, Math.max(3, height - 10));
        d.button(Dialog.Button.okButton("ok", "OK")).button(Dialog.Button.cancelButton());
        openDialog(d);
        d.focus = 0;
        d.selectRow(selected);
    }

    private void messageDialog(String title, String... lines) {
        var d = new Dialog(title, result -> true);
        d.message(lines);
        d.button(Dialog.Button.okButton("ok", "OK"));
        openDialog(d);
    }

    // ================================================================
    // Files
    // ================================================================

    /** Saves a document. {@code after} runs when the save is done. */
    private void save(EditorDocument doc, Runnable after) {
        if (doc.path == null) {
            saveAsDialog(doc, after);
            return;
        }
        diskCheck(doc);
        if (doc.diskChanged) {
            var d = new Dialog("File changed on disk", result -> {
                switch (result.button()) {
                    case "overwrite" -> write(doc, after);
                    case "changes" -> showChanges();
                    default -> { }
                }
                return true;
            });
            d.message(doc.displayName() + " changed on disk after the editor read it.",
                    "Overwrite the file with the text of the editor?");
            d.button(Dialog.Button.plainButton("overwrite", "Overwrite"))
                    .button(Dialog.Button.plainButton("changes", "Show Changes"))
                    .button(Dialog.Button.cancelButton());
            d.focus = 2;
            openDialog(d);
            d.focus = 2;
            return;
        }
        write(doc, after);
    }

    private void write(EditorDocument doc, Runnable after) {
        try {
            var state = FileIo.save(doc.path, doc.buffer.text(), doc.eol);
            doc.diskState = state;
            doc.diskChanged = false;
            doc.undo.markClean();
            sink.fileSaved(doc.path, state);
            notice = "Saved " + doc.path.getFileName() + ".";
            if (after != null) {
                after.run();
            }
        } catch (IOException e) {
            log.atWarn().log(() -> "cannot save " + doc.path + ": " + e);
            messageDialog("Cannot save", "The editor could not save the file:", String.valueOf(e.getMessage()));
        }
    }

    private void saveAsDialog(EditorDocument doc, Runnable after) {
        var d = new Dialog("Save As", result -> {
            var text = result.values().get("path").trim();
            if (text.isEmpty()) {
                notice = "A file name is needed.";
                return true;
            }
            var target = baseDir.resolve(text).toAbsolutePath().normalize();
            if (Files.exists(target) && !target.equals(doc.path)) {
                var confirm = new Dialog("Replace file", r2 -> {
                    if (r2.button().equals("replace")) {
                        assignPath(doc, target);
                        write(doc, after);
                    }
                    return true;
                });
                confirm.message(target.getFileName() + " exists. Replace it?");
                confirm.button(Dialog.Button.plainButton("replace", "Replace")).button(Dialog.Button.cancelButton());
                openDialog(confirm);
                confirm.focus = 1;
                return true;
            }
            if (target.equals(doc.path)) {
                // The same file: the normal save checks the disk and asks before it overwrites a change.
                save(doc, after);
                return true;
            }
            assignPath(doc, target);
            write(doc, after);
            return true;
        });
        d.field("path", "File name:", doc.path == null ? "" : doc.path.toString());
        d.button(Dialog.Button.okButton("ok", "OK")).button(Dialog.Button.cancelButton());
        openDialog(d);
    }

    private void assignPath(EditorDocument doc, Path target) {
        if (!target.equals(doc.path)) {
            doc.path = target;
            doc.diskState = FileIo.stateOf(target);
            doc.diskChanged = false;
            chooseSyntax(doc);
            doc.openView();
        }
    }

    private void revert() {
        var doc = win().doc;
        if (doc.path == null) {
            notice = "The file has no name yet.";
            return;
        }
        if (!doc.isModified()) {
            reloadNow(doc);
            return;
        }
        var d = new Dialog("Revert", result -> {
            if (result.button().equals("revert")) {
                reloadNow(doc);
            }
            return true;
        });
        d.message("Discard the unsaved changes and read " + doc.displayName() + " again?");
        d.button(Dialog.Button.plainButton("revert", "Revert")).button(Dialog.Button.cancelButton());
        openDialog(d);
        d.focus = 1;
    }

    private void reload() {
        var doc = win().doc;
        if (doc.path == null) {
            notice = "The file has no name yet.";
            return;
        }
        if (!doc.isModified()) {
            reloadNow(doc);
            return;
        }
        var d = new Dialog("Reload", result -> {
            switch (result.button()) {
                case "reload" -> reloadNow(doc);
                case "changes" -> showChanges();
                default -> { }
            }
            return true;
        });
        d.message(doc.displayName() + " has unsaved changes.", "Reload the file and lose them?");
        d.button(Dialog.Button.plainButton("reload", "Reload"))
                .button(Dialog.Button.plainButton("changes", "Show Changes"))
                .button(Dialog.Button.cancelButton());
        openDialog(d);
        d.focus = 2;
    }

    private void reloadNow(EditorDocument doc) {
        try {
            var loaded = FileIo.load(doc.path);
            if (loaded.state() == null) {
                notice = "The file no longer exists.";
                return;
            }
            doc.eol = loaded.eol();
            doc.diskState = loaded.state();
            doc.diskChanged = false;
            doc.bytes = loaded.bytes();
            doc.reset(loaded.text());
            doc.undo.markClean();
            notice = "Reloaded " + doc.displayName() + ".";
        } catch (IOException e) {
            messageDialog("Cannot reload", String.valueOf(e.getMessage()));
        }
    }

    private void closeEditor() {
        var modified = new ArrayList<EditorDocument>();
        for (var w : windows) {
            if (w.doc.isModified() && !modified.contains(w.doc)) {
                modified.add(w.doc);
            }
        }
        closeFlow(modified);
    }

    private void closeFlow(List<EditorDocument> remaining) {
        if (remaining.isEmpty()) {
            closed = true;
            return;
        }
        var doc = remaining.getFirst();
        var rest = remaining.subList(1, remaining.size());
        var d = new Dialog("Unsaved changes", result -> {
            switch (result.button()) {
                case "save" -> save(doc, () -> closeFlow(rest));
                case "discard" -> closeFlow(rest);
                default -> { }
            }
            return true;
        });
        d.message("Save the changes to " + doc.displayName() + "?");
        d.button(Dialog.Button.okButton("save", "Save"))
                .button(Dialog.Button.plainButton("discard", "Discard"))
                .button(Dialog.Button.cancelButton());
        openDialog(d);
        d.focusDefault();
    }

    private void showChanges() {
        var doc = win().doc;
        var disk = "";
        if (doc.path != null && Files.isRegularFile(doc.path)) {
            try {
                disk = FileIo.load(doc.path).text();
            } catch (IOException e) {
                messageDialog("Cannot compare", String.valueOf(e.getMessage()));
                return;
            }
        }
        menuIndex = -1;
        diff = new DiffView(disk, doc.buffer.text(), "File on disk", "Editor", options.wheelLines());
    }

    private void diffKey(String op) {
        if (diff == null) {
            return;
        }
        layoutWindows();
        diff.layout(new Rect(0, 1, width, Math.max(3, height - 2)));
        var page = Math.max(1, diff.scroll.viewport() - 1);
        switch (op) {
            case "escape", "close" -> diff = null;
            case "up" -> diff.scroll.scrollBy(-1);
            case "down" -> diff.scroll.scrollBy(1);
            case "pageUp" -> diff.scroll.scrollBy(-page);
            case "pageDown" -> diff.scroll.scrollBy(page);
            case "fileStart", "lineStart" -> diff.scroll.scrollTo(0);
            case "fileEnd", "lineEnd" -> diff.scroll.scrollTo(diff.scroll.maxOffset());
            default -> { }
        }
    }

    // ================================================================
    // Windows
    // ================================================================

    private void toggleSplit() {
        if (windows.size() == 1) {
            var first = windows.get(0);
            var second = new EditorWindow(first.doc, options.wheelLines(), () -> this.options);
            second.cursor = first.cursor;
            windows.add(second);
        } else {
            var keep = windows.get(active);
            var drop = windows.get(1 - active);
            drop.dispose();
            windows.clear();
            windows.add(keep);
            active = 0;
        }
        menuIndex = -1;
    }

    private void switchWindow() {
        if (windows.size() < 2) {
            notice = "There is only one window.";
            return;
        }
        active = 1 - active;
    }

    private void layoutWindows() {
        var available = Math.max(0, height - 2);
        if (windows.size() == 1) {
            windows.get(0).frame = new Rect(0, 1, width, available);
        } else {
            var top = (available + 1) / 2;
            windows.get(0).frame = new Rect(0, 1, width, top);
            windows.get(1).frame = new Rect(0, 1 + top, width, available - top);
        }
        for (var w : windows) {
            w.sync();
        }
    }

    // ================================================================
    // Dialog keys
    // ================================================================

    private void dialogKey(String op, String text) {
        var d = dialog;
        switch (op) {
            case "text" -> {
                if (text.equals(" ") && d.focusedButton() >= 0) {
                    pressFocused(d);
                } else if (text.equals(" ") && d.toggle()) {
                    return;
                } else if (d.listFocused()) {
                    d.typeAhead(text.charAt(0));
                } else {
                    d.type(text);
                }
            }
            case "enter" -> pressFocused(d);
            case "escape" -> cancelDialog(d);
            case "indent", "down" -> d.down();
            case "unindent", "up" -> d.up();
            case "left" -> d.left();
            case "right" -> d.right();
            case "lineStart" -> d.home();
            case "lineEnd" -> d.end();
            case "backspace" -> d.backspace();
            case "delete" -> d.delete();
            case "pageUp" -> d.selectRow(d.selected - d.listRows);
            case "pageDown" -> d.selectRow(d.selected + d.listRows);
            case "paste", "alias:paste" -> d.type(clipboard.paste());
            default -> { }
        }
    }

    private void cancelDialog(Dialog d) {
        if (dialog == d) {
            dialog = null;
        }
    }

    private void pressFocused(Dialog d) {
        var index = d.focusedButton();
        var button = index >= 0 ? d.buttons.get(index) : d.defaultButton();
        if (button != null) {
            press(d, button);
        }
    }

    private void press(Dialog d, Dialog.Button button) {
        if (button.cancel()) {
            cancelDialog(d);
            return;
        }
        var result = d.result(button);
        var close = d.handler.handle(result);
        if (close && dialog == d) {
            dialog = null;
        }
    }

    // ================================================================
    // Menus
    // ================================================================

    private static Menus.Item item(String label, char hot, String action, String help) {
        return new Menus.Item(label, label.toLowerCase(Locale.ROOT).indexOf(hot), action, help);
    }

    private static Menus.Item check(String label, char hot, String action, boolean on, String help) {
        return new Menus.Item(label, label.toLowerCase(Locale.ROOT).indexOf(hot), action, on, true, help);
    }

    private List<Menus.Menu> menus() {
        var doc = win().doc;
        var file = new ArrayList<Menus.Item>();
        file.add(item("Save", 's', "save", "Save the file"));
        file.add(item("Save As...", 'a', "saveAs", "Save the text under another name"));
        file.add(item("Revert", 'r', "revert", "Discard the changes and read the file again"));
        if (doc.diskChanged) {
            file.add(item("Reload", 'l', "reload", "The file changed on disk. Read it again"));
        }
        file.add(item("Close and return to chat", 'c', "close", "Close the editor"));
        var edit = List.of(
                item("Undo", 'u', "undo", "Undo the last change"),
                item("Redo", 'r', "redo", "Redo the last undone change"),
                item("Cut", 't', "cut", "Cut the selection to the clipboard"),
                item("Copy", 'c', "copy", "Copy the selection to the clipboard"),
                item("Paste", 'p', "paste", "Insert the clipboard"),
                item("Clear", 'l', "clear", "Delete the selection"),
                item("Select All", 'a', "selectAll", "Select all text"));
        var search = List.of(
                item("Find...", 'f', "find", "Find text"),
                item("Repeat Last Find", 'r', "repeatFind", "Find the next match"),
                item("Change...", 'c', "change", "Find and replace text"),
                item("Go To Line...", 'g', "goToLine", "Move the cursor to a line"));
        var view = List.of(
                check("Line Numbers", 'l', "toggleLineNumbers", options.lineNumbers(), "Show line numbers"),
                check("Wrap Long Lines", 'w', "toggleWrap", options.wrap(), "Wrap lines at the window edge"),
                check("Split Window", 's', "split", windows.size() > 1, "Show two windows"),
                check("Show Whitespace", 'h', "toggleWhitespace", options.showWhitespace(), "Show spaces and tabs"),
                item("Show Changes", 'c', "showChanges", "Compare the text with the file on disk"));
        var optionsMenu = List.of(
                item("Tab Width...", 't', "tabWidth", "Set the width of a tab stop"),
                check("Use Spaces For Tabs", 'u', "toggleSpaces", options.useSpaces(), "Insert spaces for the Tab key"),
                check("Syntax Colors", 'c', "toggleSyntax", options.syntaxColors(), "Turn syntax colors on or off"),
                item("Syntax...", 's', "chooseSyntax", "Choose the syntax of this file"),
                check("Colors", 'o', "colors", !options.noColor(), "Switch between colors and monochrome"));
        var help = List.of(
                item("Keys", 'k', "help", "Show the keys of the editor"),
                item("About the Editor", 'a', "about", "About this editor"));
        return List.of(
                new Menus.Menu("File", 0, "menuFile", file),
                new Menus.Menu("Edit", 0, "menuEdit", edit),
                new Menus.Menu("Search", 0, "menuSearch", search),
                new Menus.Menu("View", 0, "menuView", view),
                new Menus.Menu("Options", 0, "menuOptions", optionsMenu),
                new Menus.Menu("Help", 0, "menuHelp", help));
    }

    /** The x position of the title of a menu in the menu bar. */
    private int menuX(int index) {
        var x = 1;
        var all = menus();
        for (var i = 0; i < index; i++) {
            x += all.get(i).title().length() + 3;
        }
        return x;
    }

    /** The keys of a command, as the menus show them. Empty when the command has no key. */
    private String keyText(String action) {
        var id = action.equals("clear") ? "delete" : action;
        var shown = keys.binding(KeyScope.EDITOR, id).map(b -> b.keys().isEmpty() ? "" : b.display()).orElse("");
        var w = win();
        return switch (action) {
            case "cut" -> shown + (w.hasSelection() ? aliasSuffix(shown, "Ctrl-X") : "");
            case "copy" -> shown + (w.hasSelection() ? aliasSuffix(shown, "Ctrl-C") : "");
            case "paste" -> shown + (!clipboard.paste().isEmpty() ? aliasSuffix(shown, "Ctrl-V") : "");
            case "close" -> shown;
            default -> shown;
        };
    }

    private static String aliasSuffix(String shown, String alias) {
        return shown.isEmpty() ? alias : ", " + alias;
    }

    private String describeKey(String action) {
        var text = keyText(action);
        return text.isEmpty() ? "none" : text;
    }

    private void openMenu(int index) {
        if (menuIndex == index) {
            menuIndex = -1;
            return;
        }
        menuIndex = index;
        itemIndex = firstEnabled(menus().get(index), 0, 1);
    }

    private static int firstEnabled(Menus.Menu menu, int from, int step) {
        var n = menu.items().size();
        for (var k = 0; k < n; k++) {
            var i = Math.floorMod(from + k * step, n);
            if (menu.items().get(i).enabled()) {
                return i;
            }
        }
        return 0;
    }

    private void menuKey(String op, String text) {
        var all = menus();
        var menu = all.get(menuIndex);
        switch (op) {
            case "escape", "menu" -> menuIndex = -1;
            case "left" -> {
                menuIndex = Math.floorMod(menuIndex - 1, all.size());
                itemIndex = firstEnabled(all.get(menuIndex), 0, 1);
            }
            case "right" -> {
                menuIndex = Math.floorMod(menuIndex + 1, all.size());
                itemIndex = firstEnabled(all.get(menuIndex), 0, 1);
            }
            case "up" -> itemIndex = firstEnabled(menu, itemIndex - 1, -1);
            case "down" -> itemIndex = firstEnabled(menu, itemIndex + 1, 1);
            case "lineStart", "pageUp" -> itemIndex = firstEnabled(menu, 0, 1);
            case "lineEnd", "pageDown" -> itemIndex = firstEnabled(menu, menu.items().size() - 1, -1);
            case "enter" -> runItem(menu.items().get(itemIndex));
            case "text" -> {
                var c = Character.toLowerCase(text.charAt(0));
                menu.items().stream().filter(i -> i.hotChar() == c && i.enabled()).findFirst()
                        .ifPresent(this::runItem);
            }
            default -> {
                for (var i = 0; i < MENU_ACTIONS.length; i++) {
                    if (MENU_ACTIONS[i].equals(op)) {
                        openMenu(i);
                        return;
                    }
                }
            }
        }
    }

    private void runItem(Menus.Item item) {
        menuIndex = -1;
        if (item.enabled()) {
            command(item.action());
        }
    }

    // ================================================================
    // Mouse
    // ================================================================

    /** A region built from a rectangle and a handler. */
    private record Rgn(Rect rect, Consumer<RegionEvent> handler) implements Region {
        @Override
        public Rect bounds() {
            return rect;
        }

        @Override
        public void onMouse(RegionEvent event) {
            handler.accept(event);
        }
    }

    private void buildRegions() {
        registry = new RegionRegistry();
        var full = new Rect(0, 0, width, height);
        if (dialog != null) {
            dialog.layout(width, height);
            registry.register(new Rgn(full, e -> { }));
            var d = dialog;
            registry.register(new Rgn(d.rect, e -> dialogMouse(d, e)));
            return;
        }
        if (diff != null) {
            var area = new Rect(0, 1, width, Math.max(3, height - 2));
            diff.layout(area);
            var view = diff;
            registry.register(new Rgn(area, e -> {
                if (e.isWheel()) {
                    view.scroll.wheel(e.button(), e.shift());
                }
            }));
            registry.register(view.bar);
            return;
        }
        for (var w : windows) {
            var area = w.textArea();
            registry.register(new Rgn(area, e -> textMouse(w, e)));
            registry.register(w.verticalBar);
            if (w.hasHorizontalBar()) {
                var bar = new Rect(w.frame.x() + 1, w.frame.y() + w.frame.height() - 1, w.frame.width() - 2, 1);
                registry.register(new Rgn(bar, e -> {
                    if (e.type() == MouseEvent.Type.Pressed && e.button() == MouseEvent.Button.Button1) {
                        w.clickHorizontalBar(e.x());
                    } else if (e.isWheel()) {
                        w.scroll.wheel(e.button(), true);
                    }
                }));
            }
        }
        if (menuIndex >= 0) {
            registry.register(new Rgn(full, e -> {
                if (e.type() == MouseEvent.Type.Pressed) {
                    menuIndex = -1;
                }
            }));
        }
        registry.register(new Rgn(new Rect(0, 0, width, 1), this::menuBarMouse));
        if (menuIndex >= 0) {
            var rect = dropdownRect();
            registry.register(new Rgn(rect, e -> dropdownMouse(e)));
        }
    }

    private void menuBarMouse(RegionEvent e) {
        if (e.type() != MouseEvent.Type.Pressed || e.button() != MouseEvent.Button.Button1) {
            return;
        }
        var all = menus();
        for (var i = 0; i < all.size(); i++) {
            var x = menuX(i);
            if (e.x() >= x && e.x() < x + all.get(i).title().length() + 2) {
                if (menuIndex == i) {
                    menuIndex = -1;
                } else {
                    menuIndex = i;
                    itemIndex = firstEnabled(all.get(i), 0, 1);
                }
                return;
            }
        }
        menuIndex = -1;
    }

    private Rect dropdownRect() {
        var menu = menus().get(menuIndex);
        var labelWidth = menu.items().stream().mapToInt(i -> i.label().length()).max().orElse(0);
        var keyWidth = menu.items().stream().mapToInt(i -> keyText(i.action()).length()).max().orElse(0);
        var w = 2 + 2 + labelWidth + 2 + keyWidth + 1;
        var x = Math.max(0, Math.min(menuX(menuIndex), width - w));
        return new Rect(x, 1, Math.min(w, width), menu.items().size() + 2);
    }

    private void dropdownMouse(RegionEvent e) {
        if (menuIndex < 0) {
            return;
        }
        var menu = menus().get(menuIndex);
        var index = e.y() - 1;
        if (index < 0 || index >= menu.items().size()) {
            return;
        }
        switch (e.type()) {
            case Pressed -> {
                if (e.button() == MouseEvent.Button.Button1) {
                    runItem(menu.items().get(index));
                }
            }
            case Dragged, Moved -> {
                if (menu.items().get(index).enabled()) {
                    itemIndex = index;
                }
            }
            default -> { }
        }
    }

    private void dialogMouse(Dialog d, RegionEvent e) {
        var absoluteX = d.rect.x() + e.x();
        var absoluteY = d.rect.y() + e.y();
        if (e.isWheel()) {
            var step = e.button() == MouseEvent.Button.WheelUp ? -options.wheelLines() : options.wheelLines();
            d.scrollList(step);
            return;
        }
        if (e.type() != MouseEvent.Type.Pressed || e.button() != MouseEvent.Button.Button1) {
            return;
        }
        var hit = d.hit(absoluteX, absoluteY);
        switch (hit.part()) {
            case FIELD -> d.clickField(hit.index(), absoluteX);
            case CHECK -> d.clickCheck(hit.index());
            case LIST -> {
                d.clickList(hit.index());
                if (e.clicks() >= 2) {
                    var button = d.defaultButton();
                    if (button != null) {
                        press(d, button);
                    }
                }
            }
            case BUTTON -> press(d, d.buttons.get(hit.index()));
            default -> { }
        }
    }

    private void textMouse(EditorWindow w, RegionEvent e) {
        switch (e.type()) {
            case Wheel -> w.scroll.wheel(e.button(), e.shift());
            case Pressed -> {
                if (e.button() == MouseEvent.Button.Button1) {
                    pressText(w, e);
                }
            }
            case Dragged -> {
                if (dragging && dragWindow == w) {
                    dragSelect(w, e);
                }
            }
            case Released -> {
                var wasDragging = dragging && dragWindow == w;
                dragging = false;
                lastDrag = null;
                if (copyOnSelect && wasDragging && w.hasSelection()) {
                    copy();
                }
            }
            default -> { }
        }
    }

    private void pressText(EditorWindow w, RegionEvent e) {
        active = windows.indexOf(w);
        var buffer = w.doc.buffer;
        var offset = w.offsetAt(e.y(), e.x());
        w.desiredColumn = -1;
        w.undoBreak();
        switch (Math.min(3, Math.max(1, e.clicks()))) {
            case 1 -> {
                if (e.shift()) {
                    if (w.anchor < 0) {
                        w.anchor = w.cursor;
                    }
                    w.cursor = offset;
                    dragAnchor = w.anchor;
                } else {
                    w.cursor = offset;
                    w.anchor = -1;
                    dragAnchor = offset;
                }
            }
            case 2 -> {
                w.anchor = wordStart(buffer, offset);
                w.cursor = wordEnd(buffer, offset);
                dragAnchor = w.anchor;
            }
            default -> {
                var line = buffer.lineOf(offset);
                w.anchor = buffer.lineStart(line);
                w.cursor = Math.min(buffer.length(), buffer.lineEnd(line) + 1);
                dragAnchor = w.anchor;
            }
        }
        dragging = true;
        dragWindow = w;
        lastDrag = null;
    }

    private void dragSelect(EditorWindow w, RegionEvent e) {
        var h = w.textHeight();
        var row = e.y();
        if (row < 0) {
            w.scroll.scrollBy(row);
            row = 0;
            lastDrag = e;
        } else if (row >= h) {
            w.scroll.scrollBy(row - h + 1);
            row = h - 1;
            lastDrag = e;
        } else {
            lastDrag = null;
        }
        w.cursor = w.offsetAt(row, e.x());
        w.anchor = dragAnchor;
        w.desiredColumn = -1;
    }

    private static int charClass(char c) {
        if (TextSearch.isWordChar(c)) {
            return 1;
        }
        return Character.isWhitespace(c) ? 2 : 3;
    }

    private static int wordStart(TextBuffer buffer, int offset) {
        var line = buffer.lineOf(offset);
        var start = buffer.lineStart(line);
        var end = buffer.lineEnd(line);
        if (start == end) {
            return offset;
        }
        var at = Math.min(offset, end - 1);
        var cls = charClass(buffer.charAt(at));
        var i = at;
        while (i > start && charClass(buffer.charAt(i - 1)) == cls) {
            i--;
        }
        return i;
    }

    private static int wordEnd(TextBuffer buffer, int offset) {
        var line = buffer.lineOf(offset);
        var start = buffer.lineStart(line);
        var end = buffer.lineEnd(line);
        if (start == end) {
            return offset;
        }
        var at = Math.min(offset, end - 1);
        var cls = charClass(buffer.charAt(at));
        var i = at;
        while (i < end && charClass(buffer.charAt(i)) == cls) {
            i++;
        }
        return i;
    }

    // ================================================================
    // Drawing
    // ================================================================

    private int cursorX = -1;
    private int cursorY = -1;

    /** Draws the screen. The result has one styled line for each row of the screen. */
    public List<AttributedString> render() {
        var canvas = new Canvas(width, height, palette.text());
        cursorX = -1;
        cursorY = -1;
        if (width < 12 || height < 5) {
            canvas.put(0, 0, "Terminal too small", palette.text());
            return canvas.toLines();
        }
        layoutWindows();
        for (var i = 0; i < windows.size(); i++) {
            var w = windows.get(i);
            var title = w.doc.displayName() + (w.doc.isModified() ? " *" : "");
            if (w.doc.path != null) {
                title = displayPath(w.doc.path) + (w.doc.isModified() ? " *" : "");
            }
            w.draw(canvas, palette, i == active, title);
        }
        if (diff != null) {
            diff.layout(new Rect(0, 1, width, Math.max(3, height - 2)));
            diff.draw(canvas, palette);
        } else if (dialog == null || menuIndex < 0) {
            var cell = win().cursorCell();
            if (cell != null && dialog == null && menuIndex < 0) {
                cursorX = cell[0];
                cursorY = cell[1];
            }
        }
        drawMenuBar(canvas);
        drawStatus(canvas);
        if (menuIndex >= 0 && dialog == null) {
            drawDropdown(canvas);
        }
        if (dialog != null) {
            dialog.layout(width, height);
            dialog.draw(canvas, palette);
            cursorX = dialog.cursorX;
            cursorY = dialog.cursorY;
        }
        return canvas.toLines();
    }

    private String displayPath(Path path) {
        if (path.startsWith(baseDir) && !path.equals(baseDir)) {
            return baseDir.relativize(path).toString().replace('\\', '/');
        }
        return path.toString();
    }

    private void drawMenuBar(Canvas canvas) {
        canvas.fill(0, 0, width, 1, ' ', palette.menuBar());
        var all = menus();
        for (var i = 0; i < all.size(); i++) {
            var menu = all.get(i);
            var x = menuX(i);
            var selected = menuIndex == i && dialog == null;
            var style = selected ? palette.menuSelected() : palette.menuBar();
            canvas.put(x, 0, " " + menu.title() + " ", style);
            if (!selected) {
                canvas.set(x + 1 + menu.hot(), 0, menu.title().charAt(menu.hot()), palette.menuHot());
            }
        }
    }

    private void drawDropdown(Canvas canvas) {
        var menu = menus().get(menuIndex);
        var rect = dropdownRect();
        canvas.fill(rect.x(), rect.y(), rect.width(), rect.height(), ' ', palette.menuItem());
        canvas.box(rect.x(), rect.y(), rect.width(), rect.height(), palette.menuItem());
        for (var i = 0; i < menu.items().size(); i++) {
            var it = menu.items().get(i);
            var y = rect.y() + 1 + i;
            var base = !it.enabled() ? palette.menuDisabled() : palette.menuItem();
            var style = i == itemIndex ? palette.menuSelected() : base;
            canvas.fill(rect.x() + 1, y, rect.width() - 2, 1, ' ', style);
            if (Boolean.TRUE.equals(it.checked())) {
                canvas.set(rect.x() + 1, y, '✓', style);
            }
            canvas.put(rect.x() + 3, y, it.label(), style);
            if (i != itemIndex && it.hot() >= 0) {
                canvas.set(rect.x() + 3 + it.hot(), y, it.label().charAt(it.hot()), palette.menuHot());
            }
            var keyText = keyText(it.action());
            canvas.put(rect.x() + rect.width() - 2 - keyText.length(), y, keyText, style);
        }
    }

    private String firstKey(String action) {
        return keys.binding(KeyScope.EDITOR, action).filter(b -> !b.keys().isEmpty())
                .map(b -> b.keys().getFirst().toString()).orElse(null);
    }

    private String keyHint() {
        var parts = new ArrayList<String>();
        var save = firstKey("save");
        if (save != null) {
            parts.add(save + "=Save");
        }
        var help = firstKey("help");
        if (help != null) {
            parts.add(help + "=Help");
        }
        var repeat = firstKey("repeatFind");
        if (repeat != null) {
            parts.add(repeat + "=Repeat Find");
        }
        var menu = firstKey("menu");
        if (menu != null) {
            parts.add(menu + "=Menu");
        }
        return " " + String.join("  ", parts);
    }

    private String statusLeft() {
        if (menuIndex >= 0 && dialog == null) {
            var menu = menus().get(menuIndex);
            return " " + menu.items().get(Math.min(itemIndex, menu.items().size() - 1)).help();
        }
        if (notice != null) {
            return " " + notice;
        }
        if (win().doc.diskChanged) {
            return " Changed on disk: Reload (" + describeKey("menuFile") + "), Show Changes ("
                    + describeKey("menuView") + ")";
        }
        return keyHint();
    }

    private void drawStatus(Canvas canvas) {
        var y = height - 1;
        var warn = notice != null || win().doc.diskChanged;
        var style = warn ? palette.statusNotice() : palette.status();
        canvas.fill(0, y, width, 1, ' ', style);
        var right = (overwrite ? "OVR  " : "") + syntaxName() + "  Ln " + line() + " Col " + column() + " ";
        var left = statusLeft();
        var room = Math.max(0, width - right.length() - 1);
        if (left.length() > room) {
            left = left.substring(0, Math.max(0, room - 1)) + "…";
        }
        canvas.put(0, y, left, style);
        canvas.put(Math.max(0, width - right.length()), y, right, style);
    }

    // ================================================================
    // Terminal loop
    // ================================================================

    private volatile boolean resized;

    private static final int ESC_WAIT_MS = 40;
    private static final int CHORD_WAIT_MS = 1500;

    private static void append(StringBuilder sb, int codePoint) {
        sb.appendCodePoint(codePoint);
    }

    /**
     * Runs the editor on a terminal until the user closes it. The method puts the terminal in raw mode, uses the
     * alternate screen, tracks the mouse when the terminal supports it, and restores everything at the end.
     */
    public void run(Terminal terminal) {
        run(terminal, false);
    }

    /**
     * Opens a file in a new editor on the terminal and returns when the user closes the editor.
     *
     * <p>The method leaves the alternate screen as it found it (see {@link EditorHooks#alternateScreenActive()}),
     * restores the terminal attributes, and sets mouse tracking back to the mode that was on before. The caller
     * draws its own screen again after the method returns.
     *
     * @param terminal the terminal
     * @param file     the file to edit; a path that does not exist opens an empty new file
     * @param hooks    the connections to the chat screen
     * @return the editor, closed, so the caller can read the final state; the caller can also ignore it
     * @throws IOException when the file cannot be read
     */
    public static QEditor edit(Terminal terminal, Path file, EditorHooks hooks) throws IOException {
        var config = hooks.config();
        var wheel = config.get("ui.mouse.wheelLines") instanceof Number n ? Math.max(1, n.intValue()) : 3;
        var options = EditorOptions.defaults().withWheelLines(wheel);
        if (config.get("editor.syntax.enabled") instanceof Boolean b && !b) {
            options = options.withSyntaxColors(false);
        }
        var injections = InjectionOptions.fromConfig(config);
        if (config.get("editor.syntax.replayLines") instanceof Number n) {
            injections = injections.withReplayLines(Math.max(1, n.intValue()));
        }
        var editor = builder()
                .keys(hooks.keys())
                .clipboard(hooks.clipboard())
                .catalog(hooks.catalog())
                .highlight(hooks.highlight())
                .fileStates(hooks.fileStates())
                .options(options)
                .injections(injections)
                .noticeSink(hooks.agentChangeNotice())
                .build();
        editor.open(file);
        var previousTracking = terminal.getCurrentMouseTracking();
        try {
            editor.run(terminal, hooks.alternateScreenActive());
        } finally {
            if (previousTracking != null && previousTracking != Terminal.MouseTracking.Off) {
                terminal.trackMouse(previousTracking);
            } else if (terminal.getCurrentMouseTracking() != Terminal.MouseTracking.Off) {
                terminal.trackMouse(Terminal.MouseTracking.Off);
            }
            terminal.flush();
        }
        return editor;
    }

    private void run(Terminal terminal, boolean keepAlternateScreen) {
        var attributes = terminal.enterRawMode();
        if (!keepAlternateScreen) {
            terminal.puts(InfoCmp.Capability.enter_ca_mode);
        }
        terminal.puts(InfoCmp.Capability.keypad_xmit);
        // The editor display draws only the cells that differ from an empty screen. Clear what the caller left.
        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.flush();
        var mouseInput = new MouseInput(terminal, new MouseConfig(true, options.wheelLines(), false),
                new RegionRegistry(), text -> notice = text);
        var tracking = mouseInput.start();
        var display = new Display(terminal, true);
        var previousResize = terminal.handle(Terminal.Signal.WINCH, signal -> resized = true);
        var reader = terminal.reader();
        mouseTerminal = terminal;
        inputReader = reader;
        if (tracking) {
            for (var seq : MouseSupport.keys(terminal)) {
                keyMap.bind("mouse", seq);
            }
        }
        var lastOverwrite = overwrite;
        try {
            var size = terminal.getSize();
            setSize(size.getColumns(), size.getRows());
            display.resize(size.getRows(), size.getColumns());
            display.clear();
            var eof = false;
            while (!closed && !eof) {
                if (resized) {
                    resized = false;
                    size = terminal.getSize();
                    setSize(size.getColumns(), size.getRows());
                    display.resize(size.getRows(), size.getColumns());
                    display.clear();
                }
                if (redraw && pending.isEmpty() && reader.peek(1) == NonBlockingReader.READ_EXPIRED) {
                    draw(terminal, display);
                    if (lastOverwrite != overwrite) {
                        lastOverwrite = overwrite;
                        terminal.writer().print(overwrite ? "\u001b[4 q" : "\u001b[2 q");
                        terminal.writer().flush();
                    }
                    redraw = false;
                }
                var timeout = pending.isEmpty() ? 250 : (isAmbiguous(pending) ? ESC_WAIT_MS : CHORD_WAIT_MS);
                var c = reader.read(timeout);
                if (c == NonBlockingReader.READ_EXPIRED) {
                    if (pending.isEmpty()) {
                        tick();
                    } else {
                        decodeAll(true);
                    }
                    continue;
                }
                if (c < 0) {
                    break;
                }
                var burst = new StringBuilder(pending);
                append(burst, c);
                // A sequence or a paste arrives as a burst. Read what is ready before the decoder runs.
                int more;
                while ((more = reader.read(1)) >= 0 && burst.length() < 8192) {
                    append(burst, more);
                }
                eof = more == NonBlockingReader.EOF;
                pending = burst.toString();
                decodeAll(false);
            }
        } catch (IOException e) {
            log.atWarn().log(() -> "terminal input failed: " + e);
        } finally {
            mouseInput.close();
            mouseTerminal = null;
            inputReader = null;
            terminal.handle(Terminal.Signal.WINCH, previousResize);
            terminal.writer().print("\u001b[0 q");
            terminal.puts(InfoCmp.Capability.keypad_local);
            if (!keepAlternateScreen) {
                terminal.puts(InfoCmp.Capability.exit_ca_mode);
            }
            terminal.flush();
            terminal.setAttributes(attributes);
        }
    }

    /** True when the pending input is a key that is also the start of a longer key (such as Esc). */
    private boolean isAmbiguous(String input) {
        var remaining = new int[1];
        var op = keyMap.getBound(input, remaining);
        return op != null && remaining[0] < 0;
    }

    private void draw(Terminal terminal, Display display) {
        var lines = render();
        var size = terminal.getSize();
        var cursor = cursorX >= 0 ? size.cursorPos(cursorY, cursorX) : size.cursorPos(height - 1, 0);
        display.update(lines, cursor);
    }
}
