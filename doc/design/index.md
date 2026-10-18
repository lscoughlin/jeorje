---
title: Jeorje Design Overview
description: Entry point to the Jeorje design set. It gives the goals, the document map, and the glossary.
---

# Jeorje Design Overview

Jeorje is a terminal coding agent. It is written in Java 25.

Many Jeorje instances can form a swarm. Each instance runs an agent loop against an LLM. The agent uses tools to change a workspace. Each instance keeps a searchable index of its context history. The agent prunes its working context at token level all the time. The index keeps the full text, and keyword hints tell the agent how to get it back.

Some design choices have alternatives. [architecture.md](architecture.md#design-choices) lists these choices and the alternatives.

## Goals

- Run as one interactive agent or as a member of a swarm of peers.
- Keep the working context small with continuous compaction. Keep the full history in a searchable index.
- Let the user switch into any subagent and steer it directly.
- Turn a goal into a plan of stories and tasks, and implement the plan with a team of subagents. Each subagent sees only its own story.
- Give the agent a complete coding toolset with an explicit permission model.
- Run all tests and builds offline. No test needs a network or a real model.

## Non-goals

- A graphical or web interface. The interface is the terminal.
- Hosting or training of models. Jeorje is a client of an LLM provider.
- A central server. Swarm membership is peer to peer.

## Document map

| Document | Subject |
|----------|---------|
| [architecture.md](architecture.md) | Modules, dependency direction, threads, data flow, packaging |
| [agent-loop.md](agent-loop.md) | Turn loop, LLM client, messages, tool dispatch, retries |
| [context-and-compaction.md](context-and-compaction.md) | Turn chunks, token-level pruning, keyword hints, model roles, recall |
| [context-index-design.md](context-index-design.md) | Lucene schema, query modes, embeddings |
| [tools.md](tools.md) | Tool interface, registry, built-in tools |
| [permissions-and-safety.md](permissions-and-safety.md) | Permission policy, sandbox, audit log |
| [swarm.md](swarm.md) | Membership, gossip, messages, delegation, token, TLS, local discovery |
| [instructions-and-skills.md](instructions-and-skills.md) | `AGENTS.md` instructions, `.agents/` skills, custom commands, subagent definitions |
| [subagents.md](subagents.md) | Spawn, life cycle, foreground switching |
| [planning.md](planning.md) | `/plan` mode, plan files with stories and tasks, `/implement` with story-scoped workers, headless runs |
| [ui.md](ui.md) | Terminal interface, mouse, editor, syntax highlighting, key bindings, commands |
| [configuration.md](configuration.md) | Config files, precedence, storage, logging |
| [testing.md](testing.md) | Test strategy, stubs, offline builds |

A short overview of the index is in [../context-index.md](../context-index.md).

## Glossary

- **Instance**: one running Jeorje process.
- **Agent**: the loop in an instance that talks to the LLM and runs tools. One instance has one root agent and can have many subagents.
- **Turn**: one unit of conversation. It has one input, the model responses, and all tool calls and results.
- **Chunk**: the indexed form of a turn, or of a part of a long turn.
- **Working context**: the messages that the agent sends to the model in the next request.
- **History**: all turns that the session recorded. The index stores them.
- **Peer**: another instance in the swarm.
- **Skill**: a folder with a `SKILL.md` file that tells the agent how to do one kind of task. See [instructions-and-skills.md](instructions-and-skills.md#skills).
- **Plan, story, task, worker**: see [planning.md](planning.md#terms).
- **WKA list**: a list of well-known addresses of peers. The user gives it at startup to start membership.
