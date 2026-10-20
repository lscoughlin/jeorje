---
title: Permissions and Safety
description: The permission policy, path sandbox, command and network rules, secret handling, and audit log.
---

# Permissions and Safety

The `PermissionPolicy` port decides if an action is allowed. The agent loop calls it before each tool run. See [agent-loop.md](agent-loop.md).

## Decisions

`PermissionPolicy.check(action)` returns one of three decisions:

- `ALLOW`: the tool runs.
- `ASK`: the UI shows the action and the user approves or rejects it.
- `DENY`: the tool does not run. The model gets a `ToolResult` with `error = true` and the text `Permission denied: the call to <tool> did not run.` The text has no reason.

The agent loop handles `ASK`. The tools that Jeorje runs itself (`task_done`, `story_done`, `story_blocked`, `story_read`, `sync_done`, and `sync_abort`) never ask the user. The tools themselves call the policy for the paths and commands that they use. A tool refuses a call only when the policy returns `DENY`. The search tools (`ls`, `glob`, and `grep`) cannot ask the user, so they skip each path that the policy does not allow with `ALLOW`.

## Rules

Rules are in the config (`permissions.rules`). See [configuration.md](configuration.md). A rule has a tool name, an optional pattern, and a decision. The tool name is exact, `*`, or a prefix that ends in `*` (for example `mcp__github__*`). The pattern is a regular expression when it starts with `re:`. It is a glob when it has `*` or `?`. Otherwise it is a prefix. For `webfetch`, the pattern is a domain. The subject of a rule depends on the tool:

- For a file tool, the subjects are the path as given and the resolved path.
- For `bash`, the subject is each part of the command.
- For `webfetch`, the subject is the host of the URL.

The most specific rule wins. A rule for an exact tool name is more specific than a rule for a tool prefix, a rule for a tool prefix is more specific than a rule for `*`, and a rule with a pattern is more specific than a rule without one. If two rules match with the same specificity, `DENY` wins over `ASK`, and `ASK` wins over `ALLOW`. A rule that the user saves for the session ("always allow") counts like a config rule.

Default rules:

| Tool | Default |
|------|---------|
| `read`, `ls`, `glob`, `grep`, `diff`, `recall`, `agent_status` | `ALLOW` inside the workspace |
| `write`, `edit`, `patch` | `ASK` |
| `bash` | `ASK`, with an allow list of safe read-only commands |
| `webfetch` | `ASK` for a new domain |
| `spawn_agent`, `agent_message`, `agent_ask`, `agent_answer`, `story_read`, `skill` | `ALLOW` |
| `worktree_merge`, `worktree_discard` | `ASK` |
| `todo`, `ask_user`, `advisor`, `plan_submit` | `ALLOW` |
| Any other tool, including extension tools and MCP tools | `ASK` |

The user can choose "always allow" in a prompt. Jeorje then saves a rule for the session. The user can also save the rule in the project config (`${CWD}/.jeorje/config.yaml`). For a project that the user does not trust, Jeorje ignores `allow` rules, `permissions.extraDirectories`, and `permissions.webfetch.allow` in the project config. See [instructions-and-skills.md](instructions-and-skills.md#trust).

## Plan mode and workers

- **Plan mode.** The interactive session puts a gate around the policy. In plan mode the gate allows only these tools (MCP tools are not in the list): `read`, `ls`, `glob`, `grep`, `recall`, `webfetch`, `skill`, `ask_user`, `advisor`, `todo`, `plan_submit`, and `bash`. It denies all other tools. `bash` follows the normal rules of the policy, as outside plan mode. The gate allows `plan_submit` only in plan mode. Every call of `plan_submit` also goes to the base policy, so the audit log has one line for each call, also for a call that the gate refuses outside plan mode. The gate decides the result. The gate comes before the normal rules, so `--yolo` does not change the tool set: plan mode never gets write tools. The policy class has a plan mode of its own (the builder option `planMode`, and `narrow` with the flag `readOnly`). In that mode the allowed tools also include `diff` and `agent_status`, and `plan_submit` is allowed only for a path inside the plan directory. `plan_submit` writes only inside the plan directory.
- **Worktrees.** A subagent in a worktree gets a policy that has the same rules, extra directories, and read-only directories as the policy of its parent, but the worktree is its workspace. The main working tree is therefore outside the sandbox of the subagent, so a read or write of a path in the main working tree by absolute path is `DENY`. The subagent gets the work back only through the merge back. The worktree directories are below `.jeorje/worktrees` in the workspace of the parent. The git calls that create and remove worktrees are internal actions, not tool calls. With `worktree.mergeBack: auto` (default), the sync and the merge back at completion are internal actions with no prompt. Manual merges (`worktree_merge`, `/merge`) and discards are `ASK`.
- **Workers.** A worker of `/implement` gets the policy of the orchestrator, never wider. In the interactive session, an `ASK` of a subagent or worker becomes a question to the user. With `--yolo`, workers run with no checks.

## MCP tools

- **Default.** An MCP tool (`mcp__<server>__<tool>`) gets `ASK`. A rule with a tool prefix, for example `mcp__github__*`, sets the decision for a whole server. A rule for one exact tool name is more specific, so it wins.
- **No path check.** The policy does not check the input fields of an MCP tool as local paths. The remote server decides what a `path` field means. The only checks are the rules. The user can therefore not limit the arguments of an MCP tool. The user can only allow, ask, or deny the whole tool. A rule pattern has no effect on an MCP tool.
- **Plan mode and read-only agents.** Plan mode denies all MCP tools. A subagent that is read-only denies them too. A subagent never gets more access than its parent.
- **Trust.** The key `mcp` in the project config of an untrusted project is ignored. A `stdio` server runs code on this computer, so only a trusted config can start it.
- **Environment.** A `stdio` server gets the minimal environment (see "Shell commands"). Only the variables that the config names in `envFrom`, and the values in `env`, are added. API keys of Jeorje are not passed unless the config names them.
- **Network.** An `http` server is checked by the network policy before each request. A domain on the deny list, an address that the policy blocks, and a URL that is not `http` or `https` stop the connection. This includes loopback and private addresses, so a server on the same computer must use `stdio`. A new domain is not a reason to stop, because the config names the URL. The values of `headers` can use `${NAME}`. Jeorje replaces `${NAME}` with the environment variable when it connects. A missing variable is an error.
- **Secrets.** `jeorje config` and the config tree hide the values of `env` and `headers`. Tool output is cleaned by the secret scrubber like the output of all tools.

## Path sandbox

- Project skills, commands, and subagent definitions load only for a trusted project. See [instructions-and-skills.md](instructions-and-skills.md#trust).
- The workspace is the working directory and the directories that the config adds (`permissions.extraDirectories`). The sandbox can also have read-only directories. A write tool is `DENY` in a read-only directory. The CLI does not pass the folders of skills as read-only directories.
- Jeorje resolves each path with `toRealPath`. This removes symbolic links and `..` segments. For a path that does not exist, it resolves the nearest parent that exists.
- The policy checks the input fields `path`, `file_path`, `filePath`, `a`, `b`, and `paths`. A tool call that has none of these fields has no path check in the policy.
- A path outside the workspace is `DENY`, unless a rule with a pattern matches the path.
- A path under a `.git` directory in the workspace, under the `.jeorje` directory of the workspace, or under a configuration directory that the sandbox knows is sensitive gets at least `ASK`, for every tool, also for reads. With `--yes`, this `ASK` becomes `ALLOW`.
- The `patch` tool also refuses a path under `.git/` and a path outside the workspace by itself, in every mode.

## Shell commands

- A rule can match a command by prefix, glob, or regular expression.
- Jeorje splits compound commands at `&&`, `||`, `;`, `|`, `&`, new lines, and parentheses. The content of `$( )`, backticks, and `<( )` becomes a part of its own. It checks each part. A part with a matching rule gets the decision of the rule. Another part gets `ALLOW` if it is on the read-only allow list and `ASK` if not. The decision for the full command is the strictest decision of its parts. An empty command gets `ASK`.
- The read-only allow list has `ls`, `cat`, `head`, `tail`, `pwd`, `echo`, `wc`, `grep`, `rg`, `find`, `sort`, `uniq`, `diff`, `stat`, `file`, `which`, `whoami`, `date`, `tree`, `du`, `df`, `basename`, `dirname`, `realpath`, `true`, `false`, `cut`, `id`, `uname`, `printf`, and the `git` commands `status`, `log`, `diff`, `show`, `rev-parse`, `ls-files`, `blame`, and `describe`. A part is not read-only if it redirects output to a file (a redirect to `/dev/null` or to a descriptor is fine). `find` with `-exec`, `-delete`, or a similar option is not read-only.
- Jeorje runs each process with a minimal environment. The environment keeps only `PATH`, `HOME`, `USER`, `LOGNAME`, `LANG`, `TERM`, `TMPDIR`, `SHELL`, `TZ`, `PWD`, and the `LC_` variables. It removes each variable whose name has `key`, `token`, `secret`, `password`, `passwd`, `credential`, or `auth`. API keys are not in the environment of the child process.
- Jeorje kills the process tree on timeout and on cancellation.

## Network

- `webfetch` uses the domain policy. The policy has allow and deny lists. A domain on a list also covers its subdomains, and a list entry can start with `*.`.
- The policy accepts only `http` and `https`. It denies a domain on the deny list. It resolves the host and denies loopback, link-local, private, multicast, and similar addresses (this includes `100.64.0.0/10`, IPv6 unique-local addresses, and IPv4-mapped IPv6 addresses). It allows a domain on the allow list or a domain that the user approved for the session. All other domains get `ASK`.
- The `webfetch` tool checks the policy again after each redirect.
- The LLM client talks only to the configured `baseUrl`.

## Secrets

- API keys come from environment variables. The config key `llm.apiKeyEnv` lists the names.
- `SecretScrubber` removes key values. It knows the API key, the values of all variables in `llm.apiKeyEnv`, the swarm token (config and `JEORJE_SWARM_TOKEN`), and `OPENAI_API_KEY`, and it knows the patterns of common keys (`sk-`, `ghp_`, `AKIA`, `xox`, bearer tokens, and `name=value` pairs where the name has `api_key`, `token`, `secret`, `password`, or `passwd`). Log output shows `***` instead. Jeorje uses the scrubber for the debug log of permission decisions, for the audit log, for error texts of the config loader, for git output, and for error events of the run mode.
- The application removes secrets from tool output before the output goes anywhere else. A key that a tool prints therefore never reaches the chat view, the transcript, the index, or the model. `ScrubbingContextStore` removes secrets from the text of each chunk before the chunk enters the index. `TranscriptWriter` removes secrets from each message before it writes the message to `transcript.jsonl`. The same scrubber also cleans the events that go to the UI. The child processes of tools have no keys in their environment.
- Jeorje does not send the environment of the user to the model.

## Subagent and swarm limits

A subagent cannot get more permission than its parent. A subagent policy denies each tool that is not in the tool set of the subagent. For all other tools it gives the stricter decision of the parent policy and of an optional extra policy. A peer message cannot approve an action. Only the user or the local rules decide. See [subagents.md](subagents.md) and [swarm.md](swarm.md).

## Audit log

Jeorje writes one JSON line for each policy check to `${CWD}/.jeorje/sessions/<sessionId>/audit.jsonl`. A line has the time, the agent id, the tool, a shortened input (200 characters at most, with secrets removed), the decision, the source of the decision (`rule`, `user`, or `yolo`), and a status. The status is always `decided`. A tool that checks a path itself adds a line for that check, so one tool call can write more than one line. The search tools `grep`, `glob`, and `ls` check the root of the call and write one line for it. They check each file of the tree without a line when the policy allows the file. A refused file gets a line. See [tools.md](tools.md). When the user answers an `ASK`, Jeorje writes a line with the source `user`. The audit log never contains secrets.

## Unattended modes

### `--yes`

The flag `--yes` sets all `ASK` decisions to `ALLOW`. `DENY` rules and the path sandbox still apply. Jeorje prints a warning at startup when `--yes` is active.

### `--yolo`

The flag `--yolo` turns off the permission checks of the policy. `PermissionPolicy` returns `ALLOW` for every action, with no rule evaluation. Plan mode comes first (see above).

- **What stops:** rules (including `DENY` rules), prompts, the path sandbox, and the shell command checks.
- **What stays:** the secret rules (no keys in logs or in the environment of child processes), the file-state check of `write` and `edit` (see [tools.md](tools.md)), the checks that tools do by themselves, tool timeouts, and the audit log. The audit log records each action with the source `yolo`. The `webfetch` tool still applies the network policy, so it still blocks private addresses and the deny list. The `patch` tool still refuses paths outside the workspace and under `.git/`.
- **Scope:** the flag applies to the whole instance: the root agent, all local subagents, and tasks that peers delegate to this instance. Tasks that this instance delegates to a peer follow the policy of that peer. See [swarm.md](swarm.md).
- **Only a flag:** there is no config key and no environment variable for `--yolo`. The user must give the flag at each start. `jeorje resume` does not keep it.
- **Warning:** Jeorje prints a warning at startup and shows `YOLO` in the status line for the whole run.
- **Conflict:** if the user gives both `--yes` and `--yolo`, `--yolo` wins.
- **Unattended use:** `jeorje run --yolo` runs a task with no checks. Users should run it only in a disposable environment, such as a container or a virtual machine.
