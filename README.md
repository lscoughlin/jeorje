# Jeorje

Jeorje is a coding agent written in Java, built to work in a swarm.

## Swarm

- Jeorje instances discover each other with a gossip protocol (SWIM style, over TCP).
- An instance can be given a well-known-address (WKA) list on startup.
- An agent can delegate a task to a subagent on a peer with `spawn_agent`.
- Subagents spawned by an instance are first-class: you can switch into one, at which point it becomes the foreground chat and can be steered directly.

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
| `todo`, `ask_user` | Keep a task list, ask the user a question |
| `spawn_agent`, `agent_status`, `agent_message`, `agent_ask`, `agent_answer` | Start subagents (local or on a peer), and let agents talk to each other |
| `task_done`, `story_done`, `story_blocked`, `story_read` | Story tools of the workers of `/implement` |
| `sync_done`, `sync_abort`, `worktree_merge`, `worktree_discard` | Resolve a sync, and manage the worktree of a subagent |
| `plan_submit` | Check a plan and write it (plan mode only) |
| `advisor` | Ask a second model for a review |

[doc/design/tools.md](doc/design/tools.md) describes each tool.

## Planning and Implementation

- `/plan <goal>` starts a read-only plan mode. The agent explores the project and writes a plan: a list of stories, each with tasks.
- `/implement <plan>` runs a team of subagents. Each subagent implements one story and sees only that story in its context. The orchestrator checks each story with its verification command and an optional advisor review.

[doc/design/planning.md](doc/design/planning.md) describes both commands.

In a git repository, each subagent works in its own git worktree and branch. Results are merged back with `/merge` or by the `/implement` orchestrator. See [doc/design/subagents.md](doc/design/subagents.md#worktrees).

## Instructions and Skills

Jeorje reads `AGENTS.md` files (user, project, and per directory) and the `.agents/` directory: skills (`.agents/skills/<name>/SKILL.md`), custom slash commands, and subagent definitions. See [doc/design/instructions-and-skills.md](doc/design/instructions-and-skills.md).

## Models

Jeorje uses the OpenAI-compatible API. The first target is OpenRouter, and the default model is `~deepseek/deepseek-flash-latest`. Three model roles are set in the config or with slash commands:

- `/model` sets the default model.
- `/compactor` sets the model that prunes the context.
- `/advisor` sets the model that the `advisor` tool calls.

## User Interface

The terminal UI is built on JLine and includes:

- a chat view for the foreground agent, with mouse-wheel and scroll-bar scrolling in every scrolling region (chat, editor, file browser, lists),
- syntax highlighting of code and diffs in the chat view and the editor,
- a small file browser,
- a modeless, menu-driven text editor in the style of the MS-DOS QBasic editor, built on JLine.

## Tech Stack

Java 25, Maven, Jackson 3, SLF4J with Logback, JLine 4, picocli, Apache Lucene (text and vector search), ONNX Runtime (local embeddings), java-diff-utils (diff and patch), and JUnit for tests. The parent `pom.xml` pins all versions.

## Project Conventions

Contributor and agent conventions are in [AGENTS.md](AGENTS.md).
