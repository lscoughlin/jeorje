package org.github.lscoughlin.jeorje.core.port;

/** A search request for the history. */
public record HistoryQuery(String text, Syntax syntax, Filters filters, int limit) {}
