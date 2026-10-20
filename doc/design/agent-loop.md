---
title: Agent Loop
description: The turn loop, the LLM client port, the message model, tool dispatch, retries, cancellation, and token budget.
---

# Agent Loop

## Message model

Messages are immutable records. Jackson 3 serializes them.

```java
public sealed interface Content permits Text, ToolUse, ToolResult {}
public record Text(String text) implements Content {}
public record ToolUse(String id, String tool, JsonNode input) implements Content {}
public record ToolResult(String toolUseId, String output, boolean error) implements Content {}

public record Message(Role role, List<Content> content) {}
public enum Role { USER, ASSISTANT }
```

A **turn** groups the messages between one input and the final answer of the model. It has the input, each assistant message, and each tool result. Each agent numbers its turns in increasing order. The id of a turn is `<agentId>:<number>`.

An agent runs in one virtual thread. The thread reads an input queue (`AgentInput`). The user, peers, and subagents add inputs to the queue. Each input starts one turn. A turn runs under a lock, so two turns of one agent never run at the same time.

## LLM client port

```java
public interface LlmClient {
    Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel);
}

public record LlmRequest(String system, List<Message> messages,
                         List<ToolDefinition> tools, int maxOutputTokens) {}

public sealed interface LlmEvent permits TextDelta, ToolUseStart, ToolUseInput, MessageStop, Usage {}
// TextDelta(text), ToolUseStart(id, tool), ToolUseInput(id, jsonFragment),
// MessageStop(stopReason), Usage(inputTokens, outputTokens)
```

The publisher sends the events in order and ends with `MessageStop`. It reports an error with `onError`. When the cancellation token is cancelled, the client closes the stream and completes with no more events.

### OpenAI-compatible client

`OpenAiCompatibleClient` is in `jeorje-llm`. It uses the OpenAI Chat Completions API: `POST {baseUrl}/chat/completions` with `stream: true` and `tools`. It uses `java.net.http.HttpClient`.

The first target is OpenRouter. Its `baseUrl` is `https://openrouter.ai/api/v1`. Any OpenAI-compatible endpoint works if you change `baseUrl`.

- The API key comes from an environment variable. The `llm.apiKeyEnv` key names the variable. The default is `OPENROUTER_API_KEY`. The key can also be a list of names; Jeorje then uses the first variable that has a value that is not blank.
- The model id is an OpenRouter slug. The default is `~deepseek/deepseek-flash-latest`, an OpenRouter alias that always points to the latest DeepSeek Flash model. The model id is configuration. The loop never contains a model id.
- Each model role has its own `LlmClient` instance: the default model, the compactor, and the advisor. The user sets them with `/model`, `/compactor`, and `/advisor`. See [context-and-compaction.md](context-and-compaction.md#models).
- The `llm.headers` key is a map of extra HTTP headers, for example the OpenRouter headers `HTTP-Referer` and `X-Title`. The `llm.baseUrl` key sets the base URL.
- The adapter maps the internal messages to the wire format:
  - The `system` text becomes the first message with `role: "system"`.
  - `Text` becomes `content`. The text parts of one message are joined.
  - `ToolUse` becomes an assistant `tool_calls` entry. It has a `function` name and `arguments` as a JSON string. An assistant message with no text has `content: null`.
  - `ToolResult` becomes a message with `role: "tool"` and a `tool_call_id`. In a user message, the tool results come before the text.
  - `maxOutputTokens` becomes `max_tokens`. The request sets `stream_options.include_usage`.
- The adapter reads the server-sent events. It assembles the streamed `delta.content` and `delta.tool_calls` fragments into `LlmEvent` objects. It keeps the fragments of a tool call until it knows the id and the name. The `usage` chunk comes after the finish reason, so the adapter sends `MessageStop` last. If the stream ends before a finish reason or the `[DONE]` marker, the adapter reports an error. The client does not retry an error object in the stream.
- The adapter has no provider-specific code except the headers. The loop has none.
- The adapter retries a call by itself. It retries only before it sends the first event, on a network error, HTTP 429, and HTTP 5xx. It uses the same defaults as the agent loop: 5 attempts, a 1 s base delay, a 30 s cap, and jitter. A `Retry-After` header (seconds or a date) replaces the computed delay. It reports other HTTP errors at once as an `LlmException`. The exception has the HTTP status and the text of the response body (the first 2,000 characters).

## The loop

```
loop:
  add the messages of the step boundary hook
  context = ContextBuilder.build(pinned items, pruned chunks, recent turns, current turn, system prompt)
  events  = llm.stream(context)
  message = assemble(events)
  append(message)
  if message has no ToolUse: finish turn, break
  for each ToolUse (in order):
      decision = permissions.check(toolUse)
      result   = switch (decision) {
          case ALLOW -> tool.execute(toolUse)
          case ASK   -> confirm(toolUse) ? tool.execute(toolUse) : denied()
          case DENY  -> denied()
      }
  append(ToolResults)
  continue loop
```

- The agent checks the permission of every call in the order of the message. When all calls are `readOnly` and the message has more than one call, the agent then runs the allowed calls at the same time. Otherwise, it checks and runs the calls one after the other.
- For `ASK`, the agent publishes a `ConfirmationRequested` event and waits. The UI answers with `confirm(toolUseId, approved)`. A rejected call gives a `ToolResult` with `error = true` and the text `Permission denied: the call to <tool> did not run.`. A `DENY` gives the same text. A tool name that the agent does not know gives an error result.
- The agent loop applies the state updates of the tool results (see [tools.md](tools.md#read)) in order, after the calls end.
- A step boundary hook can add texts to the context. The agent calls it before each model call, which is also after each tool call. A text joins the last user message, so a tool result always follows its tool use. The hook never runs inside a tool. Peer messages and questions use this hook.
- A turn ends in one of four cases: the model returns a message with no tool use, the user interrupts, the step limit occurs, or a model call fails. The default step limit is 200 model calls for each turn, from the key `agents.stepLimit`. A turn that reaches the limit publishes an `Error` event.
- The step limit counts the model calls since the last text of the user. A text that the user steers into the running turn starts a new count, so the turn continues with a full budget. The step limit protects against a model that loops with no input from the user. The result of `runTurn` is the number of model calls of the whole turn, so it can be above the limit.
- When a turn ends, the agent writes it to the `ContextStore` and to the transcript. The agent then starts compaction. See [context-and-compaction.md](context-and-compaction.md).

## Events to the UI

The agent publishes these `AgentEvent` types: `TokenDelta`, `ToolStarted`, `ToolFinished`, `ConfirmationRequested`, `TurnFinished`, `Compacted`, and `Error`. The UI never reads the state of an agent directly.

## Retries and errors

- The agent retries a failed model call only before the first event of the response arrives. After the first event, a failure ends the turn.
- The agent reads the retry information from the error by reflection. It looks at the error and its causes. A `status()` method that returns an `int` gives the HTTP status. A status of 0 (network error), 429, or 500 and above can be retried. Other statuses cannot. An `IOException` with no `status()` method can be retried. A `retryAfter()` method that returns a `Duration` replaces the computed delay.
- The delay uses exponential backoff with jitter: half of the capped delay plus a random value of up to the other half. The defaults are 5 attempts, a 1 s base delay, and a 30 s cap.
- The client in `jeorje-llm` retries by itself first (see above). `LlmException` has a `status()` method but no `retryAfter()` method. A retryable error that the client gives up on can therefore start the retries of the agent loop again.
- The agent does not retry HTTP 4xx errors other than 429. It reports a failed call as an `Error` event with the text `Model call failed: <message>`, and the turn ends.
- If a tool throws an exception, the agent returns a `ToolResult` with `error = true` and the exception message. The model decides what to do next.
- The UI shows each `Error` event. The agent stays usable. An unexpected exception in a turn also becomes an `Error` event.

## Cancellation

When the user cancels a turn, the `CancellationToken` of the agent triggers:

1. The agent cancels the subscription, and the client closes the HTTP stream.
2. The agent cancels the running tools. A tool that fails after the cancellation gives the result `interrupted`.
3. The agent cancels each open confirmation.
4. The agent keeps the partial assistant message. A tool call with input that is not valid JSON gets an empty object as input.
5. The agent adds a `ToolResult` with the text `interrupted` and `error = true` for each open tool call. The history stays valid.

## Token budget

- `ContextBuilder` estimates the tokens of a request. The estimate is one token for four characters, rounded up, plus 4 tokens for each message. It counts the system prompt, the tool definitions, and all messages. It improves the estimate with the `Usage` values from the provider: the scale is the input tokens that the provider counted divided by the raw estimate. The scale stays between 0.25 and 4.
- The default working-context budget is 60% of the context window of the model. The budget is the smaller value of that share and the window less `maxOutputTokens`.
- `maxOutputTokens` is configuration (`llm.maxOutputTokens`).
- The builder puts the pinned items and the pruned chunks in front of the first user message. When the request is over the budget, the builder drops whole recent turns from the request, the oldest first. It never drops the current turn. The `Built` result reports the number of dropped turns and whether the request is still over the budget.
- Compaction runs after each turn. It prunes tokens. It does not write summaries. The triggers measure the earlier context only. They are in [context-and-compaction.md](context-and-compaction.md#compaction-policy). A turn gives one chunk for each user text, one chunk for each `ask_user` answer, and one or more chunks for the rest. See [Chunk formats](context-and-compaction.md#chunk-formats). The `/compact` command runs it at once at the hard level.

## System prompt

The agent builds the system prompt from these parts:

- the identity and behavior section. The built-in text is the default. The config can append to it or replace it with `prompts.system`. The text can come from `text` or from `file`. If the file cannot be read, Jeorje logs a warning and uses the built-in text. See [configuration.md](configuration.md#prompts),
- the tool guidance: one sentence about tool use, and the name and description of each tool,
- the workspace description (working directory, platform, date),
- the user and project instructions from `AGENTS.md` files, when there are any. See [instructions-and-skills.md](instructions-and-skills.md),
- the guidance for pruned chunks and the `recall:` keyword hints,
- the plan-mode section, only in plan mode. See [planning.md](planning.md#plan-mode).

A custom system prompt changes only the identity and behavior section. The tool guidance, the workspace description, the `AGENTS.md` instructions, and the guidance for pruned chunks always stay.
