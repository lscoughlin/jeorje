---
title: Context and Compaction
description: How Jeorje chunks turns, prunes the working context at token level, adds search keywords to pruned text, and retrieves earlier context with the recall tool.
---

# Context and Compaction

The model sees only the **working context**. The **history** is complete. The index stores it. See [context-index-design.md](context-index-design.md).

Compaction removes tokens from the working context. It keeps original words and writes no free-form summaries. One exception exists: decision notes (see [Protection classes](#protection-classes)). The history keeps the original text. Each pruned chunk keeps a **keyword hint**. The hint tells the model which search terms bring the full text back with `recall`.

## Turn chunking

When a turn ends, `TurnChunker` converts it to one or more chunks.

- A turn gives one or more chunks. The chunk format decides which. See [Chunk formats](#chunk-formats).
- The text of a turn chunk has one line for each part: `USER: <text>`, `ASSISTANT: <text>`, `TOOL_CALL <tool> <arguments>`, and `TOOL_RESULT <tool> <output>`.
- A tool result of more than 8,000 characters becomes separate chunks of the kind `tool_result`. `TurnChunker` splits at paragraph or line boundaries and uses a 10% overlap. A large file read then does not dominate one embedding.
- A `recall` call becomes a reference chunk. See [Recall](#recall).
- Each chunk has these values: `chunkId`, `sessionId`, `agentId`, `turn`, `part`, `timestamp`, `kind`, `toolName` (when it applies), the text, and `sourceAgentId` (when it applies). The chunk id is `<agentId>:<turn>.<part>`.
- The `kind` value is `user`, `assistant`, `tool_call`, `tool_result`, `subagent_summary`, or `mixed`. A chunk that has parts of more than one kind has the kind `mixed`. The `toolName` is set when the chunk has exactly one tool. A `subagent_summary` chunk has the parent in `agentId` and the subagent in `sourceAgentId`.

The agent adds the chunks to the index one time, when the turn ends. Adding the same chunk id again replaces the chunk, so an append is idempotent. The history grows only by append.

### Chunk formats

The manifest of a session stores the **chunk format** in the field `chunkFormat`. A new session uses format 2. A manifest with no `chunkFormat` value belongs to an older session and has format 1. A resumed session keeps its format, so the chunk ids and the pruning table of the session still match. The command `jeorje index rebuild` also uses the format of the manifest. A log line at level info tells when a session resumes with format 1.

**Format 2.** `TurnChunker` writes the parts of a turn in this order, and numbers them from 0:

1. One chunk of the kind `user` for each user text. The text is `USER: <text>`.
2. One chunk of the kind `user` with the tool name `ask_user` for each `ask_user` call. The text has the lines `QUESTION <question>`, `CHOICES <choice> | <choice>` (when the call has choices), and `ANSWER <answer>`. A call with no result has `ANSWER (none)`. A failed call has `ERROR <message>` and not `ANSWER`. These parts are not in the main text.
3. The main text: the lines of the assistant text, the tool calls, and the tool results. If the main text is longer than 8,000 characters, `TurnChunker` splits it into parts of at most 8,000 characters. It splits between lines and never inside a line. It cuts a single assistant line that is longer than 8,000 characters with the overlap rule of tool results. A part has the kind `mixed` when it holds more than one kind of line.
4. The `recall` reference chunks.
5. The chunks of the large tool results.

A user chunk is short, so the index finds it easily and the embedding covers all of its text.

**Format 1.** One chunk holds all parts of the turn, except the `recall` references and the large tool results. The chunk has the part number 0 and the kind `mixed` when the turn has more than one kind of line. The user text and the answers of `ask_user` are lines in this chunk. See [Protection classes](#protection-classes) for how pruning keeps them.

## Working context layout

```
[ system prompt ]
[ pinned items ]
[ earlier context: the chunks of older turns, oldest first, pruned chunks with a keyword hint ]
[ recent turns, verbatim ]
[ messages of the current turn ]
```

- **Pinned items** are texts that the user pins with `/pin`, followed by the latest todo list. The agent reads the text of the latest successful `todo` result from the current turn or from the finished turns, and pins it in every request. Pruning never removes the list. An empty list is not pinned. The request puts the pinned items and the earlier context in front of the first user message, under the headings `[Pinned items]` and `[Earlier context]`.
- **Recent turns** stay verbatim. These are the last N turns (default 6, `context.recentTurns`).
- **Protected content** is never pruned. See [Protection classes](#protection-classes).
- **Pruned chunks** are the chunks of older turns. A chunk that compaction has not touched shows its original text. A pruned chunk has fewer tokens than the original and ends with a keyword hint.
- When a request is over the budget, the builder drops the oldest recent turns. See [agent-loop.md](agent-loop.md#token-budget).

## Protection classes

Jeorje puts each chunk in one of three classes. The class decides how much compaction can change the chunk. The compactor model cannot move a chunk to a less strict class.

### Never compress (`PROTECTED`)

| Content | Rule |
|---------|------|
| System prompt | Never in the chunk list. The agent builds it again for each request. |
| Active user instructions and answers | A chunk of the kind `user`. This covers every user text and every `ask_user` question with its answer in format 2. In format 1, a `mixed` chunk is `PROTECTED` when it is the first chunk of its agent in the list that the classifier gets and its text starts with `USER:`. A chunk that the caller marks as pinned. |
| Todo list | A chunk whose `toolName` is `todo`. The agent also pins the latest list in every request (see [Working context layout](#working-context-layout)). |
| Current error state | The latest failing `tool_result` chunk of each tool that no later success of the same tool has resolved. A failure is a result that matches a failure pattern, for example `BUILD FAILURE`, `[ERROR]`, `FAILED`, a non-zero `exit code`, or `Tests run:` with failures. A later chunk of the same tool with no failure, or a note that the caller marks as resolved, ends the protection. The chunk then becomes `AGGRESSIVE`. |
| Skill content | A `tool_result` chunk of the `skill` tool, because it is durable guidance. |

**Words of the user in a turn chunk (format 1).** In format 1, the user text of a later turn and the answer of `ask_user` are lines in a `mixed` chunk that is `CAREFUL`. `Spans.human` finds these **human ranges**: each `USER:` part and each `TOOL_RESULT ask_user` part. The rules for a chunk that has human ranges are:

- The chunk stops at level 2. It never goes to level 3.
- The chunk never becomes a decision note. The compactor gives kept spans for it, also at level 2.
- Every answer of the compactor and of the fallback pruner includes the human ranges. If an answer leaves them out, Jeorje adds them before it stores the result. The size check of the target applies to the answer of the compactor before Jeorje adds the ranges, so the kept share of the chunk can be higher than the target.
- The ranges stay on resume, because the pruning table stores the kept spans.

The words of the user are therefore never removed in any format. The assistant text, the tool calls, and the other tool results of the chunk follow the normal rules.

**Immediate file paths.** `ContextClassifier.immediatePaths` finds the paths of files that the agent read, wrote, or edited in the last N turns (default 6), and the paths in a protected error chunk. The compactor and the pruner keep these path tokens at every level, also in chunks of other classes. The agent computes the set before each compaction and gives it to the compactor. A path that only a pruned older turn used is not in the set.

**Skill content.** The protection of skill content has a limit: when the protected skill content is more than 20,000 tokens, the skill that the agent used least recently becomes `AGGRESSIVE`. Its keyword hint has `skill:<name>`, so the agent can load it again with the `skill` tool. See [instructions-and-skills.md](instructions-and-skills.md#context-and-compaction).

### Compress carefully (`CAREFUL`)

| Content | Rule |
|---------|------|
| Intermediate reasoning | Plans, hypotheses, and explanations in assistant text (chunks of the kind `assistant`) |
| Architectural decisions | Choices of design, the reasons for them, and the rejected options |
| Subagent summaries | Chunks of the kind `subagent_summary` |
| Mixed turns | A `mixed` chunk that is not `PROTECTED` |

The compactor condenses a `CAREFUL` chunk into a **decision note**: structured bullets, not raw text. A note has these bullet types: `decision`, `reason`, `rejected`, `open question`, and `files`. Each bullet is short and uses words from the chunk. The note ends with the keyword hint.

Decision notes are the only text that the compactor writes. Jeorje checks each note: the note must not be empty, each bullet has at most 240 characters, every keyword and every file path in it must appear in the original chunk, and the note must be shorter than the original. A `CAREFUL` chunk has at most level 2. It never goes to level 3, because a decision has no other copy.

### Aggressively compress or drop (`AGGRESSIVE`)

| Content | Rule |
|---------|------|
| Historical tool outputs | Chunks of the kind `tool_result` or `tool_call`, for example large results of `read`, `grep`, `glob`, `ls`, `bash`, and `webfetch`, that are older than the recent turns |
| Raw build and test logs | Long output of build and test commands |
| Recall references | Chunks whose `toolName` is `recall` |

A search result is a `tool_result` chunk of `grep`, `glob`, `ls`, `find`, or `search`. A search that led to a conclusion is a **concluded search**. `ContextClassifier.concludedSearches` finds it: a later edit or write call, or later assistant text, contains a path, a file name, or an identifier of the result. Compaction prunes a concluded search first, and it goes to level 3 at the first compaction that touches it. Pinned and protected chunks are never concluded searches.

An `AGGRESSIVE` chunk goes to level 2 at the first compaction that touches it. It goes to level 3 when it is older than the recent turns by a second margin (default 12 turns, `context.compaction.aggressiveDropAfterTurns`) or when the hard trigger raises it again. Its keyword hint is the way to get it back.

### Classification

`ContextClassifier` assigns the class with fixed rules on `kind`, `toolName`, and the text of the chunk. It does not use the compactor model. The rules run in this order: a pinned chunk, a `todo` chunk, an `ask_user` chunk that is not `mixed` (`PROTECTED`), a `recall` chunk (`AGGRESSIVE`), a `skill` result (`PROTECTED`), and then the kind of the chunk (`user` is `PROTECTED`, `assistant` and `subagent_summary` are `CAREFUL`, `tool_result` and `tool_call` are `AGGRESSIVE`, and `mixed` follows the table above). A chunk of an unknown kind is `CAREFUL` if its text starts with `ASSISTANT:` and `AGGRESSIVE` otherwise. The error state and the skill limit then change some classes.

The agent classifies the chunks of one turn at a time, when the turn leaves the recent window. It does not classify them again at later compactions. The agent gives no pinned chunk ids and no resolved chunk ids to the classifier.

## Token-level pruning

Token-level pruning keeps the original words in the original order. It removes tokens that carry little information. It never rewrites text.

### Levels

Each chunk has a pruning level. The level only increases. `PROTECTED` chunks stay at level 0. `CAREFUL` chunks stop at level 2. The targets are the config value `context.compaction.levels` (default 1.0, 0.5, 0.2, 0.0).

| Level | Target to keep | Content that stays |
|-------|----------------|--------------------|
| 0 | 100% | The full original text |
| 1 | 50% | Identifiers, paths, numbers, error messages, and key phrases. The pruner removes filler words, repeated lines, and blank lines. |
| 2 | 20% | Only the most informative tokens: names, paths, error text, and decisions. For a `CAREFUL` chunk, this level is the decision note. |
| 3 | 0% | No text. The header, a short topics line, and the keyword hint stay. |

The pruner measures the target in characters. It estimates tokens as one token for four characters (rounded up).

### Pruner

The **compactor model** does the pruning. See [Models](#models). The instructions that Jeorje sends to the compactor come from a built-in prompt. The config can append to it or replace it with `prompts.compaction`. See [configuration.md](configuration.md#prompts). Jeorje always adds the output contract (spans, keywords, and decision notes), and the checks below always apply. For each chunk, Jeorje sends the text and the target level to the compactor. The compactor returns:

- the list of token spans to keep,
- the keyword list for the hint (see [Keyword hints](#keyword-hints)).

A span is a pair of character offsets in the chunk text. The start is included and the end is not. Jeorje builds the pruned text from the original text and the kept spans. It joins two spans with the original white space (one space or one line break) when only white space lies between them. Otherwise it joins them with ` ... `. For `AGGRESSIVE` chunks, the compactor never writes new words. This rule has two results: the pruned text cannot contain an invented fact, and Jeorje can check it. Only decision notes for `CAREFUL` chunks contain new wording.

One compactor call carries a batch of chunks. Jeorje groups the chunks by level and class. A batch has up to 20 chunks and 16,000 estimated tokens. The call has a timeout of 120 s. The answer is one JSON object with an entry for each chunk id.

Jeorje checks each answer:

1. Each span must be inside the chunk text, must not be empty, and must not overlap another span. The answer must have at least one span.
2. The kept characters must be at least 50% and at most 150% of the level target. Jeorje skips this check for a chunk of less than 40 characters.
3. Each keyword must appear in the original chunk text.
4. For a decision note, see [Compress carefully](#compress-carefully-careful).

Jeorje asks again one time for the chunks that fail the check, and it gives the reason to the compactor. A call that fails or returns text that is not valid JSON counts as a failed try. If the check fails after the second try, Jeorje uses the **fallback pruner**. It needs no model. It removes tokens in this order until it reaches the target: repeated lines, English stop words, and then lines with the lowest score. The stop-word step never removes a negation or restriction word (`no`, `not`, `never`, `nor`, `none`, `cannot`, `without`, `except`, `only`) or a word that ends in `n't`, also when punctuation is next to it. A line score is the sum of the weights of its terms. The weight comes from a `TermStats` object. The CLI passes uniform weights, so every term has the weight 1. It always keeps tokens that contain a path, an error word (`error`, `exception`, `failed`), or a digit. The fallback also produces keywords with the same candidate score. This pruner also runs when the compactor model is not set or not available. A `CAREFUL` chunk at level 2 that has no valid decision note gets the spans of the fallback pruner, not a note.

Jeorje prunes `AGGRESSIVE` chunks first. They are the largest and the easiest to recover.

### Pruned chunk format

A pruned chunk keeps its header and ends with the hint:

```
[main:17.2 tool_result read | pruned L2 (22% kept)]
class ConfigLoader ... load(Path) ... throws IOException ... line 412 NullPointerException ...
[recall: ConfigLoader, NullPointerException, Config.java, "line 412", id=main:17.2]
```

The header has the chunk id, the kind, the tool name (when the chunk has one), the level, and the share of the characters that stay. The `recall:` line is the keyword hint. It has the search terms and the chunk id. The model can run `recall` with these terms or with the id.

## Keyword hints

A keyword hint helps the model find the full text again. The compactor chooses the keywords so that a text search finds the chunk.

- A hint has 3 to 12 keywords (`context.compaction.maxKeywords`, at most 12).
- The keywords are exact strings from the original text. The candidates are identifiers, file paths, error names, numbers, and rare words. Jeorje ranks them by a score and keeps the best 40. It does not pick stop words or terms of less than 3 characters.
- The keywords are distinctive in the session. Jeorje removes a keyword that matches more than 20 other chunks in the index. Chunks of `recall` calls do not count. It then replaces the keyword with a better term.
- Jeorje can find the chunk with the hint. After pruning, Jeorje runs the hint as a TEXT query. It checks that the chunk is in the top 3 results. If not, Jeorje adds the next best keyword and checks again, up to the maximum.
- A keyword hint stays when a chunk goes to a higher level. The keywords of the earlier level come first. The compactor can add keywords at each level.
- A chunk of the `skill` tool also gets the keyword `skill:<name>`.
- At level 3, the header, the topics line, and the hint stay. The topics line has the tool name, the file name of the first path in the text, and the first error name, for example: `read Config.java; found NullPointerException`.

## Compaction policy

Compaction runs after each turn. It does not wait for a hard limit. The budget is the share of the context window for the working context (60% by default, `context.budgetPercent`). The triggers measure the estimated tokens of the earlier context only: the chunks of the turns outside the recent window, in their current form. They do not count the system prompt, the tool definitions, the pinned items, or the recent turns. A large system prompt or large recent turns therefore never starts a compaction by themselves. The request builder keeps the whole request inside the budget: when a request is over the budget, it drops the oldest recent turns. See [agent-loop.md](agent-loop.md#token-budget).

An **eligible** chunk is not `PROTECTED`, is older than the recent turns, and is below the highest level of its class.

1. **Soft trigger.** If the earlier context is more than 50% of the budget (`softPercent`), raise all eligible chunks. Raise each `CAREFUL` chunk by one level. Raise each `AGGRESSIVE` chunk to level 2, or to level 3 when it is old enough or when it is a concluded search (see [Aggressively compress or drop](#aggressively-compress-or-drop-aggressive)). Work in the eviction order.
2. **Hard trigger.** If the earlier context is more than 80% of the budget (`hardPercent`), raise more chunks. Raise each eligible chunk by one level, again and again, until the context is under 50% of the budget or no chunk can rise more.
3. **Eviction order.** Prune concluded searches first. Then prune large tool results (2,000 tokens or more, the largest first), then raw logs (`bash` results and text with `BUILD ` or `Tests run:`), then other output. Then prune `CAREFUL` chunks. Chunks of the same rank go in the order of the history. Never prune `PROTECTED` chunks or recent turns.
4. **Batches.** Jeorje sends the chunks to the compactor in batches. This keeps the cost low. A batch has up to 20 chunks or 16,000 tokens.
5. **No loss.** Compaction never deletes history. The index has the original text of each chunk.

Compaction sends a `Compacted` event. The UI shows the number of chunks that Jeorje pruned and the tokens it saved. The `/compact` command runs the hard trigger between turns.

## Models

Jeorje uses three model settings. The user can set each one in the config or with a slash command. See [ui.md](ui.md) and [configuration.md](configuration.md).

| Role | Command | Use |
|------|---------|-----|
| Default model | `/model` | The model for the agent loop. Subagents use it unless their `model` field names another model. |
| Compactor | `/compactor` | The model that prunes chunks and chooses keywords. A fast, low-cost model is a good choice. |
| Advisor | `/advisor` | The model that the `advisor` tool calls. See [tools.md](tools.md). |

If no compactor is set, compaction uses the fallback pruner. If no advisor is set, Jeorje does not register the `advisor` tool.

## Recall

The agent has a `recall` tool. See [tools.md](tools.md). It has two modes:

- `recall(query, filters?, limit?)` runs a hybrid search over the history of the session. The default limit is 10 and the largest is 50. The result starts with a line `recall-ref: <JSON>` that has the query, the filters, and the ids of the hits. Then each hit has a line with the chunk id, the agent, the turn, the score, the kind, and the tool, and a line with a snippet.
- `recall(chunkId, before?, after?)` returns the full text of a chunk, or a window of up to 10 chunks before and after it.

To find what the user said, asked, or chose, the model calls `recall` with the filter `kind` set to `user`. The system prompt tells the model to do this. The index gives a `user` chunk a rank boost: it multiplies the score of the chunk by `index.userBoost` (default 1.5). The snippet of a `user` chunk is its text, up to 1,000 characters.

The tool gets the session id as a constructor argument. It never returns a chunk of another session.

The system prompt tells the model what a pruned chunk is. It tells the model to take the terms from the `recall:` line and use them with `recall` when it needs the missing detail. It also tells the model to use `recall` instead of a repeat tool run.

Recalled content enters the working context as a normal tool result at level 0. The index has no text for it, so the pruner cannot build kept spans from it. When compaction evicts a recall result, Jeorje replaces it with a stub that lists the ids of the source chunks (`sources: <ids>`). Each source chunk keeps its own pruned form and keyword hint.

**History stores a reference, not the text.** The index does not get a second copy of recalled text. For a `recall` call, `TurnChunker` makes a chunk of the kind `tool_call` with `toolName` `recall`. Its text is `RECALL query=<query> filters=<filters> returned=<ids>`. The ids come from the chunk ids in the output of the tool. The search skips chunks with `toolName` `recall` unless the filter `toolNames` has `recall`. The hint check and the keyword check ignore these chunks. This rule keeps duplicates out of the top results, keeps keywords from matching more than 20 chunks, and stops the index from growing with each recall. The full text of a recall result stays in `transcript.jsonl`. The UI shows full text from the transcript. See [configuration.md](configuration.md#directories).

## Self-questions

`recall` is a tool. The agent can therefore ask questions about its past work during a task. Example: "What error did the build give for module X?" The search covers the full index of the session: the chunks of all agents. A subagent searches the same index. See [Recall in a subagent](subagents.md#recall-in-a-subagent). Filters narrow the search by `agentId`, turn range, `kind`, and `toolName`. A peer can get read access to the history of an agent only through `history_query`. See [swarm.md](swarm.md).

## Persistence and resume

A session directory holds the index, the transcript, and a manifest. Jeorje removes secrets from the chunk text before it enters the index, and from every message before it enters the transcript. See [permissions-and-safety.md](permissions-and-safety.md#secrets). The manifest has the session id, the times, the agent tree, a snapshot of the config, the pinned items, the pruning table, the run state, the last turn number, the state of an `/implement` run, and the chunk format. The pruning table has the class, the level, the kept spans (character offsets) or the note, and the keywords for each pruned chunk.

To resume a session, Jeorje reads the manifest and the transcript. It chunks the turns of the agent with `TurnChunker`. It builds the working context from the pinned items, the pruned chunks, and the latest turns. The text of a pruned chunk comes from the transcript, with the kept spans applied. For a decision note, it uses the stored note. A chunk with no row in the pruning table is at level 0. The next request equals the request that the earlier run would have made. The index can be rebuilt from the transcript with `jeorje index rebuild`.
