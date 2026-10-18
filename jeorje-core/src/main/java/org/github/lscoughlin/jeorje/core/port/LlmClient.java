package org.github.lscoughlin.jeorje.core.port;

import java.util.concurrent.Flow;
import org.github.lscoughlin.jeorje.core.model.*;

/** Port for a language model. */
public interface LlmClient {
    /**
     * Streams a model response. The publisher sends events in order and ends with a
     * {@link LlmEvent.MessageStop}. It signals errors with {@code onError}. When the token is
     * cancelled, the client closes the stream and completes without more events.
     */
    Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel);
}
