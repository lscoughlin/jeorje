package org.github.lscoughlin.jeorje.tools.registry;

import java.time.Duration;
import org.github.lscoughlin.jeorje.core.port.Tool;

/** A tool with its own timeout. Other tools get the default timeout of the registry. */
public interface TimedTool extends Tool {
    /** The longest time one run can take. */
    Duration timeout();
}
