package org.github.lscoughlin.jeorje.core.port;

import java.nio.file.Path;
import java.time.Clock;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;

/** The environment of a tool run. */
public record ToolContext(Path workingDirectory, AgentId agent, CancellationToken cancel,
                          ContextStore history, Clock clock) {}
