# Jeorje

Jeorje is a coding agent for the terminal, written in Java 25. It works alone or in a swarm of instances on your network. It keeps a searchable index of everything it did, so it can prune its working context and still find old details when it needs them.

See [doc/usage.md](doc/usage.md) for a guide to daily use.

## Why Use Jeorje

- **Long tasks stay on track.** The context stays small because Jeorje prunes it all the time. The full history stays in an index. The agent can get any pruned detail back.
- **One tool for many machines.** Instances find each other, send messages, and share work. You can watch or steer an agent that runs on another computer.
- **Plans become code in a controlled way.** A plan is a list of stories. A team of subagents builds the stories in separate git worktrees. Each story has a check that must pass.
- **You see and control the work.** Every agent is visible. You can switch into any agent and steer it. Permission rules decide what an agent may do.
- **It runs on the JVM.** One jar, Java 25, and any OpenAI-compatible model. Tests run offline.

## Unique Features

- **Queryable context memory.** Each turn becomes a chunk in a Lucene index with text and vector search. Old chunks shrink, and each one keeps search keywords. The `recall` tool restores the full text.
- **A swarm with no server.** Instances find each other with a gossip protocol, zeroconf, a local rendezvous, or a list of addresses. Agents message each other and ask each other questions over SSH. A shared token protects the swarm.
- **Remote foreground.** Attach to an agent on a peer. Its chat shows in your terminal. You can steer it and answer its permission requests.
- **Agent tree.** The [agent switcher](doc/agent-switcher.md) shows each instance, its agents, and their subagents as a tree you can collapse.
- **Sessions in the background.** `/agents new` starts a fresh conversation. The old one keeps running, and you can switch back at any time.
- **Plan, then implement.** `/plan` explores the project in read-only mode. `/implement` gives each story to its own subagent, and the orchestrator checks the result.
- **An advisor for every agent.** A second model reviews the work. It is on by default.
- **Bang commands.** A line that starts with `!` runs a shell command. The output shows in the chat, and the agent sees it on its next turn.
- **Project MCP servers.** Jeorje reads `.mcp.json` from the project, in addition to its own config.
- **A built-in editor and file browser.** A modeless text editor in the style of the MS-DOS QBasic editor, and a file browser with `@path` mentions.

## Swarm

- Jeorje instances discover each other with a gossip protocol (SWIM style, over TCP).
- The swarm is on by default and uses a free port. `--no-swarm` turns it off, and `--swarm-bind` sets the address.
- Instances on one computer find each other with a local rendezvous. Instances on one network can find each other with zeroconf (mDNS) discovery (`swarm.discovery`). An instance can also be given a well-known-address (WKA) list on startup (`--peer host:port`).
- A shared token (`--swarm-token` or `JEORJE_SWARM_TOKEN`) protects the swarm. TLS is optional (`swarm.tls.keyStore`).
- Agent messages and questions between instances use SSH, with the token as the password.
- An agent can delegate a task to a subagent on a peer with `spawn_agent`.
- Agents can send messages to agents on peers (`send_message`) and ask them questions (`ask_peer`).
- Subagents are first-class. You can switch into one, and it becomes the foreground chat. You can also attach to an agent on a peer.

## Context Memory

Jeorje keeps a full index of its context history, chunked by turn, and can query it at any time. This lets it:

- prune its working context at token level almost continuously,
- ask itself questions about what it is doing,
- retrieve fuller pieces of earlier context on demand, with the search keywords that each pruned chunk keeps.

This approach is inspired by the Clarity project.

The index is built on Apache Lucene and supports text, vector, and hybrid search over turn chunks. Details are in [doc/context-index.md](doc/context-index.md).

The full design is in [doc/design/index.md](doc/design/index.md).

## Built-in Tools

| Tool | Purpose |
|------|---------|
| `read` | Read files. It also returns front matter and `README.md`, `llms.txt`, and `llm.txt` context. |
| `write`, `edit` | Create and change files |
| `diff`, `patch` | Compare files, and apply a unified diff to many files at once |
| `ls`, `glob`, `grep` | Find files and search their content |
| `bash` | Run shell commands |
| `webfetch` | Fetch content from the web |
| `recall` | Search the context history of the agent |
| `todo`, `ask_user` | Keep a task list, ask the user a form of single choice, multiple choice, and text questions |
| `spawn_agent`, `agent_status`, `agent_message`, `agent_ask`, `agent_answer` | Start subagents (local or on a peer), and let agents talk to each other |
| `send_message`, `ask_peer` | Reach agents on other instances of the swarm |
| `task_done`, `story_done`, `story_blocked`, `story_read` | Story tools of the workers of `/implement` |
| `sync_done`, `sync_abort`, `worktree_merge`, `worktree_discard` | Resolve a sync, and manage the worktree of a subagent |
| `plan_submit` | Check a plan and write it (plan mode only) |
| `skill` | Load a skill from `.agents/skills/` |
| `advisor` | Ask a second model for a review (on by default, for every agent) |

[doc/design/tools.md](doc/design/tools.md) describes each tool.

## Planning and Implementation

- `/plan <goal>` starts plan mode. The agent cannot write or edit files. It explores the project, with `bash` under the normal permission rules, and writes a plan: a list of stories, each with tasks.
- `/implement <plan>` runs a team of subagents. Each subagent implements one story and sees only that story in its context. The orchestrator checks each story with its verification command and an optional advisor review.

[doc/design/planning.md](doc/design/planning.md) describes both commands.

Both commands also run with no interface: `jeorje run "/plan <goal>"`, `jeorje run "/implement <plan>"`, and `jeorje run --plan <path>`. See [Headless runs](doc/design/planning.md#headless-runs).

In a git repository, each subagent works in its own git worktree and branch. Results are merged back with `/merge` or by the `/implement` orchestrator. See [doc/design/subagents.md](doc/design/subagents.md#worktrees).

## Instructions and Skills

Jeorje reads `AGENTS.md` files (user, project, and per directory) and the `.agents/` directory: skills (`.agents/skills/<name>/SKILL.md`), custom slash commands, and subagent definitions. See [doc/design/instructions-and-skills.md](doc/design/instructions-and-skills.md).

## Running

- `jeorje` opens the interactive interface. `jeorje resume` and `jeorje --resume` open an earlier session.
- `jeorje run "<task>"` runs one task with no interface and prints the final answer. `jeorje resume --task "<text>"` continues a session the same way.
- `jeorje index rebuild` embeds the stored history again. `jeorje config` shows the effective config.
- Useful options: `--dir`, `--model`, `--peer`, `--swarm-token`, `--no-swarm`, `--no-mcp-file`, `--yes`, `--yolo`, `--trust`, `--config`, `--no-mcp`, and `--no-mouse`.

## Models

Jeorje uses the OpenAI-compatible API. The first target is OpenRouter, and the default model is `~deepseek/deepseek-flash-latest`. Three model roles are set in the config or with slash commands:

- `/model` sets the default model.
- `/compactor` sets the model that prunes the context.
- `/context` shows how full the context is: the tokens of each part of the last request and the share of the budget.
- `/advisor` sets the model that the `advisor` tool calls. The advisor is on by default with `~deepseek/deepseek-pro-latest`, and every agent has the tool. With no slug, the command sets the default again. `/advisor none` turns the advisor off.

## User Interface

The terminal UI is built on JLine and includes:

- a chat view for the foreground agent, with mouse-wheel and scroll-bar scrolling in every scrolling region (chat, editor, file browser, lists),
- syntax highlighting of code and diffs in the chat view and the editor,
- a file browser app in the chat region, with arrow-key movement, `Right` to open a directory, `Left` to close it or to move to the parent, `Enter` to edit a file or to expand a directory, and a keys row; `Ctrl-X f` and `/files` open it, and `/files list [filter]` prints the tree,
- an `@path` mention: a word that starts with `@` and names a path in the working directory adds the file to the next message, with a `Files: <paths>` line,
- a modeless, menu-driven text editor in the style of the MS-DOS QBasic editor, built on JLine. See [doc/editor.jpg](doc/editor.jpg) for a screenshot.

## Tech Stack

Java 25, Maven, Jackson 3, SLF4J with Logback, JLine 4, picocli, Apache Lucene (text and vector search), ONNX Runtime (local embeddings), JmDNS (zeroconf), Apache MINA SSHD (agent traffic), java-diff-utils (diff and patch), and JUnit for tests. The parent `pom.xml` pins all versions.

## Project Conventions

Contributor and agent conventions are in [AGENTS.md](AGENTS.md).
