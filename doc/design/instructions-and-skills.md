---
title: Instructions and Skills
description: How Jeorje loads AGENTS.md instructions and the .agents directory - skills, custom commands, and subagent definitions.
---

# Instructions and Skills

Jeorje reads project instructions from `AGENTS.md` files. It reads reusable capabilities from the `.agents/` directory: **skills**, **custom commands**, and **subagent definitions**. The loaders are in `jeorje-core` (`InstructionsLoader`, `SkillRegistry`, `CommandRegistry`, `AgentTypeRegistry`, `TrustStore`). The `skill` tool is in `jeorje-tools`. `jeorje-cli` connects them. Parts that the launcher does not connect yet are marked in the text.

## AGENTS.md

`AGENTS.md` is a plain markdown file with instructions for agents. It has no required fields and no frontmatter. Jeorje follows the common `AGENTS.md` convention (the closest file to the edited file wins) and passes the text to the model as it is. The user-level locations and the `/init` command are Jeorje additions.

### Where Jeorje looks

| Level | File | Use |
|-------|------|-----|
| User | `${HOME}/.jeorje/AGENTS.md`, then `${HOME}/.agents/AGENTS.md` | Instructions for all projects |
| Project | `AGENTS.md` in the workspace root | Instructions for the project |
| Directory | `AGENTS.md` in any subdirectory of the workspace | Instructions for the files in that directory and below |

The workspace root is the working directory of the agent. A subagent in a git worktree uses the root of its worktree. See [subagents.md](subagents.md#worktrees). The config key `instructions.userFiles` sets the user files. The key `instructions.enabled: false` turns off all `AGENTS.md` instructions.

The launcher uses the user files and the project file. `InstructionsLoader.directoryFiles` returns the directory files, but no tool result carries them yet.

### How Jeorje uses them

- **System prompt.** When a session starts, Jeorje adds the user files and the project file to the system prompt, in the section `Instructions`. Each file has a title line that gives its path and level (`user` or `project`). The project file comes after the user files, so the project instructions are the more specific. See [agent-loop.md](agent-loop.md#system-prompt).
- **Directory files.** A nested `AGENTS.md` is not in the system prompt. `InstructionsLoader.directoryFiles` returns the files on the path from the workspace root to a directory. The root file is not repeated, and the closest file is last. No tool result carries these files yet.
- **Precedence.** The order in the system prompt gives the precedence: a project file is after a user file. The direct request of the user wins over all files.
- **Size.** Each file is cut at `instructions.maxBytes` (default 32,768 bytes). A cut file ends with a note. The total of the system-prompt part is also limited by `instructions.maxTotalBytes` (default 65,536). When the total limit is reached, Jeorje cuts the text and adds a note.
- **Compaction.** The `AGENTS.md` text is part of the system prompt, not of the history, so compaction never prunes it.
- **Reload.** `InstructionsLoader` checks the size and the modification time of each file when it reads it. The launcher reads the files when the session starts, so an edit takes effect at the next start.
- **Subagents.** All subagents, including the workers of `/implement`, get the same system-prompt part. This does not break the story-only context rule: instructions are part of the system prompt, not of the memory context. See [planning.md](planning.md#context-rule).
- **Not executed.** Jeorje treats the file as text for the model. It does not run commands from it. Every action still goes through the permission policy.

The `/init` slash command sends the agent a prompt to write an `AGENTS.md` for the project root. The agent reads the build files and the main source folders first. The agent writes the file with the `write` tool, so the permission policy decides if the user must approve.

## The .agents directory

`.agents/skills/` is the cross-client convention for skills. Jeorje also reads its own locations, and it uses `.agents/` for two Jeorje features: custom commands and subagent definitions. The Agent Skills specification defines only skills. Commands and subagent definitions are Jeorje additions, and other tools do not read them.

```
${CWD}/.agents/ and ${HOME}/.agents/         # cross-client locations
  skills/<name>/SKILL.md                     # skills (Agent Skills layout)
  skills/<name>/scripts/ references/ assets/ # optional files of a skill
  commands/<name>.md                         # custom slash commands (Jeorje)
  agents/<name>.md                           # subagent definitions (Jeorje)
${CWD}/.jeorje/ and ${HOME}/.jeorje/         # Jeorje locations, same layout
  skills/ commands/ agents/
```

**Scopes and order.** Jeorje scans these skill directories, from the highest to the lowest precedence:

1. `${CWD}/.jeorje/skills/` (project, Jeorje)
2. `${CWD}/.agents/skills/` (project, cross-client)
3. `${HOME}/.jeorje/skills/` (user, Jeorje)
4. `${HOME}/.agents/skills/` (user, cross-client)
5. The `skills/` folder in each directory of `skills.dirs` and `agents.dirs`
6. The directories in `skills.compat`

There are no built-in items. The same order applies to `commands/` and `agents/`, except for step 6. Each directory in `skills.dirs` and `agents.dirs` has the sub-folders `skills/`, `commands/`, and `agents/`. The key `skills.compat` lists skill directories of other tools, for example `${HOME}/.claude/skills`. Jeorje uses each entry as a skills directory as it is. It does not expand `~` or `${HOME}` in this key. A relative entry starts at the working directory of the process. The key is empty by default.

**Collisions.** When two items have the same name, the item that Jeorje finds first (in the order above) wins. In one root, Jeorje reads the paths in sorted order. Jeorje logs a warning that names the file that it skipped, and `/skills`, `/commands`, and `/agent-types` show it.

**Scanning rules.** For skills, Jeorje looks for subdirectories that have a file named exactly `SKILL.md`. When a folder has a `SKILL.md`, Jeorje does not look inside that folder for more skills, so a `SKILL.md` in `assets/` or `references/` is not a separate skill. Jeorje skips `.git/` and `node_modules/`. It does not read `.gitignore`. It goes at most 6 levels deep and looks at most at 2,000 directories for each root. For commands and subagent definitions, Jeorje reads the `.md` files below the root, to the same depth, up to 2,000 files, and skips the same folders. Other files are ignored. Missing directories are not an error.

**Lenient loading.** Jeorje loads an item when it can and records diagnostics (see each section). `/skills`, `/commands`, and `/agent-types` show the diagnostics.

## Trust

Project items come from the repository, which can be untrusted, for example a repository that the user just cloned. A project could put instructions into the agent without the user seeing them.

- **Gated items.** Jeorje loads the project-level skills, commands, and subagent definitions (the directories `${CWD}/.agents/` and `${CWD}/.jeorje/skills|commands|agents/`) only if the user trusts the project. User-level items load always.
- **Trust prompt.** When the interactive interface starts in an untrusted project that has such items, Jeorje shows a notice and asks the user to trust the project. If the user agrees, Jeorje reads the config again, now with the project config, and loads the items. `/trust` trusts the project later, and `/trust revoke` removes the trust. Both commands change `${HOME}/.jeorje/trust.yaml`, by the real path of the project. After `/trust`, Jeorje loads the skills and the commands at once. The `skill` tool, the project config, and the types of subagents apply from the next start. After `/trust revoke`, the `skill` tool and the types of subagents change at the next start.
- **Trust at startup.** At startup, Jeorje takes the trust from the flag `--trust` or from `trust.projects`. It does not read `trust.yaml` at startup. To keep the trust for later runs, add the real path of the project to `trust.projects` in the user config.
- **No trust, no load.** If the user does not trust the project, Jeorje does not load the items. It shows one notice. The `AGENTS.md` files are not gated, because they are only text for the model and every `AGENTS.md` tool loads them. The permission policy still applies to all actions.
- **Non-interactive use.** `jeorje run` does not ask. It loads project items only if the project is trusted by the flag `--trust` or by `trust.projects`. The flags `--yes` and `--yolo` do not set trust.
- **Config.** The trust list is read from the user config and from the file given with `--config` (`trust.projects`). A project config cannot trust its own project. Jeorje ignores the key there and logs a warning.
- **Project config of an untrusted project.** The file `${CWD}/.jeorje/config.yaml` is committed with the repository, so the same risk applies. For a project that is not trusted, Jeorje reads the project config in **restricted mode**: the project config can only make settings stricter. It can add `ASK` and `DENY` rules, lower limits, and turn features off. Jeorje ignores these keys in restricted mode and logs a warning for each one: `permissions.rules` entries with `allow`, `permissions.extraDirectories`, `permissions.webfetch.allow`, `tools.extensions`, `tools.bash.shell`, `prompts.*`, `skills.dirs`, `skills.compat`, `skills.honorAllowedTools`, `agents.dirs`, `editor.syntax.dirs`, `swarm.*`, `llm.baseUrl` and `llm.headers`, and `models.*`. After the user trusts the project, Jeorje reads the project config in full. A rule with `ASK` or `DENY` stays. See [configuration.md](configuration.md#locations).
- **Worktrees.** `TrustStore` maps a git worktree to its main project, so `/trust` in a worktree trusts the main project. Jeorje scans the skills, commands, and subagent definitions once for the session, from the project directory of the session. A subagent uses these registries. A subagent reads `AGENTS.md` files from its worktree.
- **Loading does not run anything.** Scripts and commands run only through `bash` and the permission policy.

## Skills

A skill is a folder with a `SKILL.md` file. The file tells the agent how to do one kind of task. Jeorje follows the Agent Skills specification (agentskills.io).

### SKILL.md format

```markdown
---
name: release-notes
description: Write release notes from the git log. Use when the user asks for a changelog or release notes.
license: MIT
metadata:
  author: example
---

# Release notes

1. Run `scripts/log.sh <from> <to>` to get the commits.
2. Group the commits by type.
...
```

The file has YAML frontmatter between two `---` lines, and a markdown body.

| Field | Required | Rule in the specification |
|-------|----------|---------------------------|
| `name` | yes | 1 to 64 characters. Lowercase letters and digits and hyphens only. It does not start or end with a hyphen and has no two hyphens in a row. It matches the name of the folder. |
| `description` | yes | 1 to 1,024 characters. It says what the skill does and when to use it. The model decides from it. |
| `license` | no | A license name, or the name of a bundled license file |
| `compatibility` | no | At most 500 characters. It states the environment that the skill needs. |
| `metadata` | no | A map of string keys to string values |
| `allowed-tools` | no | A space-separated string of tools that are pre-approved. This field is experimental. |

**Jeorje additions.** The key `disable-model-invocation: true` hides the skill from the model: it is not in the list, and only the user can start it with `/<name>`.

**Lenient validation.** The specification is strict. Jeorje is lenient so that skills written for other tools work:

- A `name` that does not match the folder, has a bad character, or is longer than 64 characters: load the skill with the `name` from the frontmatter, and record a warning.
- A `name` that is missing: load the skill with the name of the folder, and record a warning.
- A `description` that is missing or empty: skip the skill and record an error.
- A `description` of more than 1,024 characters, or a `compatibility` text of more than 500 characters: load the skill and record a warning.
- A YAML error, most often an unquoted value that has a colon (`description: Use when: the user ...`): Jeorje wraps the value in quotes and tries again. If YAML still fails, it skips the skill and records an error.
- A body of more than 500 lines or 5,000 tokens: load it and record a warning (the specification recommends smaller skills and files that the skill loads on demand).

**`allowed-tools`.** Jeorje keeps the field. It does not give a skill more permission than the agent has. The permission policy decides all tool runs. The key `skills.honorAllowedTools` is for an opt-in, but it has no effect yet: no code gives a skill extra permission. The key is also one of the keys that restricted mode ignores. The field is a space-separated string, for example `Bash(git:*) Bash(jq:*) Read`. `AllowedTools.parse` reads it like this:

- A tool name is not case sensitive. `Bash` is `bash`, `Read` is `read`, `Edit` is `edit`, and so on.
- `Tool(pattern)` limits the tool to commands or paths that match the pattern. For `bash`, `git:*` is the prefix rule `git ` (any arguments). Other patterns are glob patterns on the main argument of the tool (the path for file tools).
- A name that is not a tool of the default set (`read`, `write`, `edit`, `bash`, `grep`, `glob`, `ls`, `webfetch`, `websearch`, `skill`, `spawn_agent`, `patch`) is ignored. `SkillRegistry.allowedToolsDiagnostics` reports the name, but `/skills` does not show this report yet.

### Progressive loading

1. **Catalog.** `SkillCatalog.build` makes a catalog text with one entry for each skill: the `name`, the `description`, and the `location` (the absolute path of `SKILL.md`). A short instruction block comes with it:

   ```
   The following skills give instructions for specific tasks. When a task matches
   the description of a skill, call the skill tool with its name before you
   continue. Relative paths in a skill are relative to the folder of its SKILL.md.
   ```

   The catalog is cut at `skills.maxListChars` (default 8,000). Skills that do not fit are not listed. The launcher does not add the catalog to the system prompt yet, and `skills.maxListChars` has no effect yet. The model learns the skill names from the `enum` in the schema of the `skill` tool. Skills that are hidden by `disable-model-invocation: true` are not in the `enum`. If no skill is available, Jeorje does not register the `skill` tool. The key `skills.enabled` has no effect yet.
2. **Instructions.** The model calls the `skill` tool, or the user types `/<name>`. The result has the body of `SKILL.md`.
3. **Resources.** The model reads other files of the skill with `read` and runs scripts with `bash`. The permission policy and the path sandbox decide these actions. `SkillTool.readableDirectories` lists the skill folders for a read-only sandbox rule, but the launcher does not pass them to the sandbox yet.

### The `skill` tool

- **Input:** `name` and optional `arguments` (text). The schema of `name` is an enum of the valid skill names, so the model cannot ask for a skill that does not exist.
- **Result:** the body of `SKILL.md` with the frontmatter removed, and the arguments. `$ARGUMENTS` in the body is replaced by the arguments. If the body has no `$ARGUMENTS` and the arguments are not blank, Jeorje adds the line `ARGUMENTS: <text>` at the end of the body. The `compatibility` text follows as one line if the skill has it. The result is wrapped so that Jeorje can find it later:

  ```
  <skill_content name="release-notes">
  ...body...

  Skill directory: /path/to/release-notes
  Relative paths in this skill are relative to the skill directory.

  <skill_resources>
    <file>scripts/log.sh</file>
    <file>references/format.md</file>
  </skill_resources>
  </skill_content>
  ```

  The resource list names the other files of the skill folder and its sub-folders, in sorted order. It does not read them. It stops at 100 files and adds a note that says so.
- **Deduplication.** The tool remembers, for each agent, the skills that it returned. A second call for the same skill returns a short note that the skill is already in the context. `SkillTool.release` clears this memory for a skill, but compaction does not call it yet. A skill that compaction pruned therefore stays unavailable to the tool until the next start.
- The tool is read-only and `ALLOW` in the permission policy. It also works in plan mode.

### User invocation

`/<name> [arguments]` starts a skill. Jeorje sends the agent a prompt. The prompt names the skill, its file, and its directory, and then it has the body of the skill and the line `Arguments: <text>`. The `/<name>` form does not replace `$ARGUMENTS`. The input line completes skill names after `/`. Built-in slash commands have precedence over custom commands, and custom commands have precedence over skills. If a skill name clashes with a built-in command, the skill is available only through the `skill` tool. If it clashes with a custom command, the custom command runs. `/help` shows a warning in both cases.

### Context and compaction

A skill is durable guidance. If compaction removes it, the agent loses the guidance with no visible error. Jeorje therefore protects it.

- The result of the `skill` tool is `PROTECTED`. Compaction does not prune it. See [context-and-compaction.md](context-and-compaction.md#protection-classes).
- A limit keeps skills from filling the context. When the protected skills are over the limit, Jeorje changes the oldest skill result to `AGGRESSIVE`, and the pruner can prune it. The limit is 20,000 tokens. The agent uses this value as a fixed number, so `skills.maxProtectedTokens` has no effect yet. Jeorje has no record of when a skill was last used, so it orders the skills by the turn of the result. Its keyword hint has `skill:<name>`, and the model can load the skill again.
- The index and `transcript.jsonl` keep the text of every skill result.

### Subagents

A subagent gets the `skill` tool of its parent, unless its `tools` list leaves the tool out. The skill result is `PROTECTED` in the working context of the subagent. A skill can start work in a subagent by calling `spawn_agent` from its instructions. A subagent keeps the context of its parent small: the skill that a subagent loads does not enter the context of the parent.

## Custom commands

A custom command is a markdown file in `.agents/commands/`. It adds a slash command that sends a prompt.

```markdown
---
description: Review the staged changes
argument-hint: "[focus]"
model: vendor/strong-model
---

Review the staged changes (`git diff --cached`). Focus on: $ARGUMENTS
```

- The command name is the file name without `.md`. A file in a subfolder gets the name `folder:name`.
- The body is a prompt. The placeholders are `$ARGUMENTS` (all arguments), and `$1`, `$2`, and so on (single arguments, split like a shell line). A placeholder with no matching argument becomes empty.
- The optional `model` is used for this one prompt. The optional `description` and `argument-hint` show in `/help`, in `/commands`, and in the completion of the input line.
- Built-in commands have precedence. A custom command with a built-in name is skipped with a warning. A file with a YAML error in its frontmatter is skipped with an error.
- `/commands` lists all custom commands and the diagnostics.

## Subagent definitions

A subagent definition is a markdown file in `.agents/agents/`. It names a reusable kind of subagent.

```markdown
---
name: reviewer
description: Reviews code for bugs and style. Use after a change is complete.
tools: [read, grep, glob, ls, bash]
model: vendor/strong-model
isolation: worktree
---

You review code. Report problems with file and line. Do not change files.
```

| Field | Rule |
|-------|------|
| `name` | Same rules as a skill name. If it is missing, Jeorje uses the file name without `.md` and records a warning. |
| `description` | Required. It tells the parent when to use this type. A definition without it is skipped with an error. |
| `tools` | Optional list, or a string with names that a comma or a space separates. The registry of the subagent has only these tools. The default is the normal subagent toolset. The list can never add a tool that the parent does not have. |
| `model` | Optional. The default is the default model. |
| `isolation` | Optional. `worktree` or `shared`. Another value is ignored with a warning. See [subagents.md](subagents.md#worktrees). |

The body is added to the system prompt of the subagent after the normal parts.

The `spawn_agent` tool has a `type` field. With `type: reviewer`, Jeorje uses the definition. An unknown type is an error. Fields that the call sets (`tools`, `model`, `isolation`) replace the fields of the definition. The description of `spawn_agent` does not list the known types. `/agent-types` shows them. The permission rule of subagents still applies: a definition cannot give a subagent more permission than its parent has.

## Configuration

```yaml
instructions:
  enabled: true
  maxBytes: 32768                  # for each file
  maxTotalBytes: 65536             # system-prompt part
  userFiles: [${HOME}/.jeorje/AGENTS.md, ${HOME}/.agents/AGENTS.md]
skills:
  enabled: true                    # no effect yet
  maxListChars: 8000               # no effect yet
  maxProtectedTokens: 20000        # no effect yet
  honorAllowedTools: false         # no effect yet
  dirs: []                         # more directories, each with skills/, commands/, agents/
  compat: []                       # skill directories of other tools, used as written
trust:
  projects: []                     # user config and --config file only: paths of trusted projects
agents:
  dirs: []                         # more directories, each with skills/, commands/, agents/
```

The keys `skills.dirs`, `agents.dirs`, `instructions.userFiles`, and `trust.projects` expand `~`, `${HOME}`, and `${CWD}` at the start of a value. See [configuration.md](configuration.md).

## Commands

| Command | Action |
|---------|--------|
| `/init` | Ask the agent to write an `AGENTS.md` for the project |
| `/skills` | List skills with their scope, description, location, and diagnostics |
| `/trust [revoke]` | Trust the current project (or revoke the trust) for project skills, commands, and subagent definitions |
| `/<skill-name> [arguments]` | Start a skill |
| `/commands` | List custom commands, with their diagnostics |
| `/agent-types` | List subagent definitions, with their diagnostics |
| `/instructions` | Show the user files and the project file that apply, in the order Jeorje uses them. A cut file has the mark `(truncated)`. |
