---
title: Agent Switcher
description: How to open the agent switcher, read its tree of instances, agents, and subagents, and switch the foreground agent.
---

# Agent Switcher

The agent switcher shows every agent that you can reach. You use it to move the foreground agent. It is a pane that replaces the chat view while it is open.

- Press `Ctrl-X a` to open the switcher. Press `Esc` to close it.
- Type `/agents` to print the same tree in the chat view. Type `/agents <name or number>` to switch to a local agent.

The pane has a tree with three levels:

- The **instance** is one Jeorje process. The first entry is this instance. Each live peer of the swarm is one more entry.
- The **agent** is an agent of an instance. The main agent of an instance is an agent.
- The **subagent** is a subagent of an agent. A subagent can have subagents of its own. Each level adds one indent.

A node with children shows `-` when it is open and `+` when it is collapsed. A row shows the name, and the state after a bar. A session that runs a turn shows `RUNNING`. A session that waits shows `IDLE`. A session that has closed shows `ENDED`.

## Keys

| Key | Action |
|-----|--------|
| `Up`, `Down` (or `k`, `j`) | Select a row |
| `Left` (or `h`) | Collapse the selected node |
| `Right` (or `l`) | Expand the selected node |
| `Enter` | Make the selected agent the foreground agent |
| `Esc` | Close the pane |

A click on a row selects it. A double click runs `Enter` on the row. A click on a node of an instance does nothing.

## Foreground agent

`Enter` on a local agent or a subagent makes it the foreground agent. The chat view then shows its history, and the text that you type goes to it. Type `/back` to return to the parent agent.

`Enter` on an agent of a peer attaches the foreground to the remote agent. The chat view shows the history of that agent and its new lines. Text that you type steers the remote agent. When the remote agent asks for a permission, the chat view shows a line with the request id. Type `yes` or `no` to answer the last request.

An agent of a peer can be slow to show. Jeorje reads the roster of each peer in the background and keeps it for a few seconds.

## Sessions

One process can hold more than one session. A session is one conversation, with its own main agent, its own subagents, and its own context index. Each live session has one top-level entry in the switcher.

- Type `/agents new [name]` to move the current session to the background and to start a new session in the foreground.
- The background session keeps its agent and does not stop its turn.
- Use the switcher or `/agents <name or number>` to go back to it.

The status line, the typed text, and the permission prompts follow the foreground session. A permission prompt of a background session stays in the queue until you switch to that session.

## See also

- [design/ui.md](design/ui.md#agent-switcher) describes the pane and its layout.
- [design/ui.md](design/ui.md#sessions-in-the-switcher) describes the sessions.
- [usage.md](usage.md) gives the keys and the commands of daily use.
- [agent-switcher.webm](agent-switcher.webm) is a short recording of the switcher.
