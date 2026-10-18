package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.List;

/** A source of older chat lines, for example {@code transcript.jsonl}. */
public interface OlderLines {
    /** Returns up to {@code count} lines that come directly before line number {@code index}, in order. */
    List<String> before(int index, int count);
}
