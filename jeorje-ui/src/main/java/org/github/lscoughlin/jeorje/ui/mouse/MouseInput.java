package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns mouse tracking on and off and reads the events. Tracking goes off at exit, on a signal, and in a shutdown
 * hook, so the terminal returns to its normal state.
 */
public final class MouseInput implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MouseInput.class);
    public static final String NO_MOUSE_NOTICE = "This terminal has no mouse support. Use the keys.";

    private final Terminal terminal;
    private final MouseConfig config;
    private final RegionRegistry registry;
    private final Consumer<String> notice;
    private final ClickCounter clicks;
    private final List<Runnable> restoreHandlers = new ArrayList<>();
    private Thread hook;
    private boolean tracking;
    private boolean started;
    private boolean noticeShown;

    public MouseInput(Terminal terminal, MouseConfig config, RegionRegistry registry, Consumer<String> notice) {
        this(terminal, config, registry, notice, System::currentTimeMillis);
    }

    public MouseInput(
            Terminal terminal,
            MouseConfig config,
            RegionRegistry registry,
            Consumer<String> notice,
            LongSupplier clockMillis) {
        this.terminal = terminal;
        this.config = config;
        this.registry = registry;
        this.notice = notice;
        this.clicks = new ClickCounter(clockMillis);
    }

    /** Starts tracking. Returns false when the mouse is off by config or the terminal has no mouse support. */
    public synchronized boolean start() {
        if (started) return tracking;
        if (!config.enabled()) return false;
        if (!terminal.hasMouseSupport()) {
            if (!noticeShown) {
                noticeShown = true;
                notice.accept(NO_MOUSE_NOTICE);
            }
            return false;
        }
        started = true;
        terminal.trackMouse(Terminal.MouseTracking.Button);
        tracking = true;
        terminal.flush();
        hook = new Thread(this::trackingOff, "jeorje-mouse-off");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException e) {
            hook = null;
        }
        installSignal(Terminal.Signal.INT);
        installSignal(Terminal.Signal.QUIT);
        installSignal(Terminal.Signal.TSTP);
        var previousCont = terminal.handle(Terminal.Signal.CONT, signal -> trackingOnAgain());
        restoreHandlers.add(() -> terminal.handle(Terminal.Signal.CONT, previousCont));
        return true;
    }

    private void installSignal(Terminal.Signal signal) {
        var previous = terminal.handle(signal, s -> {
            trackingOff();
            if (previous0(s) != null) previous0(s).handle(s);
        });
        saved[signal.ordinal()] = previous;
        restoreHandlers.add(() -> terminal.handle(signal, previous));
    }

    private final Terminal.SignalHandler[] saved = new Terminal.SignalHandler[Terminal.Signal.values().length];

    private Terminal.SignalHandler previous0(Terminal.Signal s) {
        var h = saved[s.ordinal()];
        return h == Terminal.SignalHandler.SIG_DFL || h == Terminal.SignalHandler.SIG_IGN ? null : h;
    }

    private synchronized void trackingOnAgain() {
        if (started && !tracking) {
            terminal.trackMouse(Terminal.MouseTracking.Button);
            terminal.flush();
            tracking = true;
        }
    }

    private synchronized void trackingOff() {
        if (!tracking) return;
        tracking = false;
        try {
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.flush();
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "could not turn mouse tracking off: " + e.getMessage());
        }
    }

    /** Turns tracking off while an editor or a full-screen view is open. Call {@link #resume()} after it. */
    public void suspend() {
        trackingOff();
    }

    /** Turns tracking on again after {@link #suspend()}. */
    public void resume() {
        trackingOnAgain();
    }

    public synchronized boolean isTracking() {
        return tracking;
    }

    /** Reads one event with {@link Terminal#readMouseEvent()}. The caller has found the mouse prefix. */
    public MouseEvent read() {
        return terminal.readMouseEvent();
    }

    /** Reads one event and sends it to the registry. */
    public void pump() {
        handle(read());
    }

    /** Sends an event to the registry with a click count. */
    public void handle(MouseEvent event) {
        var count =
                switch (event.getType()) {
                    case Pressed -> clicks.press(event.getX(), event.getY());
                    case Dragged, Released -> clicks.current();
                    default -> 0;
                };
        registry.dispatch(event, count);
    }

    /** Turns tracking off, restores the signal handlers, and removes the shutdown hook. */
    @Override
    public synchronized void close() {
        trackingOff();
        for (var r : restoreHandlers) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "could not restore a signal handler: " + e.getMessage());
            }
        }
        restoreHandlers.clear();
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // The JVM is already shutting down.
            }
            hook = null;
        }
        started = false;
    }
}
