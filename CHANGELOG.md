# Changelog

This file records the notable changes to Jeorje. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Release `0.3.0` is the first tagged release. The earlier `0.1.0-SNAPSHOT` and `0.2.0-SNAPSHOT` lines were development lines. Jeorje never released them. Release `0.3.0` contains their work.

## Unreleased

This is the current development line. The version in the build is `0.4.0-SNAPSHOT`. No change is ready for the next release yet.

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

[Unreleased]: https://github.com/lscoughlin/jeorje/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/lscoughlin/jeorje/releases/tag/v0.3.0
