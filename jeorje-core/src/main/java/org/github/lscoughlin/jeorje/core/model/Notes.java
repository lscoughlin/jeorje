package org.github.lscoughlin.jeorje.core.model;

/**
 * System notes. Jeorje adds a note to a turn as a text of the user role, for example to tell the model that its
 * reply was empty. A note is not a word of the user. It starts with {@link #PREFIX}, so that the chunker, the
 * decision block, and the compaction rules can tell it from a human text.
 */
public final class Notes {
    /** The start of every system note. */
    public static final String PREFIX = "[Jeorje note] ";

    private Notes() {}

    /** The text of a note. */
    public static String of(String text) { return PREFIX + text; }

    /** True when the text is a system note. */
    public static boolean is(String text) { return text != null && text.startsWith(PREFIX); }
}
