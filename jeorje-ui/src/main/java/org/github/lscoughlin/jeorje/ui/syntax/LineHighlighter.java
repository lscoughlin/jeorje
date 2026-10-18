package org.github.lscoughlin.jeorje.ui.syntax;

import org.jline.utils.AttributedString;

/** A stateful highlighter for one syntax. It keeps the state of multi-line regions. */
public interface LineHighlighter {

    /** Highlights the next line. */
    AttributedString highlight(String line);

    /** Clears the state of multi-line regions. */
    void reset();
}
