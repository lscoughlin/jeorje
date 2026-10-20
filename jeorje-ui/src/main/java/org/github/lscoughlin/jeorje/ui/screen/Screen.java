package org.github.lscoughlin.jeorje.ui.screen;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;
import org.github.lscoughlin.jeorje.ui.keys.KeyContext;
import org.github.lscoughlin.jeorje.ui.keys.KeySpec;
import org.github.lscoughlin.jeorje.ui.mouse.ClickCounter;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.jline.terminal.MouseEvent;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The interactive screen: chat view, input line, and status line.
 *
 * <p>The chat view region (and the permission box) is drawn with JLine {@link Display}, which redraws only the
 * changed lines. The status line is the last row. The input line is the row above it and belongs to the JLine
 * line reader. The screen reads {@link AgentEvent} objects from a queue and never reads agent state.
 */
public final class Screen implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Screen.class);

    /** Receives the answer of the user to a permission prompt. */
    @FunctionalInterface
    public interface DecisionHandler {
        void decided(AgentId agent, ToolUse toolUse, Decision decision);
    }

    /**
     * @param keyboardProtocol the value of {@code ui.keyboardProtocol}: auto, on, or off
     * @param historyFile the input history file, or null
     * @param onSubmit receives each line that the user submits
     * @param onDecision receives each answer to a permission prompt
     * @param onInterrupt runs when the user presses {@code Ctrl-C}
     * @param keyBindings the effective key bindings, or null for the defaults
     * @param actionHandler receives the key actions of the chat screen, or null for none
     */
    public record Options(String keyboardProtocol, Path historyFile, Consumer<String> onSubmit,
                          DecisionHandler onDecision, Runnable onInterrupt, KeyBindings keyBindings,
                          ActionHandler actionHandler) {
        public Options(String keyboardProtocol, Path historyFile, Consumer<String> onSubmit,
                       DecisionHandler onDecision, Runnable onInterrupt) {
            this(keyboardProtocol, historyFile, onSubmit, onDecision, onInterrupt, null, null);
        }

        public Options {
            keyboardProtocol = keyboardProtocol == null ? "auto" : keyboardProtocol;
            onSubmit = onSubmit == null ? line -> {} : onSubmit;
            onDecision = onDecision == null ? (a, u, d) -> {} : onDecision;
            onInterrupt = onInterrupt == null ? () -> {} : onInterrupt;
            keyBindings = keyBindings == null ? KeyBindings.defaults() : keyBindings;
            actionHandler = actionHandler == null ? ActionHandler.NONE : actionHandler;
        }

        /** Returns the options with other key bindings. */
        public Options keyBindings(KeyBindings bindings) {
            return new Options(keyboardProtocol, historyFile, onSubmit, onDecision, onInterrupt, bindings,
                    actionHandler);
        }

        /** Returns the options with another action handler. */
        public Options actionHandler(ActionHandler handler) {
            return new Options(keyboardProtocol, historyFile, onSubmit, onDecision, onInterrupt, keyBindings,
                    handler);
        }

        public static Options defaults() { return new Options("auto", null, null, null, null); }
    }

    private static final String PROMPT = "> ";

    private final Terminal terminal;
    private final Options options;
    private final org.github.lscoughlin.jeorje.ui.chat.HighlightContext highlight;
    private final Display display;
    private final StatusLine status = new StatusLine();
    private final Map<AgentId, ChatView> chats = new ConcurrentHashMap<>();
    private final BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
    private final Deque<PermissionPrompt> prompts = new ArrayDeque<>();
    private final Deque<PendingForm> forms = new ArrayDeque<>();
    private volatile int formTop = -1;
    private volatile int formCursorRow = -1;
    private volatile int formCursorColumn;

    /** A form that waits for the user, with the receiver of its answer. */
    private record PendingForm(FormPrompt prompt, Consumer<FormAnswer> onAnswer) {}

    private static final Map<String, String> FORM_KEYS = Map.of("formUp", "up", "formDown", "down",
            "formNext", "tab", "formPrevious", "shift-tab", "formToggle", "space", "formChat", "c",
            "formDecline", "esc");
    private final Object drawLock = new Object();
    private final AtomicInteger redraws = new AtomicInteger();
    private final AtomicInteger clears = new AtomicInteger();
    private final AtomicBoolean entered = new AtomicBoolean();
    private final AtomicBoolean restored = new AtomicBoolean();
    private volatile boolean running;
    private volatile boolean reading;
    private volatile AgentId foreground = new AgentId("main");
    private volatile int drawnRows;
    private volatile int drawnColumns;
    private volatile int drawnRegionRows;
    private volatile int inputRows = 1;
    private volatile ActionHandler handler;
    private volatile Pane<?> activePane;
    private volatile boolean paneAbort;
    private volatile boolean scrollBars;
    private volatile int wheelLines = 3;
    private volatile int chatRows;
    private volatile org.github.lscoughlin.jeorje.ui.chat.Verbosity verbosity =
            org.github.lscoughlin.jeorje.ui.chat.Verbosity.NORMAL;
    private final java.util.Queue<Runnable> inputTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean exited = new AtomicBoolean();

    private InputLine input;
    private Attributes savedAttributes;
    private Terminal.SignalHandler previousWinch;
    private boolean kittyOn;
    private boolean inBandOn;
    private Thread shutdownHook;
    private Thread eventThread;
    private Thread inputThread;

    public Screen(Terminal terminal, Options options) {
        this(terminal, options, null);
    }

    /**
     * Creates a screen whose chat views highlight code and diffs.
     *
     * @param highlight the shared highlighting state built from {@code ui.chat.highlight}, or null for none
     */
    public Screen(Terminal terminal, Options options, org.github.lscoughlin.jeorje.ui.chat.HighlightContext highlight) {
        this.terminal = terminal;
        this.options = options;
        this.highlight = highlight;
        this.display = new Display(terminal, true);
        // Scroll optimization inserts lines on the whole screen. That moves the input row and the status row.
        this.display.setScrollOptimization(false);
        this.handler = options.actionHandler();
        chats.put(foreground, new ChatView(highlight));
        status.agent(foreground.value());
    }

    // ---- terminal modes ----

    /** Enters raw mode and the alternate screen, and starts the event loop and the input loop. */
    public void start() {
        enterTerminal();
        input = new InputLine(terminal, options.historyFile());
        bindActions();
        running = true;
        eventThread = Thread.ofVirtual().name("jeorje-ui-events").start(this::eventLoop);
        inputThread = Thread.ofVirtual().name("jeorje-ui-input").start(this::inputLoop);
        draw();
    }

    /**
     * Enters raw mode with {@code IXON} and {@code IXOFF} cleared, the alternate screen, in-band resize, and the
     * Kitty keyboard protocol. It does not start threads.
     */
    public void enterTerminal() {
        if (!entered.compareAndSet(false, true)) return;
        savedAttributes = terminal.enterRawMode();
        var attrs = terminal.getAttributes();
        attrs.setInputFlag(Attributes.InputFlag.IXON, false);
        attrs.setInputFlag(Attributes.InputFlag.IXOFF, false);
        // JLine ends the read on VEOF at an empty line before any widget runs. Ctrl-D must interrupt, so turn VEOF off.
        attrs.setControlChar(Attributes.ControlChar.VEOF, 0);
        terminal.setAttributes(attrs);
        terminal.puts(InfoCmp.Capability.enter_ca_mode);
        terminal.puts(InfoCmp.Capability.clear_screen);
        if (terminal.hasInBandResizeSupport()) inBandOn = terminal.trackInBandResize(true);
        if (!"off".equals(options.keyboardProtocol()) && terminal.hasKittyKeyboardSupport()) {
            kittyOn = terminal.setKittyKeyboardMode(EnumSet.of(Terminal.KittyKeyboardMode.Disambiguate));
        }
        previousWinch = terminal.handle(Terminal.Signal.WINCH, signal -> onResize());
        shutdownHook = new Thread(this::restoreTerminal, "jeorje-ui-restore");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            shutdownHook = null;
        }
        terminal.flush();
        restored.set(false);
    }

    /** Restores the terminal. It is safe to call more than once. A signal calls it through a shutdown hook. */
    public void restoreTerminal() {
        if (!entered.get() || !restored.compareAndSet(false, true)) return;
        try {
            if (kittyOn) terminal.resetKittyKeyboardMode();
            if (inBandOn) terminal.trackInBandResize(false);
            terminal.puts(InfoCmp.Capability.exit_ca_mode);
            if (savedAttributes != null) terminal.setAttributes(savedAttributes);
            if (previousWinch != null) terminal.handle(Terminal.Signal.WINCH, previousWinch);
            terminal.flush();
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot restore the terminal: " + e);
        }
    }

    @Override
    public void close() {
        running = false;
        paneAbort = true;
        if (inputThread != null) inputThread.interrupt();
        if (eventThread != null) eventThread.interrupt();
        joinQuietly(inputThread);
        joinQuietly(eventThread);
        restoreTerminal();
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // the JVM shuts down
            }
            shutdownHook = null;
        }
        entered.set(false);
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- state ----

    public StatusLine status() { return status; }

    /** Sets the handler for the key actions of the chat screen. */
    public void setActionHandler(ActionHandler actionHandler) {
        this.handler = actionHandler == null ? ActionHandler.NONE : actionHandler;
    }

    /** Whether the input loop and the event loop run. It is false after the {@code exit} action. */
    public boolean isRunning() { return running; }

    /** The number of rows that the input line uses now. */
    public int inputRows() { return inputRows; }

    public AgentId foreground() { return foreground; }

    /** The chat view of an agent. It is created on first use. */
    public ChatView chat(AgentId agent) {
        return chats.computeIfAbsent(agent, a -> {
            var view = new ChatView(highlight);
            view.verbosity(verbosity);
            if (scrollBars) view.scrollBar(true, wheelLines);
            return view;
        });
    }

    /** The level of detail that the chat views show. */
    public org.github.lscoughlin.jeorje.ui.chat.Verbosity verbosity() { return verbosity; }

    /**
     * Sets the level of detail for every chat view, and for the views that the screen makes later.
     *
     * @param level the new level, or null for {@link org.github.lscoughlin.jeorje.ui.chat.Verbosity#NORMAL}
     */
    public void setVerbosity(org.github.lscoughlin.jeorje.ui.chat.Verbosity level) {
        verbosity = level == null ? org.github.lscoughlin.jeorje.ui.chat.Verbosity.NORMAL : level;
        chats.values().forEach(c -> c.verbosity(verbosity));
        draw();
    }

    /**
     * Turns the scroll bar of the chat views on or off. The bar uses the last column. Call it with true only when
     * the mouse works.
     */
    public void setScrollBars(boolean on, int wheelLines) {
        this.scrollBars = on;
        this.wheelLines = Math.max(1, wheelLines);
        chats.values().forEach(c -> c.scrollBar(on, this.wheelLines));
    }

    /** The cells of the chat text for the last draw, or an empty rectangle when a pane covers the chat. */
    public Rect chatTextArea() {
        if (activePane != null || chatRows <= 0) return new Rect(0, 0, 0, 0);
        return new Rect(0, 0, Math.max(1, drawnColumns - (scrollBars ? ChatView.BAR_COLUMNS : 0)), chatRows);
    }

    /** The cells of the scroll bar of the chat view, or an empty rectangle when there is none. */
    public Rect chatBarArea() {
        if (!scrollBars || activePane != null || chatRows <= 0) return new Rect(0, 0, 0, 0);
        return new Rect(Math.max(0, drawnColumns - ChatView.BAR_COLUMNS), 0, 1, chatRows);
    }

    /** The cells of the follow notice in the status row, or an empty rectangle when there is no notice. */
    public Rect followNoticeArea() {
        var text = status.follow();
        if (text == null || drawnRows <= 0) return new Rect(0, 0, 0, 0);
        return new Rect(0, drawnRows - 1, Math.min(text.length(), Math.max(1, drawnColumns)), 1);
    }

    /**
     * Runs a task on the input thread, between two reads of the input line. A task that opens the editor needs
     * this. Any thread can call it, but not the input thread itself.
     */
    public void runOnInput(Runnable task) {
        inputTasks.add(task);
        wakeInput();
    }

    private void runInputTasks() {
        Runnable task;
        while ((task = inputTasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Input task failed: " + e);
            }
        }
    }

    /** Switches the chat view to another agent. */
    public void setForeground(AgentId agent) {
        chat(agent);
        foreground = agent;
        status.agent(agent.value());
        draw();
    }

    /** The number of full draws so far. */
    public int redrawCount() { return redraws.get(); }

    /** The size of the last draw as {columns, rows}. */
    public int[] drawnSize() { return new int[] {drawnColumns, drawnRows}; }

    // ---- events ----

    /** Puts an event in the queue. The event loop applies it. Any thread can call this method. */
    public void post(AgentEvent event) { events.add(event); }

    /** Applies all queued events now and draws once. Returns the number of applied events. */
    public int drain() {
        var list = new ArrayList<AgentEvent>();
        events.drainTo(list);
        list.forEach(this::applyNoDraw);
        if (!list.isEmpty()) draw();
        return list.size();
    }

    /** Applies one event and draws. */
    public void apply(AgentEvent event) {
        applyNoDraw(event);
        draw();
    }

    private void applyNoDraw(AgentEvent event) {
        chat(event.agent()).apply(event);
        switch (event) {
            case AgentEvent.ConfirmationRequested c -> {
                synchronized (prompts) {
                    prompts.addLast(new PermissionPrompt(c.agent(), c.toolUse()));
                }
                wakeInput();
            }
            case AgentEvent.Cleared cleared when event.agent().equals(foreground) -> {
                // The event already reset the chat view. Clear the terminal and draw every row again.
                resetScreen();
            }
            case AgentEvent.Error e when !e.agent().equals(foreground) ->
                    status.notice(e.agent() + ": " + e.message());
            case AgentEvent.ContextUsage u when event.agent().equals(foreground) ->
                    status.contextPercent(u.budget() <= 0 ? -1 : (int) Math.min(999, Math.round(u.tokens() * 100.0 / u.budget())));
            case AgentEvent.TurnFinished t when event.agent().equals(foreground) -> status.state("idle");
            case AgentEvent.TokenDelta d when event.agent().equals(foreground) -> status.state("working");
            default -> {}
        }
    }

    private void eventLoop() {
        while (running) {
            try {
                var first = events.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                applyNoDraw(first);
                var more = new ArrayList<AgentEvent>();
                events.drainTo(more);
                more.forEach(this::applyNoDraw);
                draw();
            } catch (InterruptedException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                log.atError().log(() -> "UI event failed: " + e);
            }
        }
    }

    // ---- permission prompts ----

    /** The prompt that waits for an answer, or null. */
    public PermissionPrompt pendingPrompt() {
        synchronized (prompts) {
            return prompts.peekFirst();
        }
    }

    /** Sends a key to the first permission prompt. Returns the decision when the key ends the prompt. */
    public Decision handlePromptKey(String key) {
        PermissionPrompt prompt;
        Decision decision = null;
        synchronized (prompts) {
            prompt = prompts.peekFirst();
            if (prompt == null) return null;
            decision = prompt.handleKey(key).orElse(null);
            if (decision != null) prompts.removeFirst();
        }
        if (decision != null) {
            var d = decision;
            chat(prompt.agent()).appendNotice("permission " + prompt.toolUse().tool() + ": " + d);
            options.onDecision().decided(prompt.agent(), prompt.toolUse(), d);
        }
        draw();
        return decision;
    }

    // ---- drawing ----

    private int rows() {
        var r = terminal.getSize().getRows();
        return r > 0 ? r : 24;
    }

    private int columns() {
        var c = terminal.getSize().getColumns();
        return c > 0 ? c : 80;
    }

    /** The lines of the top region: the chat view, and the permission box if a prompt waits. */
    public List<AttributedString> renderRegion(int rows, int columns) {
        var regionRows = Math.max(1, rows - 1 - inputRows);
        var pane = activePane;
        if (pane != null) {
            chatRows = 0;
            return paneRegion(pane, regionRows, columns);
        }
        var box = List.<AttributedString>of();
        var prompt = pendingPrompt();
        var form = prompt == null ? pendingForm() : null;
        formTop = -1;
        formCursorRow = -1;
        if (prompt != null) {
            box = prompt.render(columns);
            if (box.size() >= regionRows) box = box.subList(0, Math.max(0, regionRows - 1));
        } else if (form != null) {
            box = form.render(columns, Math.max(1, Math.min(regionRows - 1, Math.max(5, regionRows * 6 / 10))));
        }
        var chatRows = Math.max(1, regionRows - box.size());
        if (form != null) {
            formTop = chatRows;
            if (form.cursorRow() >= 0) {
                formCursorRow = chatRows + form.cursorRow();
                formCursorColumn = form.cursorColumn();
            }
        }
        this.chatRows = chatRows;
        var lines = new ArrayList<>(chat(foreground).visible(columns, chatRows));
        while (lines.size() < chatRows) lines.add(AttributedString.EMPTY);
        lines.addAll(box);
        return lines;
    }

    /** All rows of the screen: the region, the input row, and the status row. */
    public List<AttributedString> renderScreen(int rows, int columns) {
        var all = new ArrayList<>(renderRegion(rows, columns));
        var text = input == null ? "" : input.buffer();
        var parts = text.split("\n", -1);
        for (var i = 0; i < inputRows; i++) {
            var part = i < parts.length ? parts[i] : "";
            all.add(new AttributedString((i == 0 ? PROMPT : "") + part));
        }
        all.add(status.render(columns));
        return all;
    }

    /** Draws the screen. Display writes only the lines that changed. */
    public void draw() {
        synchronized (drawLock) {
            if (suspended) return;
            drawScreen(false);
        }
    }

    /**
     * Starts the screen again: clears the terminal and forgets what the display drew. The next draw then writes every
     * row. Use it when the terminal can show stale text, for example after a resize. The method does nothing while
     * {@link #suspendDrawing} holds.
     */
    public void resetScreen() {
        synchronized (drawLock) {
            if (suspended || !entered.get()) return;
            terminal.puts(InfoCmp.Capability.clear_screen);
            terminal.flush();
            redrawAll();
        }
        draw();
        // The input line keeps its own model of the screen. It learns the new cursor row from the next key.
        if (Thread.currentThread() == inputThread && input != null) input.resetDisplay();
    }

    private void redrawAll() {
        display.reset();
        drawnRows = -1;
        drawnColumns = -1;
        drawnRegionRows = -1;
    }

    // ---- forms ----

    /**
     * Shows a form. The form waits behind a permission prompt. The method can run on any thread. {@code onAnswer}
     * runs on the input thread when the form ends. It does not run when {@link #cancelForms} removes the form.
     */
    public void showForm(FormPrompt prompt, Consumer<FormAnswer> onAnswer) {
        synchronized (forms) {
            forms.addLast(new PendingForm(prompt, onAnswer));
        }
        wakeInput();
        draw();
    }

    /** The row and column of the terminal cursor for a text field of the form, or {-1, 0}. For tests. */
    int[] formCursor() {
        return new int[] {formCursorRow, formCursorColumn};
    }

    /** The form that waits for the user, or null. */
    public FormPrompt pendingForm() {
        synchronized (forms) {
            var first = forms.peekFirst();
            return first == null ? null : first.prompt();
        }
    }

    /** Removes the forms of an agent without an answer. Returns the number of removed forms. */
    public int cancelForms(AgentId agent) {
        int removed;
        synchronized (forms) {
            var before = forms.size();
            forms.removeIf(f -> f.prompt().agent().equals(agent));
            removed = before - forms.size();
        }
        if (removed > 0) draw();
        return removed;
    }

    /** Sends a key to the first form. Returns the answer when the key ends the form, otherwise null. */
    public FormAnswer handleFormKey(String key) {
        PendingForm pending;
        synchronized (forms) {
            pending = forms.peekFirst();
        }
        if (pending == null || key == null) return null;
        var prompt = pending.prompt();
        Optional<FormAnswer> answer;
        if (key.startsWith("mouse:")) {
            answer = formMouse(prompt, key);
        } else {
            var mapped = translateFormKey(prompt, key);
            answer = mapped.isEmpty() ? Optional.empty() : prompt.handleKey(mapped);
        }
        if (answer.isPresent()) {
            synchronized (forms) {
                forms.remove(pending);
            }
            chat(prompt.agent()).appendNotice(summary(answer.get()));
            pending.onAnswer().accept(answer.get());
        }
        draw();
        return answer.orElse(null);
    }

    private static String summary(FormAnswer answer) {
        return switch (answer.status()) {
            case ANSWERED -> {
                var text = answer.answers().stream().map(FormAnswer.QuestionAnswer::value)
                        .collect(java.util.stream.Collectors.joining(" | "));
                yield "form answered: " + (text.length() > 80 ? text.substring(0, 79) + "…" : text);
            }
            case CHAT -> "form: chat requested";
            case DECLINED -> "form declined";
        };
    }

    private Optional<FormAnswer> formMouse(FormPrompt prompt, String key) {
        var parts = key.split(":");
        if (parts.length < 4) return Optional.empty();
        try {
            var button = Integer.parseInt(parts[1]);
            var y = Integer.parseInt(parts[3]) - 1;
            return switch (button) {
                case 0 -> formTop < 0 ? Optional.empty() : prompt.click(y - formTop);
                case 64 -> prompt.handleKey("up");
                case 65 -> prompt.handleKey("down");
                default -> Optional.empty();
            };
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Maps a key through the form key bindings. A key that is bound to a form action gives the default key name of
     * that action. A default key of an action that the user moved to another key does nothing. Text fields get
     * every key as it is.
     */
    private String translateFormKey(FormPrompt prompt, String key) {
        if (prompt.editing()) return key;
        var bindings = options.keyBindings().bindings(KeyContext.FORM);
        KeySpec pressed;
        try {
            pressed = KeySpec.parse(specText(key));
        } catch (IllegalArgumentException e) {
            return key;
        }
        for (var b : bindings) {
            if (b.keys().contains(pressed)) return FORM_KEYS.getOrDefault(b.action().id(), key);
        }
        for (var b : bindings) {
            if (b.action().defaults().contains(pressed)) return "";
        }
        return key;
    }

    private static String specText(String key) {
        return switch (key) {
            case "up" -> "Up";
            case "down" -> "Down";
            case "left" -> "Left";
            case "right" -> "Right";
            case "tab" -> "Tab";
            case "shift-tab" -> "Shift-Tab";
            case "space" -> "Space";
            case "esc" -> "Esc";
            case "enter" -> "Enter";
            case "backspace" -> "Backspace";
            case "delete" -> "Del";
            case "home" -> "Home";
            case "end" -> "End";
            case "ctrl-u" -> "Ctrl-U";
            default -> key;
        };
    }

    /** Draws the region, the input row, and the status row. Call it inside the drawing lock. */
    private void drawScreen(boolean whole) {
        var rows = rows();
        var cols = columns();
        syncNotices();
        var region = renderRegion(rows, cols);
        var resized = rows != drawnRows || cols != drawnColumns || region.size() != drawnRegionRows;
        Runnable body = () -> {
            if (whole) {
                terminal.puts(InfoCmp.Capability.clear_screen);
                display.reset();
            }
            if (resized) {
                display.resize(region.size(), cols);
                display.reset();
            }
            // Display moves the cursor relative to the home cell. Other code moves the cursor, so go home first.
            terminal.puts(InfoCmp.Capability.cursor_address, 0, 0);
            display.update(region, 0);
            terminal.puts(InfoCmp.Capability.cursor_address, rows - 1, 0);
            terminal.writer().print(status.render(cols).toAnsi(terminal));
            terminal.puts(InfoCmp.Capability.clr_eol);
            if (formCursorRow >= 0 && pendingPrompt() == null) {
                terminal.puts(InfoCmp.Capability.cursor_address, formCursorRow, formCursorColumn);
            } else {
                terminal.puts(InfoCmp.Capability.cursor_address, Math.max(0, rows - 1 - inputRows), 0);
            }
            terminal.flush();
        };
        if (terminal.isModeSupported(Terminal.Mode.SYNCHRONIZED_OUTPUT)) terminal.synchronizedUpdate(body);
        else body.run();
        drawnRows = rows;
        drawnColumns = cols;
        drawnRegionRows = region.size();
        redraws.incrementAndGet();
        if (running && reading && input != null && activePane == null
                && Thread.currentThread() != inputThread) {
            input.redraw();
        }
    }

    private volatile boolean suspended;

    /**
     * Stops all drawing while another full-screen program, such as the editor, owns the terminal. The method
     * {@link #repaint} ends the pause and draws the whole screen again.
     */
    public void suspendDrawing() {
        synchronized (drawLock) {
            suspended = true;
        }
    }

    /** Ends a pause of {@link #suspendDrawing} and draws every row again, as the terminal content is unknown. */
    public void repaint() {
        synchronized (drawLock) {
            suspended = false;
            drawScreen(true);
        }
        if (Thread.currentThread() == inputThread && input != null) input.resetDisplay();
    }

    /** Puts the follow notice of the foreground chat and the notices of the highlighter in the status line. */
    private void syncNotices() {
        status.follow(chat(foreground).followNotice(followKey()));
        if (highlight != null) highlight.takeNotices().forEach(status::notice);
    }

    private String followKey() {
        try {
            var keys = options.keyBindings() == null ? KeyBindings.defaults() : options.keyBindings();
            var shown = keys.display(org.github.lscoughlin.jeorje.ui.keys.KeyScope.UI, "follow");
            return shown.isBlank() || "none".equals(shown) ? "Ctrl-X b" : shown;
        } catch (RuntimeException e) {
            return "Ctrl-X b";
        }
    }

    private void onResize() {
        log.atDebug().log(() -> "Terminal resized to " + columns() + "x" + rows());
        draw();
    }

    // ---- input ----

    private void wakeInput() {
        var t = inputThread;
        if (t != null && reading) t.interrupt();
    }

    private void inputLoop() {
        var partial = "";
        while (running) {
            runInputTasks();
            if (pendingPrompt() != null || pendingForm() != null) {
                promptLoop();
                continue;
            }
            try {
                setInputRows(heightOf(partial));
                terminal.puts(InfoCmp.Capability.cursor_address, Math.max(0, rows() - 1 - inputRows), 0);
                terminal.flush();
                reading = true;
                var line = input.readLine(PROMPT, partial);
                reading = false;
                partial = "";
                clearInputRows();
                setInputRows(1);
                if (line == null || line.isBlank()) continue;
                chat(foreground).appendUser(line);
                status.state("working");
                draw();
                options.onSubmit().accept(line);
            } catch (UserInterruptException e) {
                reading = false;
                if (!running) return;
                if (pendingPrompt() != null || pendingForm() != null || !inputTasks.isEmpty() || Thread.interrupted()) {
                    partial = e.getPartialLine() == null ? "" : e.getPartialLine();
                } else {
                    partial = "";
                    options.onInterrupt().run();
                    handler.handle("interrupt");
                }
            } catch (EndOfFileException e) {
                reading = false;
                exitSession();
                return;
            } catch (IOError | RuntimeException e) {
                reading = false;
                if (!running) return;
                if (pendingPrompt() == null && pendingForm() == null) {
                    log.atWarn().log(() -> "Input failed: " + e);
                    return;
                }
            }
        }
    }

    /** Ends the session after the {@code exit} action: restores the terminal, then calls the handler. */
    private void exitSession() {
        running = false;
        if (!exited.compareAndSet(false, true)) return;
        log.atDebug().log(() -> "The user ends the session");
        restoreTerminal();
        handler.exit();
    }

    private void promptLoop() {
        draw();
        while (running && (pendingPrompt() != null || pendingForm() != null)) {
            var key = readPromptKey();
            if (key == null) continue;
            if (key.equals("ctrl-c")) {
                options.onInterrupt().run();
                handler.handle("interrupt");
                continue;
            }
            if (pendingPrompt() != null) handlePromptKey(key); else handleFormKey(key);
        }
    }

    /**
     * Reads one key for a prompt or a form. The names are {@code up down left right home end delete tab shift-tab
     * enter esc space backspace ctrl-u}, a printable character as a text, or {@code mouse:<button>:<x>:<y>} for a
     * mouse press or wheel step (the terminal sends it when mouse tracking is on). Other input gives null.
     */
    String readPromptKey() {
        try {
            NonBlockingReader reader = terminal.reader();
            var c = reader.read(100);
            if (c == NonBlockingReader.READ_EXPIRED) return null;
            if (c < 0) {
                running = false;
                return null;
            }
            if (c == '\r' || c == '\n') return "enter";
            if (c == '\t') return "tab";
            if (c == ' ') return "space";
            if (c == 127 || c == 8) return "backspace";
            if (c == 21) return "ctrl-u";
            if (c == 3) return "ctrl-c";
            if (c == 27) {
                var next = reader.peek(50);
                if (next == '[' || next == 'O') {
                    reader.read(50);
                    return escapeSequence(reader);
                }
                return "esc";
            }
            if (c < 32) return null;
            if (Character.isHighSurrogate((char) c)) {
                var low = reader.read(50);
                return low >= 0 ? new String(new char[] {(char) c, (char) low}) : null;
            }
            return String.valueOf((char) c);
        } catch (IOException | IOError e) {
            return null;
        }
    }

    private static String escapeSequence(NonBlockingReader reader) throws IOException {
        var params = new StringBuilder();
        int code;
        while (true) {
            code = reader.read(50);
            if (code < 0) return null;
            if (code >= 0x30 && code <= 0x3F) params.append((char) code); else break;
        }
        var text = params.toString();
        if (text.startsWith("<")) {
            if (code != 'M') return null;
            var f = text.substring(1).split(";");
            return f.length == 3 ? "mouse:" + f[0] + ":" + f[1] + ":" + f[2] : null;
        }
        return switch (code) {
            case 'A' -> "up";
            case 'B' -> "down";
            case 'C' -> "right";
            case 'D' -> "left";
            case 'H' -> "home";
            case 'F' -> "end";
            case 'Z' -> "shift-tab";
            case '~' -> switch (text) {
                case "3" -> "delete";
                case "1", "7" -> "home";
                case "4", "8" -> "end";
                default -> null;
            };
            default -> null;
        };
    }

    // ---- key actions ----

    private void bindActions() {
        // First free the default keys that the config moved. A freed key does nothing, not even a typed character.
        var bindings = options.keyBindings().bindings(KeyContext.CHAT);
        var taken = bindings.stream().flatMap(b -> sequencesOf(b.keys()).stream()).collect(java.util.stream.Collectors.toSet());
        var freed = new ArrayList<String>();
        for (var binding : bindings) {
            var sequences = sequencesOf(binding.keys());
            sequencesOf(binding.action().defaults()).stream()
                    .filter(d -> !sequences.contains(d) && !taken.contains(d)).forEach(freed::add);
        }
        input.unbind(freed);
        if (!freed.isEmpty()) input.bindWidget("jeorje-noop", () -> true, freed);
        for (var binding : bindings) {
            var action = binding.action();
            var sequences = sequencesOf(binding.keys());
            var id = action.id();
            input.bindWidget("jeorje-" + id, () -> runAction(id), sequences);
        }
        input.bindWidget("jeorje-complete", this::completeInput, List.of("\t"));
        input.afterEachWidget(this::afterInput);
    }

    private volatile org.github.lscoughlin.jeorje.ui.commands.InputCompletion completion;

    /** Sets the completion that the {@code Tab} key of the input line uses. */
    public void setCompletion(org.github.lscoughlin.jeorje.ui.commands.InputCompletion completion) {
        this.completion = completion;
    }

    /** {@code Tab}: completes the text before the cursor. Several candidates go to the chat view as a notice. */
    private boolean completeInput() {
        var active = completion;
        if (active == null) return true;
        var before = input.beforeCursor();
        var result = active.complete(before);
        if (!result.line().equals(before)) input.replaceBeforeCursor(result.line());
        if (!result.shown().isEmpty()) {
            var chat = chat(foreground);
            chat.appendNotice(result.shown().size() + " matches:");
            result.shown().forEach(line -> chat.appendNotice("  " + line));
            draw();
            input.resetDisplay();
        }
        return true;
    }

    private List<String> sequencesOf(List<org.github.lscoughlin.jeorje.ui.keys.KeySpec> keys) {
        return keys.stream().flatMap(k -> k.sequences(kittyOn).stream()).distinct().toList();
    }

    private boolean runAction(String id) {
        switch (id) {
            case "send" -> {
                handler.handle(id);
                input.callWidget(LineReader.ACCEPT_LINE);
                return true;
            }
            case "newline" -> input.insertNewline();
            case "interrupt" -> throw new UserInterruptException(input.buffer());
            case "expandResult" -> {
                chat(foreground).toggleLastResult();
                draw();
            }
            case "redraw" -> {
                synchronized (drawLock) {
                    redrawAll();
                    draw();
                }
                input.resetDisplay();
            }
            case "scrollUp" -> {
                chat(foreground).scrollUp(Math.max(1, rows() - 2 - inputRows));
                draw();
            }
            case "scrollDown" -> {
                chat(foreground).scrollDown(Math.max(1, rows() - 2 - inputRows));
                draw();
            }
            case "follow" -> {
                chat(foreground).scrollToEnd();
                draw();
            }
            default -> {}
        }
        handler.handle(id);
        return true;
    }

    // ---- input height ----

    private int heightOf(String text) {
        var cols = Math.max(1, columns());
        var lines = text == null ? new String[] {""} : text.split("\n", -1);
        var height = 0;
        for (var i = 0; i < lines.length; i++) {
            var width = (i == 0 ? PROMPT.length() : 0) + lines[i].length() + (i == lines.length - 1 ? 1 : 0);
            height += Math.max(1, (width + cols - 1) / cols);
        }
        return Math.clamp(height, 1, Math.max(1, rows() - 3));
    }

    /** Clears the rows of the input line after the user sent a line. */
    private void clearInputRows() {
        if (!entered.get()) return;
        var rows = rows();
        for (var r = Math.max(0, rows - 1 - inputRows); r <= rows - 2; r++) {
            terminal.puts(InfoCmp.Capability.cursor_address, r, 0);
            terminal.puts(InfoCmp.Capability.clr_eol);
        }
        terminal.flush();
    }

    /** Reserves rows for the input line. It clears the old rows and draws the chat region again. */
    private void setInputRows(int height) {
        if (height == inputRows) return;
        var rows = rows();
        var oldTop = rows - 1 - inputRows;
        var newTop = rows - 1 - height;
        inputRows = height;
        if (entered.get()) {
            for (var r = Math.max(0, Math.min(oldTop, newTop)); r <= rows - 2; r++) {
                terminal.puts(InfoCmp.Capability.cursor_address, r, 0);
                terminal.puts(InfoCmp.Capability.clr_eol);
            }
        }
        draw();
    }

    /** Runs after each key of the input line. It grows or shrinks the input rows to fit the buffer. */
    private void afterInput() {
        var height = heightOf(input.buffer());
        if (height == inputRows) return;
        setInputRows(height);
        terminal.puts(InfoCmp.Capability.cursor_address, Math.max(0, rows() - 1 - inputRows), 0);
        terminal.flush();
        input.resetDisplay();
    }

    // ---- panes ----

    private volatile Rect paneBounds = new Rect(0, 1, 1, 1);

    private List<AttributedString> paneRegion(Pane<?> pane, int regionRows, int columns) {
        var area = new Rect(0, 1, columns, Math.max(1, regionRows - 1));
        paneBounds = area;
        pane.resize(area);
        var lines = new ArrayList<AttributedString>();
        lines.add(new AttributedString(fit(pane.title(), columns), AttributedStyle.DEFAULT.inverse()));
        for (var row : pane.render()) lines.add(new AttributedString(fit(row, columns)));
        while (lines.size() < regionRows) lines.add(AttributedString.EMPTY);
        return new ArrayList<>(lines.subList(0, regionRows));
    }

    private static String fit(String text, int columns) {
        var clean = text.replace('\t', ' ').replace('\n', ' ');
        return clean.length() <= columns ? clean : clean.substring(0, columns);
    }

    /** Whether a pane takes the keyboard now. */
    public boolean paneActive() { return activePane != null; }

    /**
     * Shows a pane in the chat region and gives it the keyboard and the mouse. It blocks until the pane has a result
     * or the user cancels it with {@code Esc}. Then it restores the chat view. Call it from the action handler.
     *
     * @return the result, or empty when the user cancelled
     */
    public <T> Optional<T> runPane(Pane<T> pane) {
        synchronized (this) {
            if (activePane != null) return Optional.empty();
            activePane = pane;
        }
        paneAbort = false;
        var tracking = false;
        try {
            tracking = terminal.hasMouseSupport() && terminal.trackMouse(Terminal.MouseTracking.Button);
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "Cannot track the mouse: " + e);
        }
        var clicks = new ClickCounter(System::currentTimeMillis);
        var matcher = new PaneKeys(pane);
        var toggled = false;
        try {
            pane.begin();
            draw();
            while (!pane.done() && !paneAbort && !toggled) {
                var in = readPaneInput(pane, matcher);
                if (in == null) continue;
                if (in.eof()) break;
                if (in.mouse() != null) paneMouse(pane, in.mouse(), clicks);
                else if (in.action() != null) {
                    if (in.action().startsWith(PaneKeys.TOGGLE)) toggled = true;
                    else pane.action(in.action());
                } else if (!in.key().isEmpty()) pane.key(in.key());
                draw();
            }
        } finally {
            pane.end();
            activePane = null;
            if (tracking) {
                terminal.trackMouse(Terminal.MouseTracking.Off);
                terminal.flush();
            }
            draw();
            if (Thread.currentThread() == inputThread && input != null) input.resetDisplay();
        }
        return pane.done() && !toggled ? pane.result() : Optional.empty();
    }

    /**
     * The configured keys that a pane reads: the toggle key of the pane and the action keys of its context. It
     * matches raw key sequences, also the chords, against the keys.
     */
    private final class PaneKeys {
        static final String TOGGLE = "toggle:";
        private final java.util.Map<String, String> full = new java.util.HashMap<>();
        private final java.util.Set<String> prefixes = new java.util.HashSet<>();
        private String pending = "";

        PaneKeys(Pane<?> pane) {
            var keys = options.keyBindings() == null ? KeyBindings.defaults() : options.keyBindings();
            var toggle = pane.toggleAction();
            if (toggle != null) {
                keys.binding(org.github.lscoughlin.jeorje.ui.keys.KeyScope.UI, toggle)
                        .ifPresent(b -> add(b.keys(), TOGGLE + toggle));
            }
            var context = pane.keyContext();
            if (context != null) {
                for (var binding : keys.bindings(context)) add(binding.keys(), binding.action().id());
            }
        }

        private void add(List<org.github.lscoughlin.jeorje.ui.keys.KeySpec> specs, String id) {
            for (var spec : specs) {
                for (var sequence : spec.sequences(kittyOn)) full.put(sequence, id);
                for (var n = 1; n < spec.keys().size(); n++) {
                    prefixes.addAll(new org.github.lscoughlin.jeorje.ui.keys.KeySpec(spec.keys().subList(0, n))
                            .sequences(kittyOn));
                }
            }
        }

        boolean empty() { return full.isEmpty(); }

        /** Feeds one key. Returns the action id, "" while a chord is open or broken, or null for a plain key. */
        String feed(String raw) {
            var candidate = pending + raw;
            var id = full.get(candidate);
            if (id != null) {
                pending = "";
                return id;
            }
            if (prefixes.contains(candidate)) {
                pending = candidate;
                return "";
            }
            var broken = !pending.isEmpty();
            pending = "";
            return broken ? "" : null;
        }

        void reset() { pending = ""; }
    }

    private void paneMouse(Pane<?> pane, MouseEvent event, ClickCounter clicks) {
        var count = switch (event.getType()) {
            case Pressed -> clicks.press(event.getX(), event.getY());
            case Dragged, Released -> clicks.current();
            default -> 0;
        };
        var area = paneBounds;
        var relative = new RegionEvent(event.getType(), event.getButton(), event.getModifiers(),
                event.getX() - area.x(), event.getY() - area.y(), count);
        if (relative.isWheel() || area.contains(event.getX(), event.getY())) pane.mouse(relative);
    }

    private record PaneInput(String key, MouseEvent mouse, boolean eof, String raw, String action) {
        PaneInput(String key, MouseEvent mouse, boolean eof, String raw) { this(key, mouse, eof, raw, null); }
    }

    private PaneInput readPaneInput(Pane<?> pane, PaneKeys matcher) {
        var in = readPaneToken();
        if (in == null || in.eof() || in.mouse() != null) return in;
        if (matcher.empty() || pane.textInput()) {
            matcher.reset();
            return in;
        }
        var result = matcher.feed(in.raw());
        if (result == null) return in;
        if (result.isEmpty()) return null;
        return new PaneInput("", null, false, in.raw(), result);
    }

    private PaneInput readPaneToken() {
        try {
            NonBlockingReader reader = terminal.reader();
            var c = reader.read(100);
            if (c == NonBlockingReader.READ_EXPIRED) return null;
            if (c < 0) return new PaneInput("", null, true, "");
            var raw = new String(Character.toChars(c));
            return switch (c) {
                case '\r', '\n' -> new PaneInput("ENTER", null, false, raw);
                case 127, 8 -> new PaneInput("BACKSPACE", null, false, raw);
                case 27 -> readEscape(reader);
                default -> c < 32 ? new PaneInput("", null, false, raw) : new PaneInput(raw, null, false, raw);
            };
        } catch (IOException | IOError e) {
            return null;
        }
    }

    private PaneInput readEscape(NonBlockingReader reader) throws IOException {
        var next = reader.peek(50);
        if (next != '[' && next != 'O') return new PaneInput("ESC", null, false, "\u001b");
        reader.read(50);
        var code = reader.read(50);
        if (code == '<') return new PaneInput("", terminal.readMouseEvent("\u001b[<"), false, "");
        if (code == 'M' && next == '[') return new PaneInput("", terminal.readMouseEvent("\u001b[M"), false, "");
        var raw = new StringBuilder("\u001b").append((char) next);
        var params = new StringBuilder();
        while (code >= 0x20 && code < 0x40) {
            params.append((char) code);
            raw.append((char) code);
            code = reader.read(50);
        }
        if (code >= 0) raw.append((char) code);
        var key = switch (code) {
            case 'A' -> "UP";
            case 'B' -> "DOWN";
            case 'C' -> "RIGHT";
            case 'D' -> "LEFT";
            case 'u' -> switch (params.toString().split(";")[0]) {
                case "27" -> "ESC";
                case "13" -> "ENTER";
                case "127" -> "BACKSPACE";
                default -> "";
            };
            default -> "";
        };
        return new PaneInput(key, null, false, raw.toString());
    }
}
