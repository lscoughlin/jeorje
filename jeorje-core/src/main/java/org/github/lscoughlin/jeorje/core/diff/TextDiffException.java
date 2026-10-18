package org.github.lscoughlin.jeorje.core.diff;

/** Error from the text diff facade. The kind tells the caller what went wrong. */
public final class TextDiffException extends RuntimeException {

    /** The reason for the error. */
    public enum Kind { BINARY, TOO_LARGE, TIMEOUT, UNSUPPORTED, MALFORMED }

    private final Kind kind;

    public TextDiffException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
