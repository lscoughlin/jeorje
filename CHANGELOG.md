# Changelog

This file records the notable changes to Jeorje. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Release `0.3.0` is the first tagged release. The earlier `0.1.0-SNAPSHOT` and `0.2.0-SNAPSHOT` lines were development lines. Jeorje never released them. Release `0.3.0` contains their work.

## 0.4.0 - 2026-11-06

### Added

- The orchestrator of `/implement` is an agent with its own model loop, history, and system prompt. It answers the questions of the workers. Before, a question to the orchestrator got no answer and ended after the time limit.
- When a person must decide, the orchestrator gives the worker a holding answer, asks the user with `ask_user`, and sends the decision to the worker as a message. A question that gets no answer ends with a fallback answer, so a worker never waits for the full time.
- `/implement` makes the orchestrator the foreground agent. The user types to it and sees its turns. When the run ends, the foreground returns to the main agent if the orchestrator is the foreground agent at that time. The orchestrator stays in the agent list, and the workers are its children.
- A form of a background orchestrator waits until the user switches to the orchestrator.
- A run with no user (`jeorje run "/implement"`) has the orchestrator loop too, with no `ask_user` tool. The report lists the questions that needed a person and the assumptions of the workers.
- The tool `agent_answer` has the optional field `needsPerson`.
- The command `/resume` lists the sessions of the project, newest first, with the time of the last update, the turn count, and the first text of the user. `/resume <id>` opens one of them in the foreground.
- The key `implement.reviewOutputTokens` (default 2048) sets the output limit of the first review call. The key `implement.reviewRetryOutputTokens` (default 8192) sets the output limit of the later review calls after a blank answer at the output limit.

### Changed

- The foreground session is the session that owns the foreground agent. `/implement status`, `pause`, `resume`, and `stop` and the status line work when the foreground agent is an orchestrator or a worker of a run.
- A review of `/implement` that gives no answer, or whose call fails, is not a failed review. Jeorje asks again two times. If the review is still unavailable, the story goes on to the merge back with a notice and a line in the report.
- A review that ends blank at its output limit now asks again with a larger output limit. The limit of the first call is `implement.reviewOutputTokens` (default 2048) tokens. The limit of the later calls is `implement.reviewRetryOutputTokens` (default 8192) tokens. A reasoning advisor model can need a larger `implement.reviewRetryOutputTokens`. Both values must stay within the capability of the model.
- After a failed attempt, the next attempt of a story starts from the branch of the failed attempt. The worker gets the problems of the review in a `### Review feedback` section. The setting `implement.retryFrom: source` restores the start from the source branch.
- A worker gets a new budget of model calls when it completes a todo, when an edit brings its workspace to a new state, and when its verification passes. The worker instructions tell it to call `task_done` after each task and to read files with the `read` and `grep` tools.

## 0.3.0 - 2026-11-06

### Added

**The agent loop and the context**

- Jeorje measures the context against a budget on every turn.
- A recent turn shrinks before the context drops it. Jeorje reports a dropped turn.
- The chat view shows how full the context is.
- Each turn becomes a chunk in the index. The chunk keeps the words of the user.
- Jeorje retries an empty model reply. A turn never ends without a message.
- Jeorje checks the input of every tool call before it runs the call.
- Jeorje treats a broken tool-call stream as an error and keeps every fragment.
- The advisor tool is available to every agent by default.

**Subagents and plans**

- You can watch the turns of a subagent while it runs.
- The status line shows a completion bar while a plan implements.
- Plan mode stops repeated reads and bounds its exploration.
- Jeorje reuses the plan file in plan mode.
- `/clear` clears the context. Steering sends a message to a running agent.

**The interface**

- The chat view has a working row. It shows the current work of the agent.
- The file browser opens a file in the editor.
- The file browser has a glob filter. It shows every path and ignores no path.
- `ask_user` shows a form with several questions. The form has keys, a mouse, and a cursor.
- You can run several sessions in one process. `/agents new` starts a new session.
- The agent switcher shows a tree of harnesses, agents, and subagents.
- The chat view shows the transcript while a turn runs.

**MCP servers**

- The config key `mcp.servers` defines an MCP server. Jeorje reads `.mcp.json` from the working directory.
- Jeorje has an MCP JSON-RPC client, an MCP manager, and MCP tools.
- `/mcp`, `--no-mcp`, and `--no-mcp-file` control the MCP servers.
- Prefix rules gate the MCP tools. The MCP tools work in plan mode.

**The swarm**

- Jeorje finds peers with no configuration. It uses local rendezvous discovery and publishes the SSH address.
- An SSH server with ephemeral keys and token authentication carries the agent traffic.
- Framed JSON moves over an SSH command channel.
- The tools `send_message` and `ask_peer` talk to a peer.
- You can bring a remote agent into the local foreground.
- The swarm is on by default.

**The shell and the models**

- A bang line runs one shell command from the prompt. `ShellRunner` runs the command to its end.
- The config key `llm.extraBody` adds fields to each request body.
- Jeorje has prompt caching and LLM endpoint configuration.
- For the host `openrouter.ai`, Jeorje asks for providers that support all parameters of the request.

**Install**

- `task install` links the launcher into `~/.local/bin`.

### Changed

- The word keys moved to `Alt`. The line ends moved to `Ctrl-Left` and `Ctrl-Right`.
- The system prompt tells the model to use the built-in edit tools instead of shell commands.
- The search tools skip build output.
- The search tools audit once for each call.
- The fallback pruner keeps negation words.
- Jeorje chunks the text of the user, the `ask_user` answers, and the large turns separately.
- Jeorje keeps the answers of the user to `ask_user` in every request.
- `plan_submit` checks the plan and writes it to the plan directory.

### Fixed

- Jeorje measures only the earlier context in the compaction triggers.
- The compaction keeps the words of the user in every pruned chunk.

[Unreleased]: https://github.com/lscoughlin/jeorje/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/lscoughlin/jeorje/releases/tag/v0.4.0
[0.3.0]: https://github.com/lscoughlin/jeorje/releases/tag/v0.3.0
