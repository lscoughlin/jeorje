---
title: Architecture
description: Maven modules, dependency direction, thread model, data flow, packaging, and the list of design choices.
---

# Architecture

## Modules

Jeorje is a multi-module Maven project. The base package is `org.github.lscoughlin.jeorje`. Each module has a sub-package with its short name, for example `org.github.lscoughlin.jeorje.core`.

| Module | Responsibility | Depends on |
|--------|----------------|------------|
| `jeorje-core` | Domain model, ports, agent loop, compaction, config, sessions, subagent runtime, plan mode, plan parser and writer, `/implement` orchestrator, `WorktreeManager`, the loaders for `AGENTS.md` and `.agents/`, and the `TextDiff` facade | Jackson 3 (JSON and YAML), SLF4J, java-diff-utils |
| `jeorje-index` | Lucene `ContextStore` and ONNX MiniLM embedder | core, Lucene, ONNX Runtime |
| `jeorje-tools` | Built-in `Tool` implementations (including the agent, story, plan, and worktree tools) and the registry | core |
| `jeorje-swarm` | Gossip membership, TCP transport with an optional TLS context, token handshake, peer messages, zeroconf (mDNS) discovery, remote delegation messages | core, JmDNS |
| `jeorje-llm` | `LlmClient` implementations. The first one uses the OpenAI-compatible Chat Completions API. The first target is OpenRouter. | core |
| `jeorje-ui` | JLine terminal interface, file browser, editor, syntax highlighting, mouse and key handling | core, JLine |
| `jeorje-testkit` | Test-scope stubs for each port. See [testing.md](testing.md). | core |
| `jeorje-cli` | picocli entry point (`JeorjeMain`) with the commands `run`, `resume`, `index`, and `config`, wiring, the interactive session, the headless `/plan` and `/implement` runs (`HeadlessWork`), the swarm bridge (`SwarmBridge`) with TLS and discovery setup, shaded jar | all modules except testkit, picocli, Logback |

Dependencies point inward. `jeorje-core` depends on no other module. Each other module depends on `jeorje-core`. Only `jeorje-cli` depends on all production modules. Other modules use `jeorje-testkit` in test scope only. The shaded jar does not include it. The parent `pom.xml` is the source of truth for all library versions. `jeorje-core` has no Lucene, JLine, or HTTP types. `java-diff-utils` is a small library with no dependencies, and only `TextDiff` uses it.

```
cli -> ui, tools, swarm, llm, index -> core
(test scope) any module -> testkit -> core
```

## Ports

Each external concern has an interface in `jeorje-core`:

- `LlmClient` streams a model response for a message list and tool definitions.
- `ContextStore` adds turns, searches history, and returns chunks by id.
- `Tool` is a named capability with a JSON schema and an `execute` method.
- `PermissionPolicy` decides allow, ask, or deny for an action.
- `Transport` sends and receives swarm messages.
- `Embedder` converts text to a vector of fixed size.

Each test can replace a port with a stub. See [testing.md](testing.md).

## Thread model

- Jeorje uses Java 25 virtual threads for most blocking work. This includes LLM streaming, tool runs, file I/O, and gossip connections.
- Each agent runs its loop in one virtual thread. A subagent loop runs in a virtual thread that the subagent runtime starts. A watchdog thread of the runtime enforces the time limit of each subagent.
- The UI thread never blocks on an agent. It reads an event queue.
- One writer thread owns the Lucene `IndexWriter` for each session. Any thread can search with a `SearcherManager` snapshot.
- One owner thread holds the swarm membership table. Callbacks from the swarm to the agents run on one serial executor, in message order.
- The git calls for worktrees run under one lock for each repository. A second lock for each source branch (the integration lock) serializes the merges back. See [subagents.md](subagents.md#git-operations).
- Cancellation uses thread interruption and a `CancellationToken`. The agent passes the token to tools and to the LLM client.
- One owner holds each item of shared mutable state: an agent, the writer, or the membership table. Other threads use messages or immutable snapshots. The agent owns its file-state table and its directory-context set. Tools return updates, and the agent loop applies them.

## Data flow

1. The user types input in the UI. The UI sends a `UserInput` event to the foreground agent.
2. The agent adds the input as a turn. It builds the working context. It calls `LlmClient`.
3. Streamed tokens return to the UI as `AgentEvent` objects.
4. `PermissionPolicy` checks each tool call. The agent runs the allowed calls and adds the results to the turn.
5. When the turn ends, the agent writes it to the `ContextStore`. Compaction can start.
6. Peer messages and subagent results arrive as events in the agent queue.

## Packaging

- `jeorje-cli` builds one shaded jar. Run it with `java -jar jeorje.jar`.
- The jar includes the ONNX Runtime native libraries and the embedded syntax files of the editor (`jeorje-ui`).
- The embedding model downloads one time at runtime. Jeorje caches it.
- Worktree support needs the `git` program, version 2.17 or newer, on the path. Jeorje uses it only for worktrees and merges. Without it, subagents share the working directory.
- Tests run with `--add-modules jdk.incubator.vector` when the JDK has the module (Maven profile `jdk-vector`). Lucene then uses its SIMD path. The jar does not add the module by itself. To use the SIMD path at run time, start the program with `java --add-modules jdk.incubator.vector -jar jeorje.jar`.

## Design choices

| Choice | Default | Alternatives |
|--------|---------|--------------|
| LLM provider | OpenAI-compatible Chat Completions API. First target: OpenRouter. | Native provider APIs behind the same port |
| Gossip transport | TCP with JSON messages (SWIM style) | UDP, gRPC |
| Module layout | Eight modules (seven production modules and `jeorje-testkit`) | One module |
| Permission model | Allow, ask, or deny per tool, with a path sandbox | Container sandbox |
| Index location | `${CWD}/.jeorje/sessions/<id>/index` (project directory) | `${HOME}/.jeorje/` |
| Embedding model | `all-MiniLM-L6-v2`, 384 dimensions | Larger models |
| Base package | `org.github.lscoughlin.jeorje` | Any other name |
