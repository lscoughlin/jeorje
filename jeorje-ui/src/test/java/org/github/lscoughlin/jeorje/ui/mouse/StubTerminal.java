package org.github.lscoughlin.jeorje.ui.mouse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;

/** A scripted terminal. It records the tracking calls and returns queued mouse events. */
final class StubTerminal {
    final boolean mouseSupport;
    final List<Terminal.MouseTracking> trackingCalls = new ArrayList<>();
    final Map<Terminal.Signal, Terminal.SignalHandler> handlers = new EnumMap<>(Terminal.Signal.class);
    final ArrayDeque<MouseEvent> events = new ArrayDeque<>();
    final StringWriter written = new StringWriter();
    final PrintWriter writer = new PrintWriter(written);
    final Terminal terminal;

    StubTerminal(boolean mouseSupport) {
        this.mouseSupport = mouseSupport;
        this.terminal = (Terminal) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Terminal.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hasMouseSupport" -> {
                            return mouseSupport;
                        }
                        case "trackMouse" -> {
                            trackingCalls.add((Terminal.MouseTracking) args[0]);
                            return true;
                        }
                        case "readMouseEvent" -> {
                            return events.poll();
                        }
                        case "handle" -> {
                            var old = handlers.put((Terminal.Signal) args[0], (Terminal.SignalHandler) args[1]);
                            return old == null ? Terminal.SignalHandler.SIG_DFL : old;
                        }
                        case "writer" -> {
                            return writer;
                        }
                        case "flush" -> {
                            return null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    Terminal.MouseTracking lastTracking() {
        return trackingCalls.isEmpty() ? null : trackingCalls.get(trackingCalls.size() - 1);
    }
}
