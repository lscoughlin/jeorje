---
title: Usage
description: How to start Jeorje and use it - the first run, the chat, shell lines, permissions, planning, subagents, sessions, the swarm, MCP servers, and headless runs.
---

# Usage

This page shows how to use Jeorje. Each section has the commands that you type. The design files in [design/index.md](design/index.md) give the details.

## Start

1. Install Java 25.
2. Set the API key of your model provider. The default is OpenRouter: `export OPENROUTER_API_KEY=...`.
3. Go to your project directory and run `jeorje`.

Jeorje opens the interactive interface in the current directory. Use `--dir <path>` to open another directory. Use `--model <slug>` to choose a model for this run.

To write a starting config file, run `jeorje config --init`. To see the effective config, run `jeorje config`. See [design/configuration.md](design/configuration.md).

## Talk to the agent

Type a task in the input line and press `Enter`. The agent reads files, changes files, and runs tools. The chat view shows its work as it happens.

| Key | Action |
|-----|--------|
| `Enter` | Send the input |
| `Alt-Enter` | Add a new line to the input |
| `Ctrl-C` | Interrupt the agent. Press it twice within 2 s to exit. |
| `Ctrl-X o` | Expand or collapse the last tool result |
| `Ctrl-X a` | Open the agent switcher |
| `Ctrl-X f` | Open the file browser |

Type `/help` to see all commands. Type `/keys` to see all key bindings. The full list is in [design/ui.md](design/ui.md#key-bindings).

## Mention files

A word that starts with `@` and names a path adds that file to your next message. For example, `explain @src/Main.java`. Press `Tab` to complete the names of commands, and the paths of `/edit`, `/files list`, and `/implement`.

## Run shell commands

A line that starts with `!` runs a shell command in the working directory. The chat view shows the command, the output, and the exit code. The agent sees the same text in its next turn. The line does not start a turn.

```
!git status
```

## Approve actions

The agent asks before it does a risky action. A prompt offers these choices:

| Key | Choice |
|-----|--------|
| `y` | Allow once |
| `s` | Allow for the session |
| `p` | Allow in the project |
| `n` | Deny |

Set `permissions.rules` in the config to allow or deny tools without a prompt. Two flags change the checks for one run:

- `--yes` approves all prompts. The deny rules and the path sandbox still apply.
- `--yolo` turns off the permission checks. Use it only in a safe environment.

See [design/permissions-and-safety.md](design/permissions-and-safety.md).

## Plan, then implement

1. Type `/plan <goal>`. The agent explores the project. It cannot write files in this mode. It writes a plan: a list of stories, each with tasks.
2. Read the plan with `/plan show`. Change it with `/plan edit`.
3. Type `/plan done` to keep the plan.
4. Type `/implement` to start a team of subagents. Each subagent builds one story in its own git worktree. The orchestrator checks each story with its verification command.

Use `/implement status`, `/implement pause`, `/implement resume`, and `/implement stop` to control the run. See [design/planning.md](design/planning.md).

## Work with subagents and worktrees

- The agent starts a subagent with `spawn_agent`. A subagent in a git repository gets its own worktree and branch.
- Type `/agents` to list the agents. Type `/agents <name>` to switch into one. Type `/back` to return to the parent.
- Type `/cancel [name]` to stop an agent.
- Type `/worktrees` to list the worktrees. Type `/merge <agent>` to merge the work of an agent. Type `/discard <agent>` to remove it.

See [design/subagents.md](design/subagents.md).

## Use several sessions

Type `/agents new [name]` to move the current conversation to the background and start a new one. The old conversation keeps running. Open the agent switcher with `Ctrl-X a` to go back to it.

To continue an earlier session, run `jeorje resume` or `jeorje --resume`.

## Manage the context

Jeorje prunes the context by itself. You can look at it and change it:

- `/context` shows how full the context is.
- `/compact` runs compaction now.
- `/pin` pins the last assistant message, so it stays in the context.
- `/recall <query>` searches the full history.
- `/compactor [slug]` sets the model that prunes the context.

See [design/context-and-compaction.md](design/context-and-compaction.md).

## Choose models

- `/model [slug]` sets the default model.
- `/advisor [slug|none]` sets the model that reviews the work, or turns the review off.
- `/compactor [slug|off]` sets the model that prunes the context.

## Add instructions and skills

- Write project rules in `AGENTS.md`. Type `/init` to ask the agent to write one.
- Put skills in `.agents/skills/<name>/SKILL.md`. Type `/<skill-name>` to start one.
- Put custom commands and subagent definitions in `.agents/`.
- Type `/trust` to trust the project config of the directory.

See [design/instructions-and-skills.md](design/instructions-and-skills.md).

## Add MCP servers

Add servers to the `mcp.servers` key of the config. Jeorje also reads a `.mcp.json` file in the project directory. Use `--no-mcp-file` to ignore that file, and `--no-mcp` to turn off all MCP servers. Type `/mcp` to see the servers and their tools. See [design/configuration.md](design/configuration.md#mcp-servers).

## Use the swarm

The swarm is on by default. Instances on the same computer find each other. To join instances on other computers:

```
jeorje --swarm-token <secret> --peer host:port
```

Give the same token to every instance. Zeroconf (`swarm.discovery`) finds instances on the same network.

- Type `/peers` to list the peers.
- An agent starts a subagent on a peer with `spawn_agent`. Agents on different instances send messages with `send_message` and ask questions with `ask_peer`.
- Open the agent switcher to see each instance and its agents as a tree. Switch into an agent on a peer to watch it and steer it.
- Use `--no-swarm` to turn the swarm off.

See [design/swarm.md](design/swarm.md).

## Run with no interface

```
jeorje run "fix the failing test in the cache module"
jeorje run "/plan add rate limiting to the API"
jeorje run "/implement"
jeorje run --plan docs/rate-limit.md
jeorje resume --task "continue with the docs"
```

A headless run prints the final answer. Add `--yes` to approve prompts, because nobody is there to answer them. See [design/planning.md](design/planning.md#headless-runs).

## Edit files

Type `/edit <path>` to open a file in the built-in editor. The editor has menus, syntax colors, find and replace, and a split window. Press `F1` for its keys. See [design/ui.md](design/ui.md#editor).

## Command line summary

| Command | Purpose |
|---------|---------|
| `jeorje` | Open the interface |
| `jeorje run "<task>"` | Run one task with no interface |
| `jeorje resume [session]` | Open an earlier session |
| `jeorje index rebuild` | Build the history index again |
| `jeorje config` | Show or write the config |

Common options: `--dir`, `--model`, `--config`, `--peer`, `--swarm-token`, `--no-swarm`, `--no-mcp`, `--no-mcp-file`, `--no-mouse`, `--yes`, `--yolo`, and `--trust`.
