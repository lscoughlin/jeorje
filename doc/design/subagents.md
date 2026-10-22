---
title: Subagents
description: How a subagent starts, its life cycle, how the user switches into it and steers it, how it returns results, and its limits.
---

# Subagents

A subagent is an agent that another agent starts to do one task. The subagent runtime is in `jeorje-core`. A subagent can run in the same instance (local) or in a peer instance (remote). See [swarm.md](swarm.md).

## Spawn

The `spawn_agent` tool starts a subagent. Its input is:

| Field | Meaning |
|-------|---------|
| `name` | Short label that the UI shows |
| `task` | The instruction for the subagent |
| `tools` | Optional list of tools. The default is the tool set of the parent without `spawn_agent`. The `tools` of the `type` is the default when the call has no list. The runtime drops a requested tool that the parent does not have, and `spawn_agent` reports the dropped names. |
| `type` | Optional name of a subagent definition from `.agents/agents/`. See [instructions-and-skills.md](instructions-and-skills.md#subagent-definitions). |
| `model` | Optional model slug. The default is the default model (`/model`). |
| `peer` | Optional peer (id, name, or address) that runs the task. The `capability` field is a second option: it picks any live peer with that capability, for example `tools:bash`. Both fields are on the `spawn_agent` tool only when the swarm is in use. A remote subagent uses only `name`, `task`, `tools`, and `mode`. It ignores `type`, `model`, `isolation`, and `workdir`. |
| `workdir` | Optional explicit working directory. A subagent with `workdir` gets no worktree. |
| `isolation` | Optional `worktree` or `shared`. The default is `worktree.isolation`. See [Worktrees](#worktrees). |
| `mode` | `background` (default) or `wait` |

The new subagent gets:

- its own `agentId` and its own message history,
- a system prompt that has the instructions of the session, a line that names the subagent and its parent, the body of the `type` (if any), and the task,
- a restricted tool registry: the chosen tools plus the shared tools (`agent_status`, `agent_message`, `agent_ask`, `agent_answer`, and `recall`), which the runtime adds to every subagent,
- a permission policy that is not wider than the policy of the parent. It denies every tool that is not in its registry.
- its own `CancellationToken`, linked to the token of the parent.

The working context of the subagent does not have the history of the parent. The `task` text must contain all needed information. The subagent can ask questions with `recall`. `recall` always searches the full index of the session: the chunks of all agents, not only the chunks of the subagent. See [Recall in a subagent](#recall-in-a-subagent).

A subagent has the `advisor` tool, unless the advisor is off. The advisor gets the last request of the subagent, so it never sees the context of the parent or of another agent.

A subagent writes its chunks to the session index with its own `agentId`. See [context-index-design.md](context-index-design.md).

## Life cycle

```
CREATED -> RUNNING -> WAITING_INPUT -> RUNNING -> WAITING_AGENT -> RUNNING -> ... -> SYNCING -> RESOLVING -> SYNCING -> MERGING -> COMPLETED
                  \-> FAILED
                  \-> CANCELLED
```

- `RUNNING`: the agent loop is active.
- `WAITING_INPUT`: the subagent asked the user a question with `ask_user`, or it needs a permission decision. The UI shows a mark on this subagent.
- `WAITING_AGENT`: the subagent asked another agent a question with `agent_ask` and waits for the answer. The UI shows the name of the agent that it waits for.
- `SYNCING`, `RESOLVING`, `MERGING`: the completion sequence of a subagent with a worktree. See [Completion sequence](#completion-sequence).
- `COMPLETED`: the model gave a final answer, and the completion sequence ended without a failure (or the subagent has no worktree). The state changes to `COMPLETED` after the completion summary exists.
- Any state that is not final can move to `FAILED` or `CANCELLED`.
- `FAILED`: an error stopped the loop after the retries, a limit stopped it, or the completion sequence failed.
- `CANCELLED`: the user or the parent cancelled the subagent.

## Results

When a subagent finishes, it produces a result: the final assistant text and a **completion summary** (see [Completion summary](#completion-summary)). The runtime does the following:

- In `wait` mode, `spawn_agent` blocks the parent loop and returns the result as the tool result.
- In `background` mode, `spawn_agent` returns the `agentId` at once. When the subagent finishes, the runtime adds a result message to the parent as an input event. The parent loop then continues if it is idle.

## Foreground switching

The UI has one foreground agent. The user sees its chat and types to it. All other agents run in the background.

- The user opens the agent list with `Ctrl-X a`, or runs `/agents`. The list shows each agent with its name, state, and the last output line.
- The user selects a subagent. It becomes the foreground chat. The UI shows the full history of that subagent from `transcript.jsonl` and then streams new output.
- Text that the user types goes to the foreground subagent as a user input. This is steering. The subagent handles it as the next input, or it interrupts the current step if the user presses `Ctrl-C` first. A text that reaches the running turn at a step boundary starts a new count of model calls, so the step limit of the turn gives it a new budget. See [agent-loop.md](agent-loop.md#the-loop).
- `Ctrl-X a` or `/back` returns the user to the parent.
- A background agent that needs input shows a notice in the status line. The user can switch to it.
- A remote subagent behaves in the same way. The UI sends the steering text to the peer. See [swarm.md](swarm.md).
- `/agents new [name]` starts a second session and keeps the first one in the background. Each session has its own subagent runtime. The main agent of one session can use `agent_message` and `agent_ask` with the id or the name of the main agent of the other session. The runtime of the sender passes the call to the session directory when it cannot find the target. See [ui.md](ui.md#sessions-in-the-switcher).

## Talking to other agents

Agents of one session can ask each other questions when they need to. This applies to all agents: the root agent, parents, children, and siblings (for example, the workers of `/implement`).

### Tools

| Tool | Purpose |
|------|---------|
| `agent_status` | List the agents of the session with their state, the story or task, and the branch |
| `agent_message(to, text)` | Send a message with no answer expected |
| `agent_ask(to, question)` | Ask a question and wait for the answer |
| `agent_answer(questionId, text)` | Answer a question that came in |

The `to` value is an agent name, an agent id, or a story id for the workers of `/implement`. The runtime ignores case for names. If an ended agent and a running agent have the same key, the running agent has precedence. An unknown key gives the reason `unknown agent`. An agent cannot ask itself (reason `self`).

### How a question works

1. The asker calls `agent_ask`. Its state becomes `WAITING_AGENT`.
2. The runtime puts the question in the inbox of the target agent. The target sees it at the next step boundary, which is the point between two model calls or between two tool calls. The runtime does not interrupt a running tool. The question appears as a message that starts with `[question from <agent> (<story id or agent id>), id <questionId>]`. If the target has finished its turn, the runtime starts a new turn for it with the text of its inbox. A root agent receives the question as a peer message.
3. The target answers with `agent_answer`. It can do other work first. The answer goes to the asker as the result of `agent_ask`.
4. The asker continues with its state `RUNNING`.

### Rules

- **Timeout.** If no answer comes in `subagents.askTimeoutSeconds` (default 120), `agent_ask` returns an error with the reason `timeout`. The asker continues without the answer.
- **Deadlock.** An agent cannot wait for an agent that waits for it, directly or through other agents. The runtime finds the cycle when the question is sent. `agent_ask` returns an error with the reason `deadlock`. The check counts internal waits as edges too: waits for an integration lock, merge waits, and a parent that waits in a `wait` mode `spawn_agent` for its child.
- **Busy.** An agent that is in an internal wait does not reach a step boundary and cannot answer. `agent_ask` to such an agent returns an error with the reason `busy` at once. This also covers an agent in `SYNCING`, `RESOLVING`, or `MERGING` that waits for a lock.
- **One at a time.** An agent has at most one open `agent_ask`. A second call returns an error with the reason `open ask`. An agent can have many unanswered questions in its inbox. It answers them in any order.
- **Limit.** An agent can ask at most `subagents.maxAsks` questions in one task (default 10). This keeps agents from talking when they should work.
- **Target not available.** If the target has not started (its story is `pending`), `agent_ask` returns an error with the reason `not started`. If the target has ended, `agent_ask` returns an error with the reason `ended` and the completion summary of the target, with its keywords. The asker can use the keywords with `recall`. See [Completion summary](#completion-summary).
- **To the user.** Workers cannot ask the user. A question for the user goes to the orchestrator, which can call `ask_user`. The root agent and the orchestrator can use `ask_user`. The user sees the question as a form that names the agent that asked. Forms from several agents queue in the order they arrive. A form does not move the user to the agent that asked. See [Forms](ui.md#forms). A question from a remote peer goes through `agent_ask` and not through `ask_user`. See [swarm.md](swarm.md).
- **Ended target and messages.** `agent_message` to an ended agent returns the same `ended` error. A question that is open when its target ends gets the `ended` error with the summary of the target. A question that is open when its asker ends is cancelled.
- **Content.** Questions and answers are plain text. They are tool calls and tool results in the histories of both agents, so the index stores them and `recall` finds them. See [context-and-compaction.md](context-and-compaction.md).
- **Remote agents.** `agent_ask` to a running remote subagent returns an error with the reason `remote`. `agent_message` to a remote subagent sends the text to its peer as steering. A remote subagent can ask a question: the runtime shows it in the output of the agent with a question id. See [swarm.md](swarm.md).
- **Permissions.** `agent_message`, `agent_ask`, and `agent_answer` are `ALLOW`. `agent_status` is a read-only tool and is `ALLOW`. An answer cannot approve a tool run or change a permission. See [permissions-and-safety.md](permissions-and-safety.md).

### Steering and questions

The user can switch into any subagent (see [Foreground switching](#foreground-switching)). A message that the user types is steering, not a question. The user can see the questions that agents ask each other in the chat view of each agent: they show as tool calls.

## Recall in a subagent

A subagent searches the full Lucene index of the session when it asks questions with `recall`.

- **Scope.** The search covers the chunks of all agents of the session: the root agent, the parent, the siblings, and the subagent itself. It never covers the index of another session.
- **No default filter.** The default query has no `agentId` filter. A subagent can add an `agentId` filter, a turn range, a `kind`, or a `toolName` to narrow the search.
- **Hits show the source.** Each hit has the `agentId` and the agent name of its chunk. The result of `recall(chunkId)` also gives the source agent.
- **Working context.** `recall` does not change the working context rule. At the start, the working context of a subagent has only what the runtime gives it. A recalled chunk enters the context as a tool result. The subagent gets content from other agents only if it asks for it.
- **Timing.** A chunk is searchable when its turn ends. A subagent sees the finished turns of the other agents that run at the same time.
- **Other sessions and peers.** A subagent cannot search the index of another session. A remote peer answers a `history_query` only as [swarm.md](swarm.md) describes.
- **Permissions.** `recall` is `ALLOW`. See [permissions-and-safety.md](permissions-and-safety.md).

## Completion summary

Each subagent ends with a **completion summary**. The runtime sends the summary to the parent session. It also records it in the history of the parent.

**Content.** The summary has at most 300 words, in these labeled parts:

| Part | Content |
|------|---------|
| Outcome | `completed`, `failed`, or `cancelled`, with the reason when it is not `completed` |
| Done | What the subagent did, in a few short sentences |
| Files | The files that it created, changed, or deleted |
| Branch | The branch and the worktree, when it used one (see [Worktrees](#worktrees)), the source branch, the merge result and commit, and the conflicts that it resolved |
| Decisions | Choices that it made, with the reason. This includes how it resolved conflicts from the sync. |
| Verification | The commands that it ran and the results |
| Open issues | Work that it did not finish, and problems that it found |
| Keywords | 3 to 12 exact terms from its work (identifiers, paths, error names). The parent can use them with `recall` to find the details. |

**Who writes it.**

- **Completed.** The runtime makes one model call with no tools, after the completion sequence (see [Completion sequence](#completion-sequence)). The call uses the same model as the subagent. It does not use the working context of the subagent. Its input is the facts of the run (name, outcome, reason, and the `Branch` text from the completion sequence) and the history of the subagent as text, cut to 60,000 characters. The summary must have the merge result and the conflicts that the subagent resolved, so it cannot exist before the merge back. A summary that a worker gives earlier (the `summary` of `story_done`) is a **draft**. The draft goes to the review. The final summary goes to the parent and to the history. See [planning.md](planning.md#workers).
- **Failed or cancelled.** The subagent cannot write it. The runtime makes the same model call with the compactor model and the history of the subagent. Without a compactor model, the runtime uses the fallback: the last assistant text, the list of changed files, and the failure reason.
- The runtime checks the summary. All eight parts must be present, the `Outcome` must start with the real outcome, the `Keywords` must be 3 to 12 terms that are in the history of the subagent, and the length limit must hold. If a check fails, the runtime makes the call one more time with the problems in the prompt, and then uses the fallback.

**Delivery to the parent session.**

- The summary goes to the parent as the result: the tool result in `wait` mode, or an input event in `background` mode (see [Results](#results)).
- The runtime also writes the summary to the history of the parent as a chunk with `kind` `subagent_summary`. The chunk has the `agentId` of the parent and the `sourceAgentId` of the subagent. Its text starts with `subagent: <name>`. Its part number is 1000 or more, so it never collides with the parts of a turn. The index stores it with the full text, so the parent can find it later with `recall`, also after compaction has pruned it from the working context.
- The class of a summary chunk is `CAREFUL`. It is already short, so compaction can reduce it to level 2 at most. See [context-and-compaction.md](context-and-compaction.md#protection-classes).
- The completion summary is not compaction output. Compaction rules about summaries do not apply to it.
- For a remote subagent, the peer sends the summary in the last `delegate_event`. The local instance adds the line `Remote subagent on the peer <name>.` and writes the chunk in the same way. If the peer sends no summary, the instance builds a short one from the outcome. See [swarm.md](swarm.md).

## Story workers

`/implement` starts one worker for each story of a plan. A worker is a subagent with a smaller context rule: its working context has the normal system prompt and the text of its story, and nothing else. It does not have the history of its parent in its working context. Its `recall` searches the full index of the session, like the `recall` of any subagent (see [Recall in a subagent](#recall-in-a-subagent)). See [planning.md](planning.md#context-rule).

## Cancellation

Cancel of a parent cancels all of its subagents. The user can cancel one subagent with `/cancel <name>`. A cancelled subagent keeps its history. Its state becomes `CANCELLED`.

## Limits

| Limit | Default |
|-------|---------|
| Subagents running at the same time for each instance | 8 |
| Nesting depth (subagent that spawns a subagent) | 2 |
| Model calls for each subagent task | 200 |
| Wall-clock time for each subagent task | 30 minutes |

When a limit is reached, the runtime stops the subagent. Its state becomes `FAILED` with the reason.

The key `subagents.stepLimit` sets the limit of the model calls of a subagent task. It also sets the step limit of the turns of a subagent. The key `agents.stepLimit` sets the step limit of the turns of the main agent. See [agent-loop.md](agent-loop.md#the-loop).

## Worktrees

Jeorje has built-in support for git worktrees. When the project is a git repository, each subagent that Jeorje creates gets its own worktree and its own branch. Subagents can then change files at the same time with no effect on each other or on the parent.

### When a worktree is used

The key `worktree.isolation` selects the mode:

| Value | Behavior |
|-------|----------|
| `auto` (default) | Jeorje uses a worktree when the working directory of the parent is inside a git work tree, `HEAD` is on a branch, and `git` is available. Otherwise it uses the shared directory. |
| `always` | Jeorje uses a worktree. If it cannot, `spawn_agent` fails with an error. |
| `never` | All agents share the working directory. |

A `spawn_agent` call can set `isolation` to `worktree` or `shared` for one subagent. This replaces the global mode for that call. The `workdir` field still sets an explicit directory. A subagent with an explicit `workdir` gets no worktree.

The same rule applies to the story workers of `/implement` and to every nested subagent. A remote subagent (see [swarm.md](swarm.md)) runs in the workspace of its peer. Jeorje does not make worktrees across peers.

If a worktree cannot be made (no `git`, not a repository, or an error), Jeorje falls back to the shared directory in `auto` mode. It shows one notice and logs the reason.

### Layout and branches

- **Path:** `${CWD}/.jeorje/worktrees/<group>/<name>/`. The group is the plan slug for `/implement`, or `session` for other subagents. The name is the story id, or the slug of the agent name. The slug has lower-case letters, digits, `.`, `_`, and `-`, and at most 60 characters.
- **Branch:** `<branchPrefix>/<group>/<name>`. The default prefix is `jeorje`. If the branch name or the path exists, Jeorje adds a numeric suffix to the name (`-2`, `-3`, and so on).
- **Base:** the commit where the worktree starts. The key `worktree.base` selects it:
  - `head` (default): the current `HEAD` commit of the parent. Uncommitted changes of the parent are not included. The subagent does not see them.
  - `snapshot`: a commit of the current state of the parent working tree, with the tracked changes and the untracked files that are not ignored. Jeorje builds it with a temporary index, so the index, the branch, and the files of the parent do not change. The subagent sees what the parent sees. The snapshot commit has the message `jeorje: snapshot`. It is not part of the history of the source branch. If the merge back included it, git would refuse the merge, because the same files are still uncommitted in the parent tree. Jeorje therefore never merges the snapshot commit back. In this mode the sync step uses a rebase that drops the snapshot commit (see [Completion sequence](#completion-sequence)). The parent must commit the uncommitted files that the work depends on, or the merged result can depend on files that the source branch does not have.
- **Parent branch:** a subagent of a subagent starts from the branch of its parent subagent. The nesting depth limit applies.
- **Project directory:** the `.jeorje` directory (config, sessions, indexes) stays in the main project directory. The tools of the subagent run in its worktree. Its path sandbox is its worktree and the directories that the config adds. The subagent cannot reach the main working tree. The worktree contains the committed `.jeorje/config.yaml`, but Jeorje does not read config or sessions from the worktree.
- **Ignored files:** a worktree has only tracked files and the files of the snapshot. Files that git ignores (build output, `.env`, installed dependencies) are not there. The key `worktree.copy` lists ignored paths that Jeorje copies into each worktree. The key `worktree.link` lists ignored paths that Jeorje links to the main tree, for example a dependency cache. A worktree builds its own output (`target/`), so the first build in a worktree takes longer.

### Git operations

`WorktreeManager` in `jeorje-core` makes all git calls. It runs the `git` program (version 2.17 or newer, which has `git worktree remove`). Jeorje does not add a git library to the tech stack.

- One lock for each repository serializes the calls that change repository metadata or the index: `worktree add`, `worktree remove`, `worktree prune`, `branch`, `merge`, `merge --abort`, `rebase`, `commit`, and `reset --merge`. Git can fail with an index lock error when callers run at the same time.
- Each call has a timeout (120 seconds) and runs with a minimal environment. The log has the call with secrets removed.
- At startup, Jeorje runs `git worktree prune` and compares the worktrees in the session manifest with the worktrees that git lists under `.jeorje/worktrees`. It reports leftover worktrees (on disk, not in the manifest) and missing worktrees (in the manifest, not on disk).
- These calls are internal actions of Jeorje. They are not tool calls of the model, so the permission policy does not prompt for them. The manual merge (`worktree_merge`, `/merge`) is an exception. See [Manual merge](#manual-merge).

### Commits

A subagent can use `bash` to run `git` commands in its worktree, subject to the permission policy. When a subagent finishes, Jeorje commits all remaining changes in its worktree with the message `jeorje: <name>` and the first line of its task. The message has a blank line between these two parts. Jeorje uses the git identity of the user. If the identity is not set, Jeorje uses `Jeorje <jeorje@localhost>` for these commits and logs a warning.

### Completion sequence

When a subagent that has a worktree completes its work, it does not just hand over its branch. It first merges from the source, resolves all issues, and then merges back. The **source** is the branch that the worktree started from: the branch that the parent has checked out in its own worktree (the main working tree for a root agent). The merge back goes to the same branch.

1. **Commit.** Jeorje commits the remaining changes in the worktree. See [Commits](#commits).
2. **Sync.** The state of the subagent is `SYNCING`. Jeorje brings the current source branch into the branch of the subagent, inside the worktree. This brings in the work that the parent and the siblings merged since the worktree started. The method depends on the setting:
   - `worktree.merge` is `merge` or `squash`, and the base is `head`: `git merge <source>`.
   - `worktree.merge` is `rebase`: `git rebase <source>`.
   - The base is `snapshot`: `git rebase --onto <source> <snapshot commit>`, which replays only the commits of the subagent on the source tip and drops the snapshot commit. The subagent resolves a conflict in each step, and `sync_done` continues the rebase.
3. **Resolve.** If the sync gives conflicts, or if the verification after the sync fails, the state becomes `RESOLVING` and the subagent resumes work.
   - The runtime puts a message in the inbox of the subagent. It lists the conflicting files, the output of `git status`, the subjects of the commits that came from the source, and the failing verification output.
   - The subagent edits the files in its worktree. It fixes the conflict markers and the problems that the merge caused (for example a build or test that fails because of the other work). It can use `agent_ask` to ask the agent that made the other change. See [Talking to other agents](#talking-to-other-agents).
   - The tool `sync_done(notes)` ends the resolution. The tool does the git work: it stages all files and checks that no conflict markers remain (`git diff --cached --check`). If markers remain, the tool returns an error and the subagent continues. If not, the tool commits the merge, or continues the rebase (`git rebase --continue`). If the next commit of a rebase also has conflicts, the tool returns the new conflict list and the subagent calls `sync_done` again. When no merge or rebase is in progress (a verification fix), the tool commits the staged changes with the message `jeorje: <name> fixes after sync`. The runtime then runs the verification again. After the third failed verification the subagent ends with the outcome `failed`.
   - If the subagent ends its turn with no `sync_done` and no `sync_abort`, the runtime reminds it. After two reminders the sync is aborted and the subagent ends with the outcome `failed`.
   - The tool `sync_abort(reason)` gives up. Jeorje runs `git merge --abort`, or `git rebase --abort` when a rebase is in progress. The subagent ends with the outcome `failed` and the reason. The worktree and the branch stay.
   - The resolution has its own limit: `worktree.sync.maxModelCalls` model calls (default 30) for each resolution. When the subagent uses all of them, the sync is aborted and the subagent ends with the outcome `failed`.
4. **Verify.** After a clean sync, Jeorje runs the verification command. For a story worker, it is the verification of the story. For another subagent, it is `worktree.sync.verify` (empty by default, which means none). `/implement` then runs its review before the merge back. See [planning.md](planning.md#review).
5. **Merge back.** The state becomes `MERGING`. Jeorje merges the branch of the subagent into the source branch, in the worktree of the parent, with the strategy `worktree.merge`: `merge` (a merge commit with `--no-ff`, the default), `squash` (one commit), or `rebase` (a fast-forward of the rebased branch). Jeorje takes the **integration lock** of the source branch for this step (one lock for each repository and source branch), so two subagents never merge into the same branch at the same time.
6. **Race.** After the sync, the tip of the source branch must be an ancestor of the branch of the subagent (`git merge-base --is-ancestor <source> <branch>`). If the source branch moved between the sync and the merge back (a sibling merged first), this check fails. Jeorje releases the lock and goes back to step 2. A round is one pass through steps 2 to 5. After `worktree.sync.maxRounds` rounds (default 3), Jeorje makes one more round. In this round it takes the integration lock before the sync and holds it until the merge back finishes. No other merge into that branch can then move it.
7. **Finish.** The runtime writes the completion summary. See [Completion summary](#completion-summary). The `Branch` part states the merge result and the commit. The parent then cleans up. See [Finish and cleanup](#finish-and-cleanup).

### Dirty parent tree

The merge back changes files in the worktree of the parent. If the parent has uncommitted changes in files that the merge touches, git refuses the merge. Jeorje then:

1. Reports the files. The report goes to the parent in a way that depends on how the parent started the subagent:
   - **`wait` mode.** The parent is blocked inside `spawn_agent` and cannot commit or stash. Jeorje does not wait. It ends the sequence at once with the outcome `completed (not merged)`. The result of `spawn_agent` lists the files and says to commit them and run `/merge <agent>`.
   - **`background` mode.** Jeorje puts a message in the inbox of the parent. It lists the files and asks the parent to commit or stash them.
2. In `background` mode, tries again every 30 seconds for up to `worktree.mergeWaitSeconds` (default 300).
3. Ends the wait with the outcome `completed (not merged)`. The worktree and the branch stay. `/merge <agent>` can start the sequence again.

### Manual merge

With `worktree.mergeBack: manual`, steps 5 and 6 do not run when the subagent completes. The sync and the resolution (steps 1 to 4) still run, so the branch is ready to merge. The completion summary (step 7) says that the branch waits for a manual merge. The subagent state changes to `COMPLETED`. The parent or the user then merges with one of these:

- the `worktree_merge` tool (`agent`, optional `strategy`),
- the slash command `/merge <agent>`.

The `worktree_merge` tool is `ASK` by default. See [permissions-and-safety.md](permissions-and-safety.md). `/merge` commits the remaining changes. It runs the sync again first if the source moved after the sync or if the snapshot commit is still in the branch. It then runs the verification and merges back. Nobody resolves a conflict in this path: the sync stops, Jeorje restores the branch, and the result lists the conflicting files. After a successful merge, Jeorje does the cleanup (see [Finish and cleanup](#finish-and-cleanup)).

The default `worktree.mergeBack` is `auto`: the merge back runs when the subagent completes. The internal git merge is not a tool call, so the permission policy does not prompt for it. The user can set `manual` to approve each merge.

### Conflict that nobody resolves

If the sync cannot be resolved (`sync_abort`, the limits, or a subagent that failed), the source branch stays unchanged. The result of the subagent lists the conflicting files. The parent or the user can start a repair subagent whose task text has the conflict, or resolve it by hand and run `/merge <agent>`.

### Finish and cleanup

When a subagent ends, `WorktreeFinisher` does the cleanup for the parent. If the subagent completes with **no issues**, it deletes the worktree of the subagent.

**No issues** means all of these are true:

- The outcome of the subagent is `completed`.
- The verification passed after the sync.
- The merge back into the source branch is done.
- The worktree has no uncommitted changes and no commits that are missing from the source branch.

Conflicts that the subagent resolved during the sync are not issues. Conflicts that nobody resolved are issues.

**Steps.** `WorktreeFinisher` runs when the runtime reports that the subagent finished. It does these steps:

1. Stops all processes that still run in the worktree (for example a background `bash` process).
2. Runs `git worktree remove` for the worktree. It does not use `--force`, so git refuses when the worktree has changes that Jeorje did not see.
3. Deletes the branch of the subagent. With the strategies `merge` and `rebase`, the branch is an ancestor of the source branch, so `git branch -d` works. With `squash`, git does not see the branch as merged, because the squash commit has no link to it. Jeorje then checks that the squash commit it made is on the source branch and uses `git branch -D`. If the check fails, Jeorje keeps the branch.
4. Writes a `worktree removed` note in the history of the parent, with the agent name, the branch, and the merge commit. The `/worktrees` list and the agent switcher no longer show the worktree.

For a root agent, the parent is the foreground agent. For the workers of `/implement`, the parent is the orchestrator. For a nested subagent, the parent is the subagent above it. The removal is an internal action of Jeorje. No permission prompt occurs. The note goes to the history of the parent only when the removal succeeded.

**When there are issues,** the parent does not delete the worktree. Jeorje keeps the worktree and the branch until the issue is solved and the branch is merged, or until the user discards them. The issues are: an outcome of `failed` or `cancelled`, a failed verification, an unresolved conflict, a merge back that did not happen (for example `completed (not merged)` or a manual merge that waits), and any uncommitted or unmerged changes. The completion summary states the reason, and the keep is listed at exit.

The key `worktree.onFinish` changes the rule:

| Value | Behavior |
|-------|----------|
| `delete-if-clean` (default) | The rule above: the parent deletes the worktree and the branch when there are no issues. |
| `keep` | The parent never deletes a worktree on its own. |
| `remove` | The parent also deletes the worktree of a subagent that has issues, after it has recorded the outcome. Unmerged commits are not lost, because Jeorje keeps the branch and deletes only the worktree directory. If git refuses, Jeorje uses `--force` in this mode. |

`/discard <agent>` and the `worktree_discard` tool remove the worktree and delete the branch with all its commits. Both are `ASK`. After a manual merge succeeds, the parent deletes the worktree with the same steps. At exit, Jeorje lists the worktrees that it kept, with their branches. `/worktrees prune` removes the worktrees of merged and discarded branches that are still on disk.

### Commands and UI

| Command | Action |
|---------|--------|
| `/worktrees` | List worktrees with the agent, branch, path, and state (`clean`, `changed`, `merged`, `missing`) |
| `/merge <agent>` | Run the completion sequence for a subagent: sync from the source, resolve, and merge back |
| `/discard <agent>` | Remove the worktree and branch of a subagent |
| `/worktrees prune` | Remove leftover worktrees that are merged or discarded |

The agent switcher shows the branch name of each subagent. The status line shows the branch of the foreground agent. `agent_status` includes the branch and the worktree state.

### Shared directory

When agents share the working directory, two agents can change the same file. The `write` and `edit` tools check the modification time and hash of each file (see [tools.md](tools.md)). A write fails if another agent changed the file after the read. The agent must then read the file again. `/implement` does not run two stories at the same time if their `touches` lists share a path. This rule applies in every mode, also with worktrees, so that merges stay simple.
