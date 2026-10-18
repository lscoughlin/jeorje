package org.github.lscoughlin.jeorje.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A part of a message. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Text.class, name = "text"),
    @JsonSubTypes.Type(value = ToolUse.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResult.class, name = "tool_result")
})
public sealed interface Content permits Text, ToolUse, ToolResult {}
