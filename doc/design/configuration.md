---
title: Configuration
description: Config file locations, precedence, keys, session storage on disk, and logging.
---

# Configuration

## Formats

Config files use YAML by default. JSON is also accepted. Both formats have the same keys and the same structure.

- Jeorje chooses the parser from the file extension: `.yaml` and `.yml` use the YAML parser, and `.json` uses the JSON parser. Jackson 3 reads both.
- A file with another extension is an error.
- All examples in the documentation use YAML.
- Jeorje creates new config files in YAML. For example, `jeorje config --init` writes `config.yaml`.

## Locations

Jeorje reads these files in this order. A later file overrides an earlier file.

1. `${HOME}/.jeorje/config.yaml` (user config)
2. `${CWD}/.jeorje/config.yaml` (project config)
3. The file given with `--config`

In each directory, Jeorje looks for `config.yaml`, then `config.yml`, then `config.json`. It uses the first file that it finds. If more than one of these files exists, Jeorje uses the first one and logs a warning that names the files it ignored.

Environment variables and command-line flags override file values. See [Precedence](#precedence).

**Merge rules.** Jeorje merges maps key by key. A list or a single value in a later file replaces the earlier value. There are two exceptions:

- `permissions.rules` is a list that adds. The rules of the project config come after the rules of the user config, so a project cannot drop a `deny` rule of the user.
- `prompts.system` and `prompts.compaction` are replaced as a whole. If a later file sets one of them, the keys that it does not set return to their defaults.

**Unknown keys.** A key that the config does not define is an error. The message names the key and the file. The keys under `llm.headers`, `llm.extraBody`, `ui.chat.highlight.aliases`, `ui.keys`, `editor.keys`, `context.windows`, and `mcp.servers` are free-form, so Jeorje does not check their names. A value of the wrong type or outside its range is also an error. The message names the key.

**Trust and the project config.** The project config comes from the repository. If the user has not trusted the project, Jeorje reads it in restricted mode: it can only make settings stricter (for example more `ask` and `deny` rules and lower limits). It cannot widen permissions, register executables, change prompts, or add directories. For each key that it ignores, Jeorje logs a warning. After the user trusts the project, Jeorje reads it in full. The list of ignored keys is in [instructions-and-skills.md](instructions-and-skills.md#trust). The user config and the file from `--config` are always read in full.

Jeorje treats a project as trusted at startup when its real path is in `trust.projects` (from the user config or the `--config` file), or when the flag `--trust` is set. The `/trust` command stores the answer in `${HOME}/.jeorje/trust.yaml`. See [instructions-and-skills.md](instructions-and-skills.md#trust). A project config cannot set `trust.projects`. Jeorje ignores the key and logs a warning.

**Path values.** These keys are path values: `index.modelCache`, `logging.file`, `editor.syntax.dirs`, `skills.dirs`, `skills.compat`, `agents.dirs`, `instructions.userFiles`, `permissions.extraDirectories`, `plan.dir`, `trust.projects`, and the `file` of a prompt. Jeorje expands `~`, `${HOME}`, and `${CWD}` at the start of the value. It does not expand other variables in path values. A relative `file` of a prompt starts at the directory of the config file that names it. Examples use `${HOME}` and `${CWD}`.

## Precedence

Flags, then environment variables, then files, then built-in defaults.

Only a few flags and variables set config values:

| Source | Config key |
|--------|------------|
| `--model` | `models.default` |
| `--base-url` | `llm.baseUrl` |
| `--peer host:port` (repeatable) | `swarm.wka`, and `swarm.enabled` is `true` |
| `--swarm` | `swarm.enabled` is `true` (the default) |
| `--no-swarm` | `swarm.enabled` is `false` |
| `--swarm-bind host:port` | `swarm.bind` |
| `--swarm-token` | `swarm.token` |
| `--no-mouse` | `ui.mouse.enabled` is `false` |
| `--no-mcp` | No key. The session starts no MCP server. |
| `JEORJE_SWARM_TOKEN` | `swarm.token` |

The flag `--swarm-token` wins over `JEORJE_SWARM_TOKEN`. An empty `swarm.token` is valid. Jeorje then reads or creates the token file `${HOME}/.jeorje/swarm-token` at start. A token that you set always wins. Other flags (`--dir`, `--config`, `--trust`, `--yes`, `--yolo`) and other variables do not set config keys. See [Environment variables](#environment-variables).

The command `jeorje config` prints the effective config and the source of each value. It prints YAML by default, with the source as a comment on each line. The option `--format json` prints a JSON object with the keys `config` and `sources`. The command hides secrets.

## Keys

```yaml
llm:
  provider: openai-compatible        # first implementation
  baseUrl: https://openrouter.ai/api/v1
  apiKeyEnv: OPENROUTER_API_KEY      # name of the environment variable (a list of names is also accepted; the first one with a value wins)
  maxOutputTokens: 8192
  headers:                           # optional
    HTTP-Referer: https://example.org
    X-Title: Jeorje
  extraBody: {}                      # optional. Fields for the top level of each request body.
  # extraBody:
  #   provider: {order: [DeepInfra], allow_fallbacks: false}
models:
  default: ~deepseek/deepseek-flash-latest   # OpenRouter slug. Set with /model.
  compactor: vendor/cheap-model  # optional. Set with /compactor. Empty means the fallback pruner.
  advisor: ~deepseek/deepseek-pro-latest   # Set with /advisor. The value none turns the advisor off. Empty means the default.
context:
  window: 128000                     # context window in tokens, for all models
  windows: {}                        # window in tokens for single models, by slug. It wins over window.
  budgetPercent: 60
  recentTurns: 6
  recentMaxPercent: 40               # most of the budget that the recent turns use. 1 to 100. A turn that does not fit moves to the earlier context.
  compaction:
    softPercent: 50
    hardPercent: 80
    levels: [1.0, 0.5, 0.2, 0.0]     # share of tokens to keep at each level
    batchChunks: 20
    batchTokens: 16000
    maxKeywords: 12
    protectedRecentTurns: 6          # window for immediate file paths
    aggressiveDropAfterTurns: 12     # AGGRESSIVE chunks go to level 3 after this age
index:
  embeddings: true                   # false gives text-only search
  modelCache: ${HOME}/.cache/jeorje/models
  userBoost: 1.5                     # score factor for user chunks in search; above 0; 1 = no boost
permissions:
  rules:
    - tool: bash
      pattern: "mvn *"
      decision: allow
    - tool: write
      decision: ask
  extraDirectories: []
  webfetch:
    allow: []
    deny: []
swarm:
  enabled: true                      # the swarm is on by default
  bind: 127.0.0.1:0                  # port 0 means a free port
  wka: []                            # list of host:port
  token: ""                          # empty means Jeorje makes one and saves it. Prefer JEORJE_SWARM_TOKEN
  gossipIntervalMs: 1000
  discovery: false                   # zeroconf (mDNS) discovery of peers on the network
  rendezvous: true                   # file-based discovery of instances that share one user directory
  ssh:
    enabled: true                    # SSH channel for agent messages, questions, and foreground attach
    bind: 127.0.0.1:0                # port 0 means a free port
    advertiseHost: ""                # host that peers dial. Empty means the host of the bind address.
  tls:
    keyStore: ""                     # PKCS12 key store that all peers share. Empty means no TLS.
    passwordEnv: ""                  # name of the environment variable with the key store password
tools:
  read:
    frontMatterScanLines: 25        # lines to scan for the opening marker
    frontMatterMaxLines: 40         # maximum size of a front matter block
    directoryDocs: [AGENTS.md, README.md, llms.txt, llm.txt]   # empty list turns directory context off
    directoryDocChars: 2000         # cut each directory doc at this size
    directoryDocFallbackLines: 20   # lines used when a doc has no front matter
  diff:
    context: 3
    maxLines: 50000                 # lines on each side
    algorithm: myers                # myers or myers-linear
  patch:
    fuzz: 2                         # 0 to 3
    conflicts: fail                 # fail or markers
  bash:
    shell: /bin/sh
    timeoutSeconds: 120
  search:                            # names that grep, glob, and ls skip. includeIgnored in a call searches them.
    skipDirs: [target, build, out, node_modules, .gradle, .idea, .vscode, __pycache__, .venv]
    skipFiles: [.DS_Store, "*.class", "*.jar", "*.o", "*.so", "*.dylib", "*.pyc"]   # a name, or *.suffix
  extensions: []                     # command tools
instructions:
  enabled: true
  maxBytes: 32768                  # for each AGENTS.md file
  maxTotalBytes: 65536             # system-prompt part
  userFiles: [${HOME}/.jeorje/AGENTS.md, ${HOME}/.agents/AGENTS.md]
skills:
  enabled: true
  maxListChars: 8000
  maxProtectedTokens: 20000
  honorAllowedTools: false
  dirs: []                         # more skill directories
  compat: []                       # for example [.claude/skills]
trust:
  projects: []                     # user config only
agents:
  dirs: []                         # more directories with skills/, commands/, agents/
  stepLimit: 200                   # model calls in one agent turn
  repeatGuard: true                # answer a repeated read, ls, glob, or grep with a note
plan:
  dir: plan                        # relative to the project directory
  maxExploreCalls: 40              # tool calls to explore before plan mode asks for the plan. 5 to 500
subagents:
  askTimeoutSeconds: 120           # wait time for agent_ask
  maxAsks: 10                      # agent_ask calls for each task
  stepLimit: 200                   # model calls of one subagent task
worktree:
  isolation: auto                  # auto, always, or never
  base: head                       # head or snapshot
  branchPrefix: jeorje
  copy: []                         # ignored files to copy into each worktree
  link: []                         # ignored paths to link into each worktree
  onFinish: delete-if-clean        # delete-if-clean, keep, or remove
  merge: merge                     # merge, squash, or rebase
  mergeBack: auto                  # auto or manual
  mergeWaitSeconds: 300            # wait when the parent tree is dirty
  sync:
    maxRounds: 3                   # sync and merge-back rounds before the lock is held
    maxModelCalls: 30              # model calls for each resolution round
    verify: ""                      # command after the sync (story verification for workers)
implement:
  parallel: 3                      # workers at the same time
  model: ""                        # empty means the default model
  review: advisor                  # advisor or none
  maxAttempts: 2
  maxModelCalls: 200               # for each story
  handoff: true
prompts:
  system:
    mode: append                     # append (default) or replace
    text: |                          # inline text
      Prefer small, focused changes.
    # file: prompts/system.md        # or a file. Use text or file, not both.
  compaction:
    mode: replace                    # replace (default) or append
    file: prompts/compaction.md
ui:
  keyboardProtocol: auto             # auto, on, or off (Kitty keyboard protocol)
  chat:
    highlight:
      enabled: true
      maxLines: 2000
      diff: true                     # diff rendering for edit results and diff blocks
      read: true                     # highlight read and grep results
      fences: true                   # highlight fenced code blocks
      aliases: {}                    # more tags, for example {kts: kotlin}
  mouse:
    enabled: true
    wheelLines: 3                    # lines for each wheel step
    copyOnSelect: false              # copy when the button is released
  keys:                              # chat screen. Each key is an action id.
    agents: Ctrl-X a
    files: [Ctrl-X f, F3]            # a list gives more than one key
    expandResult: none               # none removes the key
editor:
  syntax:
    enabled: true
    maxBytes: 5242880                # no colors above this file size (5 MB)
    dirs: []                         # more directories with .nanorc files
    systemDirs: true                 # search the directories of an installed GNU nano
    embedded: true                   # use the syntax set inside the jeorje-ui jar
    injections: true                 # color fenced code blocks in Markdown files
    replayLines: 2000                # lines to highlight again after a jump
    maxLineChars: 2000               # longer lines are cut for the highlighter
    budgetMs: 200                    # turn colors off for a syntax after two slow passes
  keys:                              # editor. See Key bindings.
    save: Ctrl-S
    find: Ctrl-Q f
logging:
  level: INFO
  file: ${HOME}/.jeorje/logs/jeorje.log
mcp:
  servers: {}                      # MCP servers by name; see "MCP servers"
```

Rules for the keys:

- `llm.apiKeyEnv` names environment variables. It is one name or a list. Jeorje uses the first variable that has a value that is not blank. Jeorje does not store the key in the file. See [permissions-and-safety.md](permissions-and-safety.md).
- `models` keys are described in [context-and-compaction.md](context-and-compaction.md#models). A slash command changes the value for the running session only. The command does not change the config file.
- `context.window` and `context.windows` set the size of the context window. Each value is a positive whole number of tokens. A string such as `"128_000"` is also accepted. For a model, Jeorje uses the entry of `context.windows` for the slug of the model. If there is no entry, it uses `context.window`.
- `ui.keys` and `editor.keys` are described in [Key bindings](#key-bindings).
- `subagents` keys are described in [subagents.md](subagents.md#talking-to-other-agents). The key `subagents.stepLimit` sets the limit of the model calls of a subagent task.
- `worktree` keys are described in [subagents.md](subagents.md#worktrees).
- `instructions`, `skills`, and `agents` keys are described in [instructions-and-skills.md](instructions-and-skills.md#configuration). The key `agents.stepLimit` sets the step limit of a turn. See [agent-loop.md](agent-loop.md#the-loop).
- `plan` and `implement` keys are described in [planning.md](planning.md#configuration).
- `prompts` keys are described in [Prompts](#prompts).
- `swarm` keys are described in [swarm.md](swarm.md).
- `permissions` keys are described in [permissions-and-safety.md](permissions-and-safety.md).
- `ui` and `editor` keys are described in [ui.md](ui.md). The interactive launcher reads all `ui` and `editor` keys: `ui.keyboardProtocol`, `ui.chat.highlight`, `ui.mouse`, `ui.keys`, `editor.keys`, and `editor.syntax`. The key `ui.mouse.copyOnSelect` acts in the chat view only. See [ui.md](ui.md#known-limits).

**Rules that Jeorje writes.** One answer to a permission prompt changes a config file. When the user answers "allow in the project", Jeorje adds a rule with `decision: allow` and no `pattern` for the tool to `${CWD}/.jeorje/config.yaml`. Jeorje creates the file when it is missing. It does this only in a trusted project. It does nothing if the same rule exists. It edits the text of the file, so comments stay. If the shape of the file does not allow a text edit, Jeorje writes the parsed data again, and the comments are lost. In an untrusted project, the rule holds for the session only, and the interface shows a notice.

## MCP servers

The key `mcp.servers` is a map. Each key is the name of a server. The name uses only `a-z`, `0-9`, `_`, and `-`. The name becomes a part of the tool names. See [tools.md](tools.md#mcp-tools).

```yaml
mcp:
  servers:
    files:                           # a stdio server
      command: npx
      args: [-y, "@modelcontextprotocol/server-filesystem", "${CWD}"]
      cwd: ${CWD}                    # default: the working directory
      env: {LOG_LEVEL: warn}         # literal values, added to the child environment
      envFrom: [GITHUB_TOKEN]        # names of variables to copy from the Jeorje environment
      timeoutSeconds: 60             # handshake and each call
    remote:                          # an http server
      transport: http
      url: https://mcp.example.com/mcp
      headers: {Authorization: "Bearer ${MCP_TOKEN}"}
      enabled: false                 # default: true
```

- `transport` is `stdio` (default) or `http`.
- A `stdio` server needs `command`. `args`, `cwd`, `env`, and `envFrom` are optional. Jeorje expands `~`, `${HOME}`, and `${CWD}` in `command` and `cwd`.
- An `http` server needs `url`. `headers` is optional. Jeorje replaces `${NAME}` in a header value with the environment variable `NAME` when it connects.
- `timeoutSeconds` is a positive whole number. The default is 60.
- `enabled: false` keeps the entry but starts nothing.
- A project config merges with the user config for each server name. Jeorje ignores `mcp` in the project config of an untrusted project.
- `jeorje config` hides the values of `env` and `headers`.
- The flag `--no-mcp` starts no server for one run. The command `/mcp` shows each server, its state, and its tools.

## Prompts

The config sets two prompts: the system prompt and the compaction prompt.

| Key | What it changes | Default `mode` |
|-----|-----------------|----------------|
| `prompts.system` | The identity and behavior section of the system prompt of each agent. See [agent-loop.md](agent-loop.md#system-prompt). | `append` |
| `prompts.compaction` | The instructions that Jeorje sends to the compactor model. See [context-and-compaction.md](context-and-compaction.md#pruner). | `replace` |

Each prompt has these fields:

- `text`: the prompt as an inline string.
- `file`: the path to a text file. A relative path starts at the directory of the config file that names it. Use `text` or `file`. If a prompt has both, Jeorje stops with an error when it loads the config.
- `mode`: `append` adds your text after the built-in text. `replace` uses your text in place of the built-in text. Any other value is an error.

If a prompt has no text and no file, Jeorje uses the built-in text.

Rules:

- **Layers.** The project config overrides the user config for the whole key. If the project sets `prompts.system`, Jeorje ignores `prompts.system` from the user config.
- **Variables.** The text of the compaction prompt can contain `${name}` variables. Jeorje replaces them before use. An unknown variable is an error. The variables are `${level}`, `${target}` (the share of tokens to keep, for example `50%`), `${class}`, and `${maxKeywords}`. The system prompt has no variables. Jeorje sends `${...}` text in the system prompt as it is.
- **Files are read again.** Jeorje reads a prompt file for each request, so an edit takes effect on the next request. For the compaction prompt, a file that is missing or empty is an error that stops the request. For the system prompt, Jeorje logs a warning for a file that it cannot read and uses the built-in text.
- **Fixed parts.** A custom prompt cannot remove the fixed parts that Jeorje adds. For the system prompt, these are the tool guidance, the workspace description (working directory, platform, and date), the `AGENTS.md` instructions, and the guidance for pruned chunks. For the compaction prompt, this is the output contract: the format of the kept spans, the keywords, and the decision notes. The validation rules in [context-and-compaction.md](context-and-compaction.md#pruner) always apply. If a custom compaction prompt produces answers that fail validation, the fallback pruner runs.
- **Safety.** A prompt cannot change permissions. See [permissions-and-safety.md](permissions-and-safety.md). Jeorje removes secrets from logs, and it does not log prompt files.
- **Check.** `jeorje config` prints the source file of each prompt. The option `--show-prompts` prints the full assembled system prompt and the full compaction prompt.

## Key bindings

The config can change the hotkeys of the chat screen and the editor. There are two scopes: `ui.keys` for the chat screen and the file browser, and `editor.keys` for the editor. Each key in the map is an **action id**. The value is one key, a list of keys, or `none`. An action id that does not exist, or that belongs to the other scope, is an error.

Keys belong to four contexts: the chat screen, the file browser, the form, and the editor. The chat screen, the file browser, and the form share the scope `ui.keys`. See [Action ids and defaults](#action-ids-and-defaults).

### Key notation

- A key is a character (`a`, `/`), or a name: `Enter`, `Esc`, `Tab`, `Space`, `Backspace`, `Del`, `Ins`, `Home`, `End`, `PgUp`, `PgDn`, `Up`, `Down`, `Left`, `Right`, and `F1` to `F12`.
- Modifiers come before the key with a hyphen: `Ctrl-`, `Alt-`, and `Shift-`. Example: `Ctrl-Shift-Left`.
- A **chord** is a sequence of keys with spaces between them. Example: `Ctrl-X a` means press `Ctrl-X`, release it, and then press `a`.
- Names and modifiers are not case sensitive. A single letter is case sensitive when it has no modifier. With `Ctrl-`, the letter is not case sensitive.
- `none` or an empty list removes all keys from the action. The action stays available from its menu or slash command.

### Rules

- **Override.** A key that the config sets replaces the default keys of that action. An action that the config does not name keeps its default keys.
- **Layers.** Jeorje merges the layers for each action. The project config overrides the user config for the actions that it names.
- **Conflicts.** Jeorje checks conflicts for each context: the chat screen, the file browser, and the editor. Two actions in the same context cannot have the same key. A key also cannot be a complete binding and the first key of a chord at the same time. Actions in different contexts can share a key. For example, `send` and `browserEdit` both use `Enter`. If a rule fails, Jeorje reports an error that names the two actions and the key, and the start stops. The same holds for an unknown action id and for a key that Jeorje cannot read.
- **Required actions.** The actions `send` and `interrupt` (chat) and `save` and `close` (editor) must keep at least one key. `none` for one of them is an error. This prevents a lockout. The slash command `/quit` and the editor menu always work.
- **Fixed keys.** Some keys are not actions. `Enter` and `Esc` work in the editor and in the dialogs, and `Ctrl-C`, `Ctrl-V`, and `Ctrl-X` are aliases for copy, paste, and cut in the editor. A configured key wins over a fixed key. An alias runs only when it can act: copy and cut need a selection, and paste needs text in the clipboard.
- **Aliases.** A terminal sends the same code for some keys: `Ctrl-I` and `Tab`, `Ctrl-M` and `Enter`, `Ctrl-[` and `Esc`, `Ctrl-H` and `Backspace`. Jeorje does not accept the `Ctrl-` form of these keys. The error tells the user to use the name.
- **Keyboard protocol.** Jeorje calls `terminal.hasKittyKeyboardSupport()`. When the terminal supports the Kitty keyboard protocol, Jeorje turns it on with `setKittyKeyboardMode(...)` and turns it off at exit. The terminal then sends `Ctrl-Ins`, `Shift-Ins`, `Alt` combinations, and the modifiers of chord keys in a form that Jeorje can read. The key `ui.keyboardProtocol` is `auto` (default), `on`, or `off`. With `on`, Jeorje turns the protocol on without the support check. The `Ctrl-I`, `Ctrl-M`, and `Ctrl-[` forms stay rejected, so a config works in all terminals.
- **Terminal limits.** Without the protocol, some terminals do not send some keys, for example `Ctrl-Ins`, `Shift-Ins`, or `Alt` combinations. For a key that has no legacy code, Jeorje logs a warning when it builds the key map. For other keys, Jeorje cannot detect that the terminal does not send them. A key that the terminal does not send never triggers its action. The user can bind a second key to the same action.
- **Menus and help.** The editor menus, the status bar, the `/help` text, and the key help show the current keys, not the defaults.
- **Slash commands.** Slash commands do not change. They always work.
- **Input line.** The input line keeps the JLine emacs keys for editing (`Ctrl-A`, `Ctrl-E`, `Ctrl-F`, and others). If an action uses one of these keys, the action wins in the chat screen, and Jeorje logs a warning that names the editing key it replaced.
- **Menu bar.** `Alt` alone cannot open the menu bar. `F10` and the `Alt` letter keys (`Alt-F`, `Alt-E`, and so on) open the menus.

The command `jeorje config --keys` and the slash command `/keys` print the effective bindings for both scopes. The command `jeorje config --keys` names the source of each binding (the built-in default, or the config file that set it). The slash command `/keys` marks each binding as `default` or `config`.

### Action ids and defaults

Chat screen (`ui.keys`):

| Action id | Default | Action |
|-----------|---------|--------|
| `send` | `Enter` | Send the input |
| `newline` | `Alt-Enter` | New line in the input |
| `interrupt` | `Ctrl-C`, `Ctrl-D` | Interrupt the foreground agent. A second press within 2 s exits. |
| `agents` | `Ctrl-X a` | Open or close the agent switcher |
| `files` | `Ctrl-X f` | Open the file browser |
| `expandResult` | `Ctrl-X o` | Expand or collapse the last tool result |
| `redraw` | `Ctrl-L` | Redraw the screen |
| `follow` | `Ctrl-X b` | Jump to the bottom of the chat view and follow new output |
| `copySelection` | `Ctrl-X c` | Copy the selected text in the chat view |
| `scrollUp`, `scrollDown` | `PgUp`, `PgDn` | Scroll the chat view |

The `copySelection` action copies the text that the user selected in the chat view with the mouse. See [ui.md](ui.md#selecting-and-copying-text). The `files` and `agents` keys also close the pane that they opened.

File browser (`ui.keys`). The browser pane reads these keys. A key that the config sets replaces the default key of the action, also for the pane. The pane shows the current keys in its title row:

| Action id | Default | Action |
|-----------|---------|--------|
| `browserOpen` | `Right` | Open a directory and move into it |
| `browserClose` | `Left` | Close a directory or move to the parent |
| `browserEdit` | `Enter` | Open the file in the editor |
| `browserAdd` | `a` | Add the path to the prompt |
| `browserFilter` | `/` | Filter by name or by glob |
| `browserHidden` | `.` | Show or hide the data of git and of Jeorje |
| `browserExit` | `Esc` | Close the browser |

Form (`ui.keys`). The form of `ask_user` reads these keys. See [Forms](ui.md#forms). The keys in a text field are not rebindable. A default key of an action that the config moved to another key does nothing:

| Action id | Default | Action |
|-----------|---------|--------|
| `formUp`, `formDown` | `Up`, `Down` | Move to the row above or below |
| `formNext`, `formPrevious` | `Tab`, `Shift-Tab` | Go to the next or previous question |
| `formToggle` | `Space` | Choose or clear the option |
| `formChat` | `c` | Chat about this question |
| `formDecline` | `Esc` | Ask to decline the form |

Editor (`editor.keys`):

| Action id | Default | Action |
|-----------|---------|--------|
| `menu` | `F10` | Open the menu bar |
| `menuFile`, `menuEdit`, `menuSearch`, `menuView`, `menuOptions`, `menuHelp` | `Alt-F`, `Alt-E`, `Alt-S`, `Alt-V`, `Alt-O`, `Alt-H` | Open one menu |
| `save` | `Ctrl-S` | Save |
| `close` | `Alt-X` | Close the editor and return to the chat |
| `undo`, `redo` | `Ctrl-Z`, `Ctrl-Y` | Undo and redo |
| `cut`, `copy`, `paste` | `Shift-Del`, `Ctrl-Ins`, `Shift-Ins` | Clipboard |
| `selectAll` | `Ctrl-A` | Select all text |
| `find` | `Ctrl-Q f` | Find |
| `repeatFind` | `F3` | Repeat the last find |
| `change` | `Ctrl-Q a` | Find and replace |
| `goToLine` | `Ctrl-G` | Go to a line |
| `help` | `F1` | Show the key help |
| `switchWindow` | `F6` | Move the cursor to the other split window |
| `toggleInsert` | `Ins` | Switch between insert and overwrite |
| `indent`, `unindent` | `Tab`, `Shift-Tab` | Indent or unindent |
| `wordLeft`, `wordRight` | `Ctrl-Left`, `Ctrl-Right` | Move by word |
| `fileStart`, `fileEnd` | `Ctrl-Home`, `Ctrl-End` | Go to the start or the end of the file |

Movement keys (arrows, `Home`, `End`, `PgUp`, `PgDn`), `Backspace`, and `Del` are also actions of the editor (`left`, `right`, `up`, `down`, `lineStart`, `lineEnd`, `pageUp`, `pageDown`, `backspace`, `delete`). Users can change these keys in the same way. The `Shift-` form of each movement key selects text. Jeorje derives it from the movement key and gives it the internal id `select:<id>`, for example `select:left`. The same holds for `wordLeft`, `wordRight`, `fileStart`, and `fileEnd`. Jeorje does not derive a Shift form when another action already uses that key. A derived key is not an action id, so the config cannot name it.

Keys that the terminal needs to type text are not actions. A printable character with no modifier always enters the text in the editor and the input line.

## Directories

Jeorje uses two config directories.

- **User directory:** `${HOME}/.jeorje`. It holds the settings and the data that belong to the user, for all projects. The variable `JEORJE_HOME` replaces this directory for the search of the user config file only. The other user files (skills, commands, subagent definitions, `AGENTS.md`, `trust.yaml`, `peer-id`, and `history`) stay under `${HOME}/.jeorje`.
- **Project directory:** `${CWD}/.jeorje`. `CWD` is the working directory where the user starts Jeorje, or the directory given with `--dir`. It holds the project config, the sessions, and the indexes.

Jeorje keeps sessions and indexes in the project directory. Each project has its own sessions and indexes. A session never mixes with the sessions of another project.

```
${HOME}/.jeorje/                  # user directory
  config.yaml                     # user config
  trust.yaml                      # trusted projects, written by /trust
  history                         # input history of the chat screen
  peer-id                         # PeerId of this instance
  logs/jeorje.log

${CWD}/plan/                         # plans (the default plan.dir, outside .jeorje)

${CWD}/.jeorje/                   # project directory
  config.yaml                     # project config
  worktrees/<group>/<name>/       # git worktrees for subagents (group: plan slug or session id)
  sessions/<sessionId>/
    manifest.json                 # agent tree, config snapshot, pinned items, pruning table, run state
    transcript.jsonl              # every message, one JSON object per line
    audit.jsonl                   # tool decisions
    index/                        # Lucene index

${HOME}/.cache/jeorje/models/     # embedding model files, shared by all projects
${HOME}/.cache/jeorje/syntax/     # syntax files of the editor, one directory for each Jeorje version
```

- `transcript.jsonl` is the source of truth. Jeorje can rebuild the index from it.
- Jeorje writes the manifest after each turn with an atomic file replace.
- A session id is a time-ordered UUID. `jeorje resume` with no argument resumes the latest session in the project directory.
- Sessions can contain source code and tool output. Users usually do not commit them. Jeorje writes `${CWD}/.jeorje/.gitignore` when it creates the directory. The file ignores `sessions/` and `worktrees/`. The project config stays outside the ignore rule, so users can commit it.
- Jeorje creates the project directory when the first session starts. If the working directory cannot be written, Jeorje stops with an error and does not use another location.

## Logging

- Jeorje uses SLF4J with Logback.
- Logs go to the log file. The default level is `INFO`. The UI never writes logs to the terminal. The commands `jeorje run`, `jeorje resume --task`, `jeorje index rebuild`, and `jeorje config` write logs to stderr only.
- Code uses the fluent form with a lazy message: `log.atInfo().log(() -> "message")`.
- Log output never contains secrets. See [permissions-and-safety.md](permissions-and-safety.md).
- Logback rolls the log file each day and keeps 14 files.
- The key `logging.level` sets the level of all loggers.

## Environment variables

| Variable | Use |
|----------|-----|
| `OPENROUTER_API_KEY` | API key for OpenRouter. This is the default name in `llm.apiKeyEnv`. |
| `OPENAI_API_KEY` | API key for another OpenAI-compatible endpoint. Add its name to `llm.apiKeyEnv` to use it. Jeorje always removes its value from logs. |
| `JEORJE_SWARM_TOKEN` | Shared swarm token. It sets `swarm.token`. |
| `JEORJE_HOME` | Replaces the user directory `${HOME}/.jeorje` for the user config file |
| `NO_COLOR` | Turns off colors in the chat view, the editor, and the diff tints. An empty value has no effect. |
| `TERM` | The interface sends copied text with the OSC 52 sequence unless `TERM` is empty, `dumb`, `linux`, or starts with `screen` |
