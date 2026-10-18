package org.github.lscoughlin.jeorje.ui.editor.markdown;

import java.util.List;

import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.utils.AttributedString;

/**
 * Tells the editor how to find the regions of other languages in a file and how to color them.
 *
 * <p>A rule keeps its own copy of the text. The editor tells the rule about each change with
 * {@link #replace(int, int, List)}.
 */
public interface InjectionRule {

    /** True when the rule runs for a file with this syntax. */
    boolean appliesTo(SyntaxInfo host);

    /** Sets the whole text of the document. */
    void load(List<String> lines);

    /** Replaces {@code removed} lines at {@code first} with the inserted lines. */
    void replace(int first, int removed, List<String> inserted);

    /** The number of lines of the document. */
    int lineCount();

    /** Returns the styled lines {@code first..last} (inclusive), cut to the document. */
    List<AttributedString> lines(int first, int last);
}
