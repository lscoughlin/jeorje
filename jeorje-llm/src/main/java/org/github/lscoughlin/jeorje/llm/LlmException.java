package org.github.lscoughlin.jeorje.llm;

import java.io.IOException;

/** An error from the model endpoint. The status is 0 when the error is not an HTTP status. */
public class LlmException extends IOException {
    private final int status;
    private final boolean retryable;

    public LlmException(String message, int status, boolean retryable) {
        super(message);
        this.status = status;
        this.retryable = retryable;
    }

    public int status() { return status; }

    public boolean retryable() { return retryable; }
}
