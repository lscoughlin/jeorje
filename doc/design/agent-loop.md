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

- The API key comes from the flag `--api-key`, from an environment variable, or from `llm.apiKey` in a config file. The flag wins over the variable, and the variable wins over the file. The `llm.apiKeyEnv` key names the variable. The default is `OPENROUTER_API_KEY`. The key can also be a list of names; Jeorje then uses the first variable that has a value that is not blank. The endpoint follows the same order: `--base-url`, then `JEORJE_BASE_URL`, then `llm.baseUrl`. The config loader makes this choice, so `llm.apiKey` in the loaded config is the key that Jeorje uses. See [configuration.md](configuration.md#precedence).
- The model id is an OpenRouter slug. The default is `~deepseek/deepseek-flash-latest`, an OpenRouter alias that always points to the latest DeepSeek Flash model. The model id is configuration. The loop never contains a model id.
- Each model role has its own `LlmClient` instance: the default model, the compactor, and the advisor. The user sets them with `/model`, `/compactor`, and `/advisor`. See [context-and-compaction.md](context-and-compaction.md#models).
- The `llm.headers` key is a map of extra HTTP headers, for example the OpenRouter headers `HTTP-Referer` and `X-Title`. The `llm.baseUrl` key sets the base URL.
- The adapter maps the internal messages to the wire format:
  - The `system` text becomes the first message with `role: "system"`.
  - `Text` becomes `content`. The text parts of one message are joined.
  - `ToolUse` becomes an assistant `tool_calls` entry. It has a `function` name and `arguments` as a JSON string. An assistant message with no text has `content: null`.
  - `ToolResult` becomes a message with `role: "tool"` and a `tool_call_id`. In a user message, the tool results come before the text.
  - `maxOutputTokens` becomes `max_tokens`. The request sets `stream_options.include_usage`.
  - For a model whose name starts with `anthropic`, the request adds `cache_control` markers to the system prompt and to the last message of the stable start. See [Prompt caching](context-and-compaction.md#prompt-caching).
- The adapter reads the server-sent events. It assembles the streamed `delta.content` and `delta.tool_calls` fragments into `LlmEvent` objects. It keeps the fragments of a tool call until it knows the id and the name. The `usage` chunk comes after the finish reason, so the adapter sends `MessageStop` last. The `usage` chunk gives the prompt tokens, the output tokens, and the tokens that the provider read from and wrote to its prompt cache. If the stream ends before a finish reason or the `[DONE]` marker, the adapter reports an error. The client does not retry an error object in the stream.
- The adapter keeps every fragment of a tool call. It finds the call from the id, then from the index, then it uses the last call. A new id on an index that another id used starts a new call. The `arguments` field can be a string or an object. A call that never got an id or a name is sent as a call named `unknown` with an id that starts with `call-lost-`, so the agent can report it.
- The `llm.extraBody` key adds fields to the top level of each request body, for example the OpenRouter `provider` object. The fields that the adapter sets itself (`model`, `messages`, `tools`, `stream`, `max_tokens`, and `stream_options`) cannot be set. The log shows the effective fields once. For the host `openrouter.ai`, when the config has no `provider` field, the adapter adds `provider.require_parameters: true`, so that OpenRouter uses only providers that support all parameters of the request, such as tools.
- The adapter has no provider-specific code except the headers and this default. The loop has none.
- The adapter retries a call by itself. It retries only before it sends the first event, on a network error, HTTP 429, and HTTP 5xx. It uses the same defaults as the agent loop: 5 attempts, a 1 s base delay, a 30 s cap, and jitter. A `Retry-After` header (seconds or a date) replaces the computed delay. It reports other HTTP errors at once as an `LlmException`. The exception has the HTTP status and the text of the response body (the first 2,000 characters).

## The loop

```
loop:
  add the messages of the step boundary hook
  context = ContextBuilder.build(pinned items, pruned chunks, recent turns, current turn, system prompt, trailing state)
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
- **Broken tool calls.** The agent checks the raw `arguments` text of each call. A call is broken when the text is not one valid JSON object (a cut, a trailing text, or a value that is not an object). A blank text is not broken: it counts as `{}`, and the schema check reports the missing fields. A broken call never runs. Its result is an error: `The arguments of your call to <tool> were not valid JSON (<n> characters were received). <problem> Send the call again.` If the model stopped with the finish reason `length`, the problem text says that the output limit of the model cut off the call. The log has the first and the last 200 characters of the raw text. The agent also checks the input of every call against the schema of the tool (see [tools.md](tools.md#interface)). After two steps in a row with a broken call, the agent publishes the `Error` event `The model sent a tool call with broken arguments 2 times in a row. The provider may be at fault. Try another model or provider.`
- The agent loop applies the state updates of the tool results (see [tools.md](tools.md#read)) in order, after the calls end.
- A step boundary hook can add texts to the context. The agent calls it before each model call, which is also after each tool call. A text joins the last user message, so a tool result always follows its tool use. The hook never runs inside a tool. Peer messages and questions use this hook.
- A turn ends in one of four cases: the model returns a message with no tool use, the user interrupts, the step limit occurs, or a model call fails. The default step limit is 200 model calls for each turn, from the key `agents.stepLimit`. A turn that reaches the limit publishes an `Error` event.
- The step limit counts the model calls since the last text of the user. A text that the user steers into the running turn starts a new count, so the turn continues with a full budget. The step limit protects against a model that loops with no input from the user. The result of `runTurn` is the number of model calls of the whole turn, so it can be above the limit.
- While a turn runs, the agent gives each final message to the transcript (`TranscriptSink.writeMessage`): the user message, each assistant message, and each result message, at the next model call. A spilled result goes in full. The rows have a sequence number, and the reader starts a turn again at sequence 0, so the rows of a turn that stopped in a crash are dropped. A reader sees only turns that have a `turn_end` row. A system note after an empty reply is its own message when the earlier message is on disk already.
- When a turn ends, the agent writes it to the `ContextStore` and to the transcript. The transcript gets only the rows that are missing, and then the `turn_end` row. The agent then starts compaction. See [context-and-compaction.md](context-and-compaction.md).

## Events to the UI

The agent publishes these `AgentEvent` types: `TokenDelta`, `ToolStarted`, `ToolFinished`, `ConfirmationRequested`, `TurnFinished`, `Compacted`, `ContextTrimmed`, `ContextUsage`, `Working`, `Steered`, `Cleared`, and `Error`. The subagent runtime also publishes `HelpRequested` (see [Turn watch for subagents](#turn-watch-for-subagents)). The `Working` event has an active flag and the token counts up and down of the model call. The UI shows it in the activity row (see [ui.md](ui.md#activity-row)). The UI never reads the state of an agent directly.

## Turn watch for subagents

The subagent runtime watches each subagent. The watch counts the model calls of the subagent since its last progress. The turn limit of the subagent is the limit for this count.

A subagent makes progress when its todo list changes. These changes are progress:

- A task becomes done.
- A task is added.
- A task is removed.
- A task gets a new text or a new state.

These events are not progress: repeated tool calls, new tool results, and new assistant text.

The watch works in this way:

1. The watch reads the todo list before each model call. The first list is the base. It is not a change.
2. When the list is different from the last list, the count goes back to 0. The subagent also gets a new budget of model calls.
3. When the count reaches 80 percent of the turn limit, the subagent asks the advisor for help. The subagent asks once for each window. A window ends when the count goes back to 0.
4. The help request names the goal of the subagent, its open todos, the count of turns, and the turn limit. The runtime publishes the `HelpRequested` event when it sends the request.
5. The advisor answers with advice. The runtime sends the advice to the subagent as a message at its next step boundary. The advisor can also write a new todo list in its answer. Each line of the list has the form `- [ ] text`, `- [~] text`, or `- [x] text`. The new list replaces the todo list of the subagent.
6. When the advisor changes the todo list, the count of the subagent goes back to 0. The step limit of the agent and the model call limit of the subagent start again from 0. The subagent can continue.

A subagent with no progress continues to count after the help request. At 100 percent of the turn limit, the turn ends with the step limit error, as before.

The watch is on only when the runtime has an advisor link. The advisor link uses the same advisor path as the `advisor` tool and the same todo store as the `todo` tool. When no advisor model is set, the help request gets no advice, and the count continues.

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
- The builder reserves the room for the output first. The **hard headroom** is the context window of the model less `maxOutputTokens`, and at least 1. The **soft working budget** is `context.budgetPercent` (default 60) of the headroom, and never more than the headroom. The builder aims for the soft budget. A request is over budget only when it is larger than the hard headroom. See [Budget](context-and-compaction.md#budget).
- `maxOutputTokens` is configuration (`llm.maxOutputTokens`).
- The builder puts the pinned items and the pruned chunks in front of the first user message. It puts the trailing state (the todo list and the decisions of the user) in a last user message, so that the start of the request stays the same for the prompt cache. When the request is over the budget, the builder drops whole recent turns from the request, the oldest first. It drops turns until the request uses 90% of the budget, so that the next calls drop the same turns. It never drops the current turn. The `Built` result reports the number of dropped turns, their numbers, and whether the request is still over the budget. A drop is the last step. The agent makes room before it, as the next bullets say.
- **Spill of the running turn.** Compaction runs only when a turn ends, but one turn can read many files. Before each model call, when the request is over the soft limit (`context.compaction.softPercent` of the budget) and the turn has at least 8 tool results, the agent moves the biggest old tool results of the turn out of the messages. The full text goes to the index as chunks with part numbers from 1000, and the message keeps a stub with the first lines and a `recall:` hint. The agent spills until the request is under 40% of the budget. The last 6 tool results, the results of `todo`, `skill`, `ask_user`, `plan_submit`, and `recall`, and results with an error stay whole. The transcript keeps the full text. The agent publishes a `Compacted` event. See [Spilled results](context-and-compaction.md#spilled-results).
- **Forced spill of the current turn.** The minimum of 8 results leaves a turn with a few huge results whole, so that turn alone can put the request far over the soft budget, and the builder never drops the current turn. After the pruning and the turn drops, when the request is still over the soft budget and the current turn is at least as large as the excess, the agent spills again with the forced flag. The forced flag lifts the minimum count of results, so that a turn with a few huge results can shrink. A result under 800 characters still stays whole, because its stub would save almost nothing. The other rules stay the same: the live tail, the text of the user and of the assistant, the error results, the stubs, and the keep-whole tools stay whole. The agent publishes `Compacted` with the number of results and the tokens that the forced spill freed, then it builds the request again.
- **Prune before drop.** When the request is still over the budget and a recent turn would leave it, the agent moves the oldest recent turn to the older entries and prunes it at the hard level. It repeats this for one turn at a time. A turn leaves the request only when pruning does not make enough room. The request then has a line in the earlier context that names the turn and points to `recall`, the agent logs a warning, and it publishes the event `ContextTrimmed` with the numbers of the dropped turns, the number of pruned chunks, and whether the request is still over the budget. A request that stays over the budget with nothing left to drop gives one such event for each turn.
- **Overflow that stays.** A forced spill and a turn drop are best effort. They never remove the live tail, the active tool result, or the text of the user. When the request is still larger than the hard headroom after them, the next model call can fail. The command `/context` then shows a warning that names the current turn as the cause.
- **Recent window by tokens.** The recent turns are at most `context.recentTurns` turns and use at most `context.recentMaxPercent` of the budget (default 40). The last finished turn is always recent. A turn that does not fit moves to the older entries when the next turn ends, and compaction prunes it.
- The agent publishes the event `ContextUsage` before each model call: the estimated tokens of the request and the budget. The status line shows the share. The command `/context` shows the tokens of each part and the prompt cache numbers of the provider. See [ui.md](ui.md).
- An **empty reply** of the model (no tool call and no text, or only white space) does not end the turn. The agent adds a system note, `Your last reply was empty. Continue with the task. If you are done, say so.`, and calls the model again. After two notes in a row, a third empty reply ends the turn with the error `The model gave an empty reply 3 times. The turn ends.` A system note is a text of the user role that starts with `[Jeorje note] `. It is not a word of the user: the chunker writes it as a `NOTE` line, not as a `USER:` text.
- A **repeat guard** can answer a repeated call. See [tools.md](tools.md#repeat-guard).
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
