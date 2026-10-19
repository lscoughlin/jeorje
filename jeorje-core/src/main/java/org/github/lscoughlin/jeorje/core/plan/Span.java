package org.github.lscoughlin.jeorje.core.plan;

/** A range of 0-based lines in a source file. The start is inclusive. The end is exclusive. */
public record Span(int start, int end) {}
