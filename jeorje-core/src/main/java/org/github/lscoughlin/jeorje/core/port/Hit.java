package org.github.lscoughlin.jeorje.core.port;

import org.github.lscoughlin.jeorje.core.model.Chunk;

/** A search result: a chunk, its score, and a snippet. */
public record Hit(Chunk chunk, float score, String snippet) {}
