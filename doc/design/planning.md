---
title: Planning and Implementation
description: The /plan mode that writes a plan of stories and tasks, and the /implement command that runs a team of story-scoped subagents.
---

# Planning and Implementation

Two slash commands support larger work.

- `/plan` puts the agent in **plan mode**. The agent explores the workspace and writes a plan. The plan is a list of **stories**. Each story has **tasks**.
- `/implement` takes a plan and runs a team of subagents. Each subagent implements one story. A subagent gets only its own story in its working context.

The plan code is in `jeorje-core` (`PlanMode`, `PlanParser`, `PlanValidator`, `PlanWriter`, `ImplementOrchestrator`). The `plan_submit` tool and the story tools are in `jeorje-tools`. Subagents are described in [subagents.md](subagents.md).

## Terms

- **Plan**: a file that describes how to reach a goal. It has stories.
- **Story**: one unit of work that a single subagent can finish alone. It has a goal, acceptance criteria, context, and tasks.
- **Task**: one step in a story. A subagent does the tasks of its story in order.
- **Worker**: the subagent that implements one story.
- **Orchestrator**: the foreground agent that runs `/implement`. It schedules the workers.

## Plan mode

### Commands

| Command | Action |
|---------|--------|
| `/plan <goal>` | Enter plan mode with a goal |
| `/plan` | Enter plan mode and continue the current goal. In plan mode, show the current plan. |
| `/plan show` | Show the current plan in the chat view |
| `/plan edit` | Open the plan file in the editor |
| `/plan done` | Leave plan mode and keep the plan |
| `/plan cancel` | Leave plan mode and discard the draft. See [Cancel](#cancel). |

The status line shows `PLAN` while the mode is active.

### Cancel

`/plan cancel` follows these rules:

- If plan mode wrote no plan file, nothing is deleted.
- If plan mode created the plan file, Jeorje deletes the file.
- If plan mode replaced an older plan file one time, the older plan comes back from `<name>.prev.md`.
- If plan mode replaced the plan file more than one time, the older plan is no longer available. The last draft stays.
- The goal is cleared.

### Behavior

In plan mode the agent cannot change the workspace.

- **Tools.** The agent has the read-only tools (`read`, `ls`, `glob`, `grep`, `recall`, `webfetch`), `skill`, `ask_user`, `advisor`, `todo`, and `plan_submit`. `bash` is available. The normal rules of the permission policy decide each call: a call can need approval, and in a headless run it needs `--yes` or `--yolo`. The prompt tells the agent to use `bash` to explore and not to change the workspace. The agent asks the user with `ask_user`, and the user sees a form: single choice, multiple choice, or text, with the option to chat about a question. See [Forms](ui.md#forms). The answers of `ask_user` stay in the working context: compaction never removes them. The agent finds them again with `recall` and the filter `kind` set to `user`. The `--yolo` option does not change the tool set. `write`, `edit`, and `spawn_agent` are not registered. See [permissions-and-safety.md](permissions-and-safety.md).
- **Plan file.** `plan_submit` is the only tool that writes. It writes only to the plan directory (`plan.dir`, default `plan/`, relative to `${CWD}`). The plan directory is not inside `.jeorje`. It follows the `AGENTS.md` convention for plans, and the user can commit it.
- **System prompt.** Jeorje adds a plan-mode section to the system prompt. It tells the agent to explore first, to ask the user when a requirement is open, and to write stories that a worker can finish with no other context.
- **Review.** After `plan_submit`, the UI shows the plan. The user can edit it with `/plan edit`, ask the agent for changes, or leave plan mode with `/plan done`. A call of `plan_submit` for the same plan replaces the file, and Jeorje keeps the previous version as `<name>.prev.md`.
- **Advisor.** If an advisor model is set, the agent can call `advisor` on the draft. The default plan-mode prompt tells it to do so before `plan_submit`.

### The `plan_submit` tool

Input: the plan as structured data (title, goal, stories, tasks), an optional `verification` command, and an optional `name`. The `name` is the file name without extension. Without it, the file name comes from the title. A call with the same name replaces the plan file. `plan_submit` checks the plan and writes the file with the status `draft`. The checks are:

1. Story ids are unique. Task ids are unique and start with the id of their story and a dot (for example `S1.T1`).
2. Each story has a title, a goal, acceptance criteria, context, and at least one task.
3. The `depends_on` references point to stories in the plan, and the dependencies have no cycle.
4. Each story is **self-contained**. The context part names the files and the facts that the worker needs. It does not say "as discussed" or refer to other stories or to the conversation. `plan_submit` rejects a story whose goal, context, acceptance criteria, verification, or tasks have a phrase such as "as discussed" or "see story", or that name the id of another story.
5. If two stories can run at the same time (no dependency path between them), their `touches` lists must not share a path. If they do, `plan_submit` asks the agent to add a dependency or to merge the stories.

6. The plan has at most 50 stories. A story has at most 20 tasks and at most 8,000 characters. See [Limits](#limits).

If a check fails, the tool returns an error that names the story and the rule. The agent corrects the plan and calls the tool again. The tool also refuses a plan whose text breaks the file format (for example a line that starts with `## ` or `### ` inside the text of a story).

## Plan file format

A plan is a markdown file with YAML front matter. The front matter has 20 lines at most. The body has the stories. The file name is `<slug>.md`, where the slug comes from the title.

```markdown
---
title: Add rate limiting to the API
status: draft            # draft, approved, in-progress, done, or blocked
goal: Requests above 100 per minute for each key get HTTP 429.
created: 2026-10-18
updated: 2026-10-18
stories: 3
verification: mvn verify
---

# Plan: Add rate limiting to the API

## Story S1: Token bucket class
- **Goal:** A thread-safe token bucket in `jeorje-core`.
- **Depends on:** none
- **Touches:** `src/main/java/.../ratelimit/`, `src/test/java/.../ratelimit/`
- **Model:** default

### Context
The module `api` uses `java.time.Clock`. Use constructor injection for the clock.
`TokenBucket` must not allocate on the hot path. The test style is in `FooTest.java`.

### Acceptance criteria
- `TokenBucket.tryAcquire()` returns false when the bucket is empty.
- Tests cover refill and concurrent use.

### Verification
`mvn -pl jeorje-core test`

### Tasks
- [ ] S1.T1 Create the `TokenBucket` class.
- [ ] S1.T2 Add unit tests with a manual clock.

## Story S2: ...
```

- **Parts of a story:** goal, depends on, touches, model (optional), context, acceptance criteria, verification (optional, defaults to the plan `verification`), and tasks.
- **Checkboxes:** `- [ ]` for open, `- [x]` for done, and `- [!]` for blocked. The orchestrator changes them during `/implement`. A person can also change them.
- **Handoff section.** The orchestrator can add a `### Handoff` section to a story before it starts. See [Context rule](#context-rule). A retried story can have a `### Previous attempt` section. See [Failure](#failure).
- **Other text.** Text between the front matter and the first story, and text after the last story, stay in the file. `PlanWriter` changes only the checkboxes and the handoff sections of an existing file.
- **Parser.** `PlanParser` reads the headings (`## Story <id>: <title>`) and the labeled parts. It reports an error with the line number if the structure is wrong (for example an unknown label, an unknown section, or a duplicate id). `Depends on: none` and `Touches: none` mean an empty list. A person can write or edit a plan without `/plan`.

Plans are normal files. The user can commit them. Documentation never refers to a specific plan.

## Implementation

### Commands

| Command | Action |
|---------|--------|
| `/implement` | Implement the latest plan in the plan directory. Ask if more than one plan has open stories. |
| `/implement <path or slug>` | Implement the plan in that file |
| `/implement <path or slug> S2 S4` | Implement only the named stories, with their open dependencies |
| `/implement pause` | Stop starting new stories. Running workers finish. |
| `/implement resume` | Continue a paused run |
| `/implement status` | Show the stories, their state, and the workers |
| `/implement stop` | Cancel all workers and end the run |

`/implement` runs in the interactive UI. It also runs with no UI. See [Headless runs](#headless-runs). A path or a slug names the plan. A story id has one to four letters and digits, for example `S2`.

### Run steps

1. **Load.** `PlanParser` reads the plan. If the plan has an error, `/implement` stops and shows the error. Stories with all tasks done are skipped.
2. **Approve.** If the plan status is `draft`, `/implement` shows the stories and asks the user to approve. Approval sets the status to `approved`. With `--yes` or `--yolo`, Jeorje approves without a question. A plan that is not a draft needs no approval. When the run starts, the plan status becomes `in-progress`.
3. **Schedule.** The orchestrator builds a graph from `depends_on`. It starts every story that has no open dependency, up to `implement.parallel` workers (default 3). It does not run two stories at the same time if their `touches` lists share a path or if one path is inside the other.
4. **Work.** Each worker implements its story. See [Workers](#workers).
5. **Sync, verify, and review.** When a worker reports done, the worker first merges from the source branch and resolves conflicts and failures (see [Completion sequence](subagents.md#completion-sequence)). Jeorje then runs the verification of the story in the worktree. If an advisor model is set and `implement.review` is `advisor` (default), the advisor model reviews the result against the acceptance criteria. The review runs after the sync and the verification, and before the merge back. See [Review](#review).
6. **Merge back.** If the story ran in a separate worktree, the orchestrator merges its branch into the source branch with the strategy `worktree.merge`. The branch already has the source. If a sibling merged first, the worker syncs again. See [Isolation](#isolation). A worker with no worktree runs the verification and the review in the shared directory.
7. **Continue.** The orchestrator starts the stories that are now free. It repeats until all stories are done or blocked.
8. **Finish.** If all stories are done, the orchestrator runs the plan-level `verification`. It sets the plan status to `done`, or to `blocked` if a story is blocked or the plan verification fails. After `/implement stop` the status stays `in-progress`. The orchestrator shows a report.

### Workers

A worker is a subagent. The orchestrator creates it with the rules of `spawn_agent` (see [subagents.md](subagents.md)) and these settings. The system prompt of the worker names the worker and the orchestrator, as for any subagent. The task of the worker is the text of its story.

- **Name:** the story id and title, for example `S1 Token bucket class`.
- **Model:** the `Model` of the story, or `implement.model`, or the default model (`/model`).
- **Tools:** the full coding toolset without `spawn_agent`, `plan_submit`, and `ask_user`. A worker has `advisor` if an advisor model is set. It has the agent tools `agent_message`, `agent_ask`, `agent_answer`, and `agent_status` (see [Talking to other agents](subagents.md#talking-to-other-agents)), `recall`, and the read tool `story_read`. When worktrees are on, it has `sync_done` and `sync_abort`. It also has these story tools:
  - `task_done(taskId, note)` marks a task done. The orchestrator writes `[x]` in the plan file.
  - `story_done(summary, filesChanged)` ends the story. The worker calls it after it ran the verification and all acceptance criteria are true. The `summary` is a **draft** of the completion summary, with the labeled parts of [subagents.md](subagents.md#completion-summary) (outcome, done, files, branch, decisions, verification, open issues, keywords). The runtime writes the final summary after the merge back, because the final summary has the merge result and the resolved conflicts. See [Completion summary](subagents.md#completion-summary). The orchestrator gets the final summary as a `subagent_summary` chunk in its history. The draft goes to the review only. `story_done` is refused while a task of the story is not done.
  - `story_blocked(reason)` ends the attempt with a reason that the worker cannot fix.
  - `sync_done(notes)` and `sync_abort(reason)` end the conflict resolution after the sync. Only in the `RESOLVING` state.
- **Permissions:** the same policy as the orchestrator, and never wider. The story tools and the sync tools change no workspace file, so they never ask the user. A prompt from a worker goes to the UI and has the story id of the worker. With `--yolo`, there are no prompts.
- **Limits:** the limits of [subagents.md](subagents.md#limits) apply, and `implement.maxModelCalls` (default 200) for each story.

A worker does the tasks of its story in order. It cannot start another story and cannot see other workers.

### Context rule

**A worker has only its story in its working context.**

The working context of a worker has:

1. The normal system prompt: identity, tool guidance, workspace description, `AGENTS.md`, and the pruned-chunk guidance, with one added line that names the worker and the orchestrator. See [agent-loop.md](agent-loop.md#system-prompt).
2. One user message. It has the text of its story: goal, depends on, touches, context, acceptance criteria, verification, tasks, and the handoff section if one exists.

Nothing else is in the working context at the start. Specifically:

- The working context of the worker does not have the conversation of the orchestrator, the plan goal, the other stories, or the results of other workers. The worker can get the other stories with `story_read` (see [Story visibility](#story-visibility)), and it can ask other agents (see below).
- The worker has its own history. Its `recall` searches the full index of the session, so it can find chunks of other agents, including the orchestrator, if it asks. This does not change what the runtime puts in the working context. See [Recall in a subagent](subagents.md#recall-in-a-subagent). The planner should still write each story so that the worker does not need `recall` for the facts of the story.
- A worker finds the work of earlier stories in the workspace (the files), not in its context.
- The plan-level goal and the plan title are not given to a worker. If a story needs them, the context part of the story contains the needed facts.

**Handoff.** A story can depend on the result of another story in a way that the planner could not know. When `implement.handoff` is `true` (default), the orchestrator can add a `### Handoff` section to a story before it starts. The section has short facts from the stories in `depends_on`: the `Done`, `Files`, and `Keywords` parts of their final completion summaries. The orchestrator adds no handoff to a story that has no dependency or that has a handoff already. The handoff is part of the story text in the plan file. The worker sees it as part of its story. The orchestrator writes the handoff from the final completion summaries, not from the histories of the workers.

### Story visibility

Workers can always see each other's stories. This does not change the working context at the start. It adds one read tool.

- `story_read()` lists all stories of the plan with the id, title, state (`pending`, `running`, `done`, `blocked`), the attempt number, and the name of the worker, if any.
- `story_read(storyId)` returns the full text of one story: goal, depends on, touches, context, acceptance criteria, verification, tasks with their checkboxes, and the handoff section. The worker can read the stories of finished, running, and pending workers, also its own.
- `story_read` reads from the plan that the orchestrator holds, not from a file in the worktree of the worker. The result has the current states and handoff sections.
- `story_read` does not give the plan title, the plan goal, or the history of any agent. For the history, a worker uses `recall` (see [Recall in a subagent](subagents.md#recall-in-a-subagent)).
- `story_read` is read-only and is `ALLOW` in the permission policy.

A worker uses the other stories to check the `touches` of its neighbors, to find the interface that another story builds, and to find the worker that it must ask. Workers can ask each other questions. See [Talking to other agents](subagents.md#talking-to-other-agents). The orchestrator answers questions that are for it, or asks the user.

### Isolation

Workers can run at the same time, so they must not change the same files in a harmful way. Each worker uses the worktree support of Jeorje. See [Worktrees](subagents.md#worktrees).

- In a git repository (with `worktree.isolation` not `never`, and `HEAD` on a branch), each worker has its own worktree and branch: `jeorje/<plan-slug>/<story-id>`. The worktree is in `${CWD}/.jeorje/worktrees/<plan-slug>/<story-id>/`. The plan slug is the file name of the plan without `.md`. A retried attempt gets the suffix `-a2`, `-a3`, and so on.
- In any other directory, workers share the working directory. The `touches` lists keep two overlapping stories apart, and the file-state check of `write` and `edit` stops stale writes.
- **Start state.** The worktree of a story is created when the story starts. It starts from the current `HEAD` of the target branch, so it has the merged results of the finished stories. Uncommitted changes of the user are not in the worktrees. When the user starts `/implement` with uncommitted changes, Jeorje shows a notice. With `worktree.base: snapshot`, the workers see the uncommitted changes. See [Worktrees](subagents.md#layout-and-branches).
- **Sync, then merge back.** When a worker completes its work, it merges the source branch into its own branch and resolves the conflicts and failures itself. See [Completion sequence](subagents.md#completion-sequence). The orchestrator merges the branch back into the source when the story passes review, in the order that stories finish. It uses `worktree.merge`. The orchestrator does not resolve conflicts. A worker that cannot resolve its conflicts ends with `sync_abort`, and the story is `blocked` with the conflicting files in the reason. The user can resolve them, or ask the orchestrator to start a **repair story** (`<story-id>-repair`): a new worker whose story text has the conflict, the text of the blocked story, and the text of finished stories that touch the same files. The repair worker merges the kept branch into its own worktree and resolves the conflict. When the repair story is done, the blocked story is done too. A repair story starts only when no run is active.
- **Cleanup.** When a story completes with no issues, the orchestrator (the parent) deletes the worktree and the branch of the worker. See [Finish and cleanup](subagents.md#finish-and-cleanup). It keeps the worktree of a blocked story until the user runs `/implement stop`, `/discard`, or fixes the story. A retried attempt discards its worktree and its branch first. A story that completes but is not merged (for example because the parent tree has uncommitted changes) is `blocked` with the reason `completed (not merged)`.

### Review

After a worker calls `story_done`:

1. After the worker finishes the sync, Jeorje runs the verification command of the story in the worktree of the worker with a shell. A failing command returns the worker to the state `RESOLVING` with the output. After three failed runs, the attempt fails (see [Failure](#failure)). A worker with no worktree gets two fix rounds.
2. If `implement.review` is `advisor` and an advisor model is set, the advisor model gets: the story text, the `story_done` summary, the list of changed files, and the diff against the source branch. It returns `approve` or a list of problems. A list of problems ends the attempt (see [Failure](#failure)). If the review cannot run, Jeorje shows a notice and continues without it. The review runs one time for each attempt.
3. If both steps pass, Jeorje merges back and the story is done.

The setting `implement.review: none` skips step 2. Step 1 always runs when the story or the plan has a verification command.

### Failure

- A failed verification, a rejected review, a `story_blocked` call, or an error ends one **attempt**.
- The orchestrator discards the worktree and the branch of the attempt, opens the checkboxes of the story, and starts a new worker for the next attempt. The new worker has a clean context. It has the story text and a `### Previous attempt` section with the reason for the failure and the last lines of the failing output. The story text stays the only input.
- The default limit is 2 attempts (`implement.maxAttempts`). After the last failed attempt, the story is `blocked`. The orchestrator marks its first open task `- [!]` and does not start stories that depend on it. A `sync_abort` of a worker blocks the story at once, with no new attempt, and Jeorje keeps the branch. It continues with independent stories.
- The orchestrator reports blocked stories at the end and in the status. The user can fix the cause and run `/implement` again. Done tasks stay done.

### Orchestrator context

The orchestrator is the foreground agent. It keeps a compact view of the run in its own context: for each story, the id, title, state, attempt number, and a one-line result. It does not read the detailed output of workers. The orchestrator is the root agent of its own subagent runtime. The user can switch into any worker with `Ctrl-X a` and steer it. See [subagents.md](subagents.md#foreground-switching). A message that the user sends to a worker becomes part of that worker's history but not of the story text.

### Resume

The run state is in the manifest of the session (the file `implement.json` in the session directory): the plan path, the story states, the attempts, the worker ids, and the worktrees. The checkboxes in the plan file are the durable record. If Jeorje stops during a run, `/implement` with the same plan reads the checkboxes and the manifest. It starts again with the stories that are not done. A story that was running starts with a new worker and a `### Previous attempt` section that states that the run was interrupted. A story that was blocked starts again with its blocked checkbox reopened, and a `### Previous attempt` section with the reason.

## Headless runs

The command `jeorje run` runs the plan commands with no UI. No user is present, so the run follows these rules.

| Command | Action |
|---------|--------|
| `jeorje run "/plan <goal>"` | Run plan mode with the goal. The command prints the path of the plan file. `/plan` with no goal is an error. |
| `jeorje run "/implement [plan] [stories]"` | Run `/implement` and print the final report. |
| `jeorje run --plan <path> [stories]` | The same as `/implement <path> [stories]`. The task text lists the story ids. |
| `jeorje run "/implement status"` | Print the state of the last run, read from the `implement.json` file of the newest session that has one. |

- A form from `ask_user` is answered in this order for each question: a value of `--answer <id>=<value>`, then the default value of the question, then the `--yes` or `--yolo` rule. With `--ask`, if these rules cannot answer every question, the run asks the whole form on the terminal. The `--yes` rule is: the first option for `single`, the first options up to `minChoices` (at least one when the question is required) for `multi`, and "Use your best judgment and go on." for `text`. For a question that is not required, with no value, no default, and no `--yes`, the answer stays empty.
- `--answer` takes the id of a question and a value. Repeat it for each question. A value is the label of an option or its number from 1. A `multi` value lists them with commas. A `text` value is the answer. When the question has `allowOther`, any other text is the other answer. A value that does not fit the question stops the run with an error. A form with the old input (`question` and `choices`) has the id `q1`.
- When a question has no value and no rule applies, the run stops. It prints the form as numbered text, with the id, the kind, and the options of each question, and the line that says to run again with `--answer <id>=<value>`. It exits with code 2.
- `--ask` asks the questions of the form on standard input as plain text. See [Plain text](ui.md#forms).
- A plan with the status `draft` needs `--yes` or `--yolo` for the approval. Without them, the run ends with the status `not approved`.
- A tool call that needs a permission decision is denied, and the run prints a line that names the story. `--yes` or `--yolo` approves, as in the interactive UI.
- `/implement pause`, `resume`, and `stop` are not available, because the run ends with its report. `jeorje run` prints an error for them.
- The exit code is 0 when the plan is `done` and 1 for any other result.
- Progress lines start with `IMPLEMENT:` and go to the error stream. The report goes to the output stream.
- Worktrees, the sync, the review, and the merge back work as in the interactive UI. The slash commands `/merge`, `/discard`, and `/worktrees` exist only in the interactive UI.

## Events and UI

- The status line shows `IMPLEMENT n/m` (stories done of total) and a count of blocked stories.
- The agent switcher shows each worker with its story id, its state, and the current task.
- `ConfirmationRequested` events from workers have the story id in the prompt. The orchestrator gives the story id of a worker with `storyOf(agentId)`.
- `/implement status` shows a table with the story, state, attempt, worker, and branch.
- A final report shows each story with its result, the files changed, and the blocked stories with their reasons.

## Configuration

```yaml
plan:
  dir: plan                        # relative to the project directory
implement:
  parallel: 3                      # workers at the same time
  model: ""                        # empty means the default model
  review: advisor                  # advisor or none
  maxAttempts: 2
  maxModelCalls: 200               # for each story
  handoff: true
```

Isolation is set with `worktree.isolation`. See [Worktrees](subagents.md#worktrees) and [configuration.md](configuration.md).

## Limits

- A plan has at most 50 stories. `plan_submit` rejects a larger plan. The planner can split the work into several plans.
- A story has at most 20 tasks.
- The text of a story is at most 8,000 characters. A longer story is a sign that it is too large for one worker.
