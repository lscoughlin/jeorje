---
title: User Interface
description: The JLine terminal interface - layout, chat view, streaming, agent switcher, file browser, editor, key bindings, and commands.
---

# User Interface

The interface is in `jeorje-ui`. It uses JLine 4 (`org.jline:jline`). The parent pom pins the exact 4.x version. It reads `AgentEvent` objects from the foreground agent. See [agent-loop.md](agent-loop.md). It never reads agent state directly.

## Layout

```
+--------------------------------------------------+
| chat view (scrolling)                            |
|                                                  |
+--------------------------------------------------+
| > input line                                     |
+--------------------------------------------------+
| agent [state] | model | ctx % | peers | notices  |
+--------------------------------------------------+
```

- The **chat view** shows the conversation of the foreground agent. It uses all rows above the input line. A permission prompt appears as a box at the bottom of this region. A pane (the agent switcher or the file browser) replaces the chat view while it is open.
- The **input line** is owned by a JLine `LineReader`. It has history and multi-line input. The history file is `${HOME}/.jeorje/history`. `Alt-Enter` adds a new line. The input area grows with the text, up to three rows less than the terminal height. The input line has no tab completion. `SlashCommands.complete` returns candidates for slash commands, but the line reader does not call it yet.
- The **status line** is the last row. The text has the foreground agent name and its state in brackets, then these fields when they apply: the git branch of the foreground agent, `YOLO` when permission checks are off, `PLAN` in plan mode, `IMPLEMENT n/m` during `/implement`, the model, `ctx n%` (the context use as a percent of the budget), `peers n` (the number of live peers), and notices. The fields have a ` | ` between them. Notices come from background agents and from the interface. The line keeps the last three notices.

JLine `Display` draws the chat region. It writes only the lines that change. The status line is written directly to the last row after each draw. The input line belongs to the `LineReader`, which draws its own rows. A permission prompt interrupts the input read. While the prompt waits, the screen reads the answer keys itself. After the answer, the input read starts again and keeps the text that the user had typed.

## Chat view

- User messages, assistant text, tool calls, and tool results appear in order.
- Assistant text streams token by token.
- Markdown output has basic styling: headings, lists, bold text, code blocks, and inline code. Code blocks keep their whitespace, except that a tab becomes four spaces.
- A tool call shows one line: the tool name and a short form of the input. A tool result is collapsed by default. It shows its first line and, for a longer result, the number of lines and the hint `Ctrl-X o to expand`. The user can expand the last tool result, and collapse it again, with `Ctrl-X o`.
- `edit` results show a colored diff.
- A `Compacted` event shows a dim line that gives the number of chunks that Jeorje pruned and the tokens it freed.
- The view keeps the last 10,000 logical lines of each agent. `PgUp` and `PgDn` scroll by a page. Each agent has its own chat view.
- Syntax highlighting of code and diffs is described in [Syntax highlighting in the chat view](#syntax-highlighting-in-the-chat-view). The interactive launcher does not turn it on yet. See [Current limits](#current-limits).

### Syntax highlighting in the chat view

The chat view uses the same `SyntaxCatalog` and `SyntaxHighlighter` as the editor. See [Syntax highlighting](#syntax-highlighting). It highlights only when the content clearly is code, a diff, or a part of a source file. It never guesses for plain text.

| Content | When | Syntax |
|---------|------|--------|
| Fenced code block in assistant text | The opening fence has a language tag | The syntax for the tag. See below. |
| Fenced code block with no tag | The first lines look like a unified diff (`diff --git`, `--- a/`, `+++ b/`, or `@@ -n,n +n,n @@`) | Diff rendering |
| `edit` result | Always. The result is a diff. | Diff rendering with the syntax of the file |
| `read` result | The file name selects a syntax | The syntax of the file, with a dim line-number column |
| `grep` result | The line has the form `path:line:text` and the path selects a syntax | The syntax of the path, applied to the text part |
| `bash` and `webfetch` output | The output looks like a unified diff (the same test as above) | Diff rendering |
| `read` result of `llms.txt` | The file name rule selects Markdown | The Markdown syntax |
| Everything else | | Plain text with no code colors |

**Language tags.** `LanguageResolver` takes the first word of the fence info string, in lowercase. It also accepts the forms `{java}`, `{.java}`, and `{r, echo=FALSE}` (see [Markdown and embedded languages](#markdown-and-embedded-languages)). It looks for the tag in this order: a syntax name, an alias, and a file name test with the name `x.<tag>`. The alias table covers common tags: `js`, `ts`, `py`, `sh`, `bash`, `shell`, `zsh`, `yml`, `rs`, `golang`, `c++`, `cpp`, `cs`, `kt`, `rb`, `md`, `jsonc`, `tf`, and `dockerfile`. The config key `ui.chat.highlight.aliases` adds more aliases. A tag with no match (`text`, `console`, `output`) gives plain text. A block that Jeorje cannot match is not guessed.

**Diff rendering.**

- Header lines (`diff`, `---`, `+++`, `index`) are bold. Hunk lines (`@@`) are cyan.
- A line that starts with `+` has a green tint on the whole line. A line that starts with `-` has a red tint. Context lines have no tint.
- The text after the marker has the colors of the syntax of the file. Jeorje takes the file from the `+++` header, or from the `edit` tool input, and selects the syntax by the file name. The tint stays under the syntax colors.
- Jeorje highlights the removed lines and the added lines as two separate streams. Each stream keeps its own state for multi-line regions. This keeps a comment that opens in a removed line from coloring the added lines.
- The markers and the tint use the colors of the editor color scheme. With fewer colors, the tint changes to a bold marker with no tint.

**Streaming.** Assistant text arrives token by token. Jeorje highlights each line of a block when the line is complete. A line in progress shows with the plain color until its newline arrives. The block keeps one highlighter, so multi-line regions stay correct. A fence that has not closed yet is highlighted as far as it has arrived.

**Read results.** The numbers on the left are not part of the text for the highlighter. A `read` that starts at a line other than 1 starts the highlighter with no open region, so a multi-line comment that began before the first line can show in the wrong color. This is an accepted limit.

**Limits and cost.**

- A block of more than 2,000 lines is highlighted for the first 2,000 lines only. The rest is plain, and the dim note `[Colors stop after N lines.]` shows the limit. The key `ui.chat.highlight.maxLines` changes the limit.
- Jeorje highlights on the thread that handles the agent events, not on the UI thread. The result is a cached list of styled lines for each block. A resize or a scroll uses the cache. A resize wraps the cached lines again and keeps their styles.
- A line of more than `maxLineChars` characters (2,000) goes to the highlighter cut at that length. The rest of the line is plain. This stops a slow regular expression on a very long line.
- `highlight()` is synchronous, and a Java regular expression cannot be interrupted. Jeorje therefore measures the time of each highlight pass. If a pass takes more than `budgetMs` (200 ms) two times in a row, Jeorje turns the colors off for that syntax for the rest of the run. It shows a notice and logs a warning one time. If a rule in a syntax file throws an exception, Jeorje shows the line with no colors and logs a warning one time.

**Display only.** The colors are in the chat view only. The history, the index, and the text that goes to the model have the original text with no colors. A copied selection has plain text. See [Mouse](#mouse).

These two limits are fixed in the chat view. They have no config key there.

**Config.** The keys `ui.chat.highlight.enabled`, `ui.chat.highlight.maxLines`, `ui.chat.highlight.aliases`, `ui.chat.highlight.diff`, `ui.chat.highlight.read`, and `ui.chat.highlight.fences` control the behavior. See [configuration.md](configuration.md). `NO_COLOR` turns all colors off. When the editor has `editor.syntax.enabled: false`, the chat view has no code colors either, but diff tints stay.

## Mouse

Jeorje supports the mouse in the chat view (the wheel), the editor, the file browser, the agent switcher, menus, and dialogs. The keyboard can do everything that the mouse can do. A terminal that has no mouse support still works with the keys.

### Input

- At startup, `MouseInput` calls `terminal.hasMouseSupport()`. If it returns `true`, `MouseInput` calls `terminal.trackMouse(Terminal.MouseTracking.Button)`. This mode sends press, release, wheel, and drag events. If the terminal has no mouse support, Jeorje shows one notice in the status line and does not use the mouse. If `ui.mouse.enabled` is `false`, Jeorje does not track the mouse.
- The JLine terminal finds the mouse prefix of the terminal. Jeorje reads each event with `terminal.readMouseEvent()` and sends it to the `RegionRegistry`. A `MouseEvent` has a type (`Pressed`, `Released`, `Wheel`, `Dragged`, `Moved`), a button (`Button1`, `Button2`, `Button3`, `WheelUp`, `WheelDown`), the modifiers (`Shift`, `Alt`, `Control`), and the `x` and `y` of the cell.
- Jeorje turns mouse tracking off with `trackMouse(Terminal.MouseTracking.Off)` when it exits, when the process gets the signals `INT`, `QUIT`, or `TSTP`, and in a shutdown hook. The signal `CONT` turns tracking on again. The editor turns tracking off while it is open and sets its own tracking. Jeorje turns tracking on again when the chat view returns.
- JLine has no click count. Jeorje counts a second and a third `Pressed` event on the same cell in 400 ms as a double click and a triple click.

### Regions

Each visible region registers its screen rectangle with the `RegionRegistry` of `jeorje-ui`. The registry sends an event to the region under the pointer, not to the region that has the keyboard focus. A drag that starts in a region stays with that region until the button is released, also when the pointer leaves the region. The interactive chat view registers one region, which handles the wheel. Panes and the editor read the mouse themselves.

### Scrolling

| Input | Result |
|-------|--------|
| Wheel up, wheel down | Scroll the region under the pointer by `ui.mouse.wheelLines` lines (default 3) |
| `Shift` with the wheel in the chat view | Scroll three times as far |
| `Shift` with the wheel in the editor | Scroll sideways |
| Click on the arrow of a scroll bar (editor, dialog lists, Show Changes) | Scroll one line |
| Click on the track of a scroll bar | Scroll one page toward the click |
| Drag the thumb of a scroll bar | Scroll to the position of the pointer |

- **Scroll moves the view only.** In the editor, the text cursor stays where it is, also when it is out of view. The next key press that moves the cursor or changes the text brings the view back to the cursor.
- **Split windows.** The wheel scrolls the editor window under the pointer. The other window does not move.
- **Lists.** In the file browser and the agent switcher, the wheel scrolls the list. It does not change the selected row.
- **Speed.** The value `ui.mouse.wheelLines` sets the number of lines for each wheel step.

### Chat view

- The wheel scrolls the chat view of the foreground agent. The view keeps the last 10,000 logical lines.
- A click does not select text, expand a result, or open a path in the chat view yet. See [Current limits](#current-limits).

### Panes

- In the agent switcher and the file browser, a click on a row selects it. A double click on a row runs the row (switch to the agent, open the file, or open or close the directory).

### Editor

- A click sets the text cursor. A drag selects text. A double click selects a word. A triple click selects a line.
- A click on a menu name opens the menu. A click on an item runs it. A click outside the menu closes it.
- A click on a dialog button presses it. A click on a text field puts the cursor in the field.
- `Shift` with a click extends the selection from the cursor.
- A drag above or below the window scrolls the window while the button is down. The speed depends on the distance of the pointer from the edge.
- The editor copies with its own clipboard. See [Files and the agent](#files-and-the-agent).

### Selecting and copying text

Mouse tracking replaces the native text selection of the terminal. Most terminals give the native selection back when the user holds `Shift` during the drag. The native selection copies the text with the borders of the screen.

The library has its own selection and clipboard classes. `ChatRegion` selects text in the chat view with a drag, a double click, and a triple click. `Clipboard` sends copied text to the clipboard of the terminal with the OSC 52 escape sequence. If the terminal does not support OSC 52, it keeps the text in an internal clipboard. The interactive launcher does not connect these classes yet. See [Current limits](#current-limits).

### Configuration

The keys `ui.mouse.enabled` and `ui.mouse.wheelLines` control the mouse. See [configuration.md](configuration.md). The key `ui.mouse.copyOnSelect` is part of the config, but it has no effect yet. The option `--no-mouse` turns the mouse off for one run. The key `ui.mouse.enabled: false` turns it off for all runs.

## Permission prompts

When the agent loop sends `ConfirmationRequested`, the UI shows a box with the agent name, the tool, and the input of the tool. The box has four choices:

| Key | Choice |
|-----|--------|
| `y` or `1` | Allow once |
| `s` or `2` | Allow for the session |
| `p` or `3` | Allow in the project |
| `n`, `d`, `4`, or `Esc` | Deny |

The up and down keys move the mark, and `Enter` chooses the marked line. The agent waits for the answer. Prompts from several agents queue, and the box shows the first one. "Allow in the project" adds an `allow` rule for the tool to the project config. See [configuration.md](configuration.md). See [permissions-and-safety.md](permissions-and-safety.md). The same box asks the trust question and the approval of a plan.

## Agent switcher

`Ctrl-X a` opens the agent switcher as a pane in the chat region. The pane has a title row and a list of agents and peers.

- Each row shows the agent name, parent, state, where the agent runs (`local` or the peer), the git branch, and the last output line.
- The up and down keys (and `k` and `j`) select a row. `Enter` makes the agent the foreground agent. `Esc` closes the pane. A click selects a row, and a double click switches to it.
- The foreground chat shows the history of the selected agent, then new output. See [subagents.md](subagents.md).
- A remote peer cannot become the foreground agent. Jeorje shows a notice.
- `/back` returns to the parent of the foreground agent.
- `/agents` with no argument prints the same list in the chat view. `/agents <name or number>` switches to that agent.

## File browser

`Ctrl-X f` opens the file browser as a pane in the chat region. It starts at the working directory.

- The browser shows a tree with directory names and file names. The up and down keys move. The right key opens a directory. The left key closes it. `Enter` on a directory opens or closes it.
- `Enter` on a file opens it in the editor. A double click on a file does the same.
- `a` adds the selected path to the next prompt. The next message then starts with a line `Files: <paths>`.
- `/` starts a filter by name. `Enter` ends the filter text, and `Esc` clears it.
- `.` shows or hides the ignored paths.
- `Esc` closes the browser.
- `/files [filter]` prints the tree in the chat view. `/files add <path>` adds a path to the next prompt.

The browser hides `.git/` and the paths in the `.gitignore` of the working directory unless the user presses `.`. It reads the names, the globs, a leading slash, and a trailing slash of the file. It does not read negation lines. This is a display filter. It is not a sandbox. The pane reads fixed keys. The `browser*` action ids of the config have no effect on it yet.

## Editor

The editor is a modeless, menu-driven text editor in the style of the MS-DOS QBasic editor. "Modeless" means that the editor has no command mode and no insert mode. Typed characters always go into the text. Commands use menus, Alt keys, function keys, and Ctrl keys.

The JLine 4 nano editor (`org.jline.builtins.Nano`) has nano key bindings and no menu bar. JLine does not support key rebinding in its nano and less implementations. The nano editor cannot give this behavior or the configurable hotkeys. Jeorje therefore has its own editor class, `QEditor`, in `jeorje-ui`. `QEditor` uses the JLine terminal, `Display`, and `KeyMap` from the `org.jline.terminal` and `org.jline.reader` modules, and `org.jline.builtins.SyntaxHighlighter` from the `org.jline.builtins` module. It does not use `Nano`.

### Opening the editor

`Enter` on a file in the file browser, or `/edit <path>`, opens the file. A path that does not exist opens an empty new file. The editor runs on the input thread and uses the full terminal. Mouse tracking of the chat view is off while the editor runs. When the user exits, the chat view returns. A file that is not text (a binary file) does not open, and Jeorje shows an error.

### Screen

```
 File  Edit  Search  View  Options  Help
+------------------ src/Main.java -----------------+
|                                                  ^|
|  (text area)                                     #|
|                                                  v|
+--------------------------------------------------+
 Ctrl-S=Save  F1=Help  F3=Repeat Find  F10=Menu    Ln 12 Col 5
```

- **Menu bar** (top line): `File`, `Edit`, `Search`, `View`, `Options`, and `Help`. Each menu name has one highlighted letter.
- **Text window** with a frame, the file name in the top border, and a vertical scroll bar on the right. A horizontal scroll bar shows when a line is wider than the window.
- **Status bar** (bottom line): the most useful keys for the current state (the keys of the actions `save`, `help`, `repeatFind`, and `menu`), and on the right the name of the syntax, the line, and the column of the cursor. `OVR` shows when overwrite mode is on. During a menu, the status bar shows a one-line help text for the selected item. A notice, for example `Saved Main.java.`, replaces the key hints until the next key. The bar uses a notice color when the file changed on disk.
- Colors are a light-on-dark scheme. The palette follows `NO_COLOR`. The menu `Options > Colors` switches to monochrome.
- The editor shows syntax colors. See [Syntax highlighting](#syntax-highlighting).

### Syntax highlighting

`QEditor` supports the syntaxes that JLine 4 supports in its nano editor. It uses the same engine, `org.jline.builtins.SyntaxHighlighter`. The engine reads rules in nanorc format.

**The JLine library has no syntax files.** The `jline-builtins` jar contains the nano help texts, but no `.nanorc` syntax files. Jeorje therefore embeds a large set of syntax files in the `jeorje-ui` jar, so highlighting works on every machine, with or without GNU nano.

**Embedded syntax set.** The resources are in `jeorje-ui/src/main/resources/org/github/lscoughlin/jeorje/ui/syntax/`. The set covers at least these languages and formats:

- **JVM:** Java, Kotlin, Scala, Groovy, Gradle, Maven POM, properties
- **C family:** C, C++, C#, Objective-C, Go, Rust, Swift, Dart, Zig
- **Scripting:** Python, Ruby, PHP, Perl, Lua, R, Julia, Tcl
- **Web:** JavaScript, TypeScript, JSON, HTML, CSS, SCSS, XML, GraphQL
- **Data and config:** YAML, TOML, INI, SQL, Dockerfile, Terraform and HCL, Nix, protobuf, nanorc, git config
- **Shell:** sh, bash, zsh, fish, PowerShell, Makefile, CMake
- **Functional and others:** Haskell, OCaml, Elixir, Erlang, Clojure, Lisp, Guile
- **Low level and legacy:** assembly, Pascal, Fortran
- **Text:** Markdown, LaTeX, groff, man pages, diff and patch, ChangeLog, email, log files, Vim script

The set can grow. A new file does not need a code change, because `SyntaxCatalog` reads every `.nanorc` file in the directory.

**Source of the files.** The embedded set comes from the `scopatz/nanorc` project (`https://github.com/scopatz/nanorc`). It has more than 100 syntax files. Each embedded file keeps its original header. The upstream project has no file for some languages, for example Objective-C, Dart, R, Julia, GraphQL, Nix, protobuf, Erlang, Guile, Pascal, and ChangeLog. Jeorje wrote simple files for them (keywords, strings, comments, and numbers). The file `syntax/SOURCES.md` in the same directory records the source project, the revision (commit) that the files came from, and the written files. The build script `scripts/update-syntax` copies the `.nanorc` files of a chosen revision into the directory and updates `SOURCES.md`. It keeps the written files. Jeorje does not change the files after the copy, so an update is a new copy. The licenses of the files are not part of this design.

**Extraction.** JLine reads syntax files from the file system. At the first start of the editor, `SyntaxCatalog` writes the embedded files to `${HOME}/.cache/jeorje/syntax/<jeorjeVersion>/`. The version is the implementation version of the Jeorje jar, or `dev`. It writes to a temporary directory and renames it, so a stop in the middle of the write leaves no partial set. A later start with the same version reuses the directory. A new Jeorje version writes a new directory and removes the old ones. This method works with the shaded jar and with a native image.

**One syntax for each file.** JLine reads only the first `syntax` line of an included file. During the extraction, Jeorje therefore splits an embedded file that has several `syntax` lines into one file for each syntax. Each part keeps the header comment of the original file, and it is named `<file>.<syntax>.nanorc`. The catalog lists only the first syntax of each file. For a file in a project, user, config, or system directory, only the first `syntax` line counts.

**Search order.** The first directory that defines a syntax name wins.

1. `${CWD}/.jeorje/syntax/` (project)
2. `${HOME}/.jeorje/syntax/` (user)
3. The directories in `editor.syntax.dirs` (config)
4. The directories of an installed GNU nano: `/usr/share/nano`, `/usr/local/share/nano`, and `/opt/homebrew/share/nano`. The key `editor.syntax.systemDirs: false` turns this step off.
5. The embedded set in the cache directory. The key `editor.syntax.embedded: false` turns this step off.

Jeorje reads all files with the extension `.nanorc` in each directory. A project or user file can replace an embedded syntax. The embedded set is the base, so the editor shows the same languages on every machine.

**Rules that JLine 4 reads.** The `SyntaxHighlighter` parser reads these nanorc elements: `syntax`, `color`, `icolor`, `start=` and `end=` (multi-line regions), `include`, `theme`, and the settings `$BLOCK_COMMENT`, `$LINE_COMMENT`, and `$BALANCED_DELIMITERS`. It does not read the nano keywords `header` and `magic`. Jeorje ignores these keywords and logs a warning one time for each file when it loads the catalog. A syntax that depends on them still works for file-name matching.

**How the editor uses the API.**

- `SyntaxCatalog` in `jeorje-ui` reads the first `syntax` line of all nanorc files. It records the syntax name and the file-name patterns of each syntax.
- To open a file, the catalog chooses a syntax by matching the file name against these patterns. A pattern that matches at the end of the name wins over a pattern that matches only inside the name. Inside each group, the search order decides.
- The public factory `SyntaxHighlighter.build(Path nanorc, String syntaxName)` reads a main config (a `jnanorc` file) and follows its `include` lines. It does not read a syntax file directly. For each syntax, Jeorje therefore writes a one-line main config, `include "<syntax file>"`, to a temporary directory, and passes that file to the factory. Jeorje removes the directory at exit. The overload that takes a file name is protected, so Jeorje does not use it.
- For each visible line, `QEditor` calls `highlight(AttributedString)` and draws the result with `Display`.
- **State.** The highlighter keeps the state of multi-line regions inside the object. The API has only `highlight`, `reset`, `setCurrentTheme`, `getCurrentTheme`, and `refresh`. It cannot save or restore a state. `QEditor` therefore uses replay:
  - `QEditor` keeps one `SyntaxHighlighter` object for each syntax, and calls `reset()` before each replay.
  - The editor caches the styled result of each line it highlighted. Scrolling down continues from the last highlighted line. Scrolling up uses the cache.
  - After a jump to a line that has no cache, the editor calls `reset()` and highlights from `max(start of the file or block, first visible line - replayLines)` down to the last visible line. The default of `replayLines` is 2,000. If a region opened before the replay start, the lines below can show the wrong color until the user scrolls. This is an accepted limit.
  - After an edit, the editor drops the cache from the edited line down and highlights again when the lines are visible.
- `setCurrentTheme(Path)` and the `theme` keyword select a color theme. `refresh()` reloads the rules after the user changes a syntax file.

**Behavior.**

- **No match.** If no syntax matches the file name, the editor shows plain text.
- **Syntax menu.** `Options > Syntax` lists all syntaxes that the catalog found. The user can choose one for the open file. `Auto` returns to the automatic choice.
- **Status bar.** The status bar shows the name of the syntax in use.
- **Errors.** A file that fails to parse is skipped. Jeorje logs a warning that names the file and the line.
- **Colors.** The color names of the nanorc files map to the editor color scheme. If the terminal has fewer colors, the highlighter uses the closest color. `NO_COLOR` turns syntax colors off.
- **Large files.** For a file of more than 5 MB (the default of `editor.syntax.maxBytes`), the editor turns syntax colors off and shows a notice. The config key `editor.syntax.maxBytes` changes the limit.
- **Config.** The keys `editor.syntax.enabled`, `editor.syntax.maxBytes`, `editor.syntax.dirs`, `editor.syntax.systemDirs`, and `editor.syntax.embedded` control the behavior. See [configuration.md](configuration.md).

A JLine upgrade can change the rules that the parser reads. The test for this section runs the syntax files of a fixed test set and shows which rules the pinned JLine version reads.

### Markdown and embedded languages

A `.nanorc` syntax colors a file with one language. A Markdown file contains other languages in fenced code blocks. `QEditor` therefore has an injection layer, `MarkdownHighlighter`, in `jeorje-ui`. It colors the Markdown text with the Markdown syntax and the content of each fenced block with the syntax of the language of the block.

**When it applies.** The layer runs when the syntax of the open file is the Markdown syntax (a `.md`, `.markdown`, or `.mdx` file, or a syntax that the user chose in the Syntax menu). The config key `editor.syntax.injections: false` turns it off.

**Fence scanner.** `MarkdownRegions` reads the lines and records, for each line, the region type: Markdown text, fence line, fenced content, or front matter.

- A fence opens with three or more backticks (the usual three-backtick block) or tildes, with up to three spaces of indent. The scanner also accepts a fence inside a list item or a block quote.
- A fence closes with the same character, at least as many characters as the opening fence, and nothing else on the line. A fence that does not close continues to the end of the file.
- A fence in a fence is not a fence. The content stays in the outer block. The language `markdown` in a fence is highlighted as Markdown, but the scanner does not look for fences inside it.
- The block at the top of the file between two `---` lines, if it is a YAML mapping, is highlighted with the YAML syntax. This is the same test as for the front matter in the `read` tool.

**Language of a block.** The scanner takes the info string of the opening fence. It uses these steps:

1. Remove a leading `{` and a trailing `}`, and a leading `.` (the forms ```` ```{java} ````, ```` ```{.java} ````, and ```` ```{r, echo=FALSE} ````).
2. Take the first word. Stop at a space, a comma, or `=`. Write it in lowercase.
3. Resolve the word with the same rules as in the chat view: a syntax name, then an alias, then the file name test with `x.<word>`. See [Syntax highlighting in the chat view](#syntax-highlighting-in-the-chat-view). A shared class, `LanguageResolver`, does this for the editor and for the chat view.
4. If the word has no match, the content of the block has no code colors. It uses a single dim color to show that it is code.

**Drawing.**

- Fence lines (the opening and closing lines, with the info string) use the Markdown colors for code markers. The language word has a separate color, so the user can see that Jeorje recognized it, and a dim color if the word has no match.
- Each fenced block starts with `reset()` on the highlighter of its language, so the state for multi-line regions starts new in each block. A comment in one block does not reach the next block or the Markdown text.
- The Markdown syntax colors the lines outside the blocks. It does not see the content of the blocks.

**Editing.** Text changes can open, close, or change a fence. The scanner updates the region map in steps:

1. It scans again from the first changed line back to the nearest fence line above it.
2. It continues down until the region type of a line is the same as before the change. Lines below that point keep their data.
3. For a visible fenced block, the highlighter for the language of the block calls `reset()` and highlights from the first line of the block, or from `first visible line - replayLines` in a long block, down to the last visible line. The editor keeps one highlighter for each language and calls `reset()` at the start of each block. It does not make a new highlighter for each block.

When the user types an opening fence with a language, the content below it changes color at once. When the user closes the fence, the colors below it return to the Markdown colors.

**Limits.** The scanner and the highlighters run for the visible lines and for the replay lines above them. Files of more than `editor.syntax.maxBytes` have no colors. A block of more than 5,000 lines is highlighted from the visible part only. The line length limit and the time budget of the editor apply.

**More languages in one file.** `MarkdownHighlighter` is one case of an `InjectionRule`. An `InjectionRule` tells `QEditor` how to find the regions of another language in a file. Jeorje has only the Markdown rule. The same interface can later describe, for example, scripts in HTML.

### Show changes

`View > Show Changes` shows the difference between the text in the editor and the file on disk. It splits both texts with `SplitText` and calls `DiffRowGenerator` directly (see [tools.md](tools.md#diff-library)). It does not use `TextDiff`. The view has two columns. Changed lines have a tint, and the changed words in a line are marked (inline diff by word). The user can scroll the view with the keys and with the mouse. `Esc` returns to the text.

When the agent changes the open file, the notice in the status bar also offers `Show Changes`. The user can see the difference between the unsaved text and the new file before the user chooses Reload or Keep.

### Menus

A menu opens with `Alt` plus the highlighted letter (for example `Alt-F`). `F10` opens the menu bar at the first menu. `Alt` alone does not open the menu bar. The left and right keys change the menu, and the up and down keys select an item. Each item also has a highlighted letter. `Enter` runs the selected item. `Esc` closes the menu. Items show their shortcut keys on the right. These keys come from the effective key bindings.

| Menu | Items |
|------|-------|
| File | Save (`Ctrl-S`), Save As, Revert, Reload (only when the file changed on disk), Close and return to chat (`Alt-X`) |
| Edit | Undo (`Ctrl-Z`), Redo (`Ctrl-Y`), Cut (`Shift-Del`), Copy (`Ctrl-Ins`), Paste (`Shift-Ins`), Clear (`Del` with a selection), Select All |
| Search | Find (`Ctrl-Q F`), Repeat Last Find (`F3`), Change (find and replace, `Ctrl-Q A`), Go To Line (`Ctrl-G`) |
| View | Line Numbers, Wrap Long Lines, Split Window (see below), Show Whitespace, Show Changes |
| Options | Tab Width, Use Spaces For Tabs (on by default, width 4), Syntax Colors (on or off), Syntax (choose a syntax for this file), Colors |
| Help | Keys (`F1`), About the Editor |

`Alt-X` is the same key as the exit key of QBasic. `Ctrl-Q F` and `Ctrl-Q A` follow the QBasic and WordStar key style. If the terminal does not send Alt keys, the user can open the menu bar with `F10`.

### Editing keys

The keys follow the QBasic editor. These are the default keys. The config can change all of them. See [configuration.md](configuration.md#key-bindings):

| Key | Action |
|-----|--------|
| Arrow keys, `Home`, `End`, `PgUp`, `PgDn` | Move the cursor |
| `Ctrl-Left`, `Ctrl-Right` | Move by word |
| `Ctrl-Home`, `Ctrl-End` | Go to the start or the end of the file |
| `Shift` with any movement key (including `Ctrl-Left`, `Ctrl-Right`, `Ctrl-Home`, and `Ctrl-End`) | Select text. Jeorje derives these keys from the movement keys. |
| `Ins` | Switch between insert and overwrite for typed characters. The status bar shows `OVR` in overwrite mode. |
| `Del`, `Backspace` | Delete the character, or the selection |
| `Tab`, `Shift-Tab` | Indent or unindent the line or the selected lines |
| `Ctrl-Y` and `Ctrl-Z` | Redo and undo (the Edit menu shows the keys) |
| `Shift-Del`, `Ctrl-Ins`, `Shift-Ins` | Cut, copy, paste |
| `F3` | Repeat the last find |
| `F1` | Help |

The editor has no command mode. A typed character always goes into the text. The mouse works in the editor. See [Mouse](#mouse).

Some terminals send `Ctrl-Ins`, `Shift-Ins`, and `Shift-Del` as other keys. In this case, the editor also accepts `Ctrl-C`, `Ctrl-X`, and `Ctrl-V` for copy, cut, and paste. Copy and cut work when a selection exists. Paste works when the clipboard is not empty. These three keys, `Enter`, and `Esc` are fixed keys. A configured key wins over a fixed key. `Esc` clears the selection. The Edit menu shows which keys are active.

### Dialogs

Dialogs, such as Find, Save As, and Go To Line, are boxes in the middle of the screen with text fields and buttons. `Tab` moves between fields. `Enter` chooses the default button. `Esc` cancels. A confirm dialog appears when the user closes a file with unsaved changes: Save, Discard, or Cancel.

### Split window

`View > Split Window` shows two text windows. This is the same idea as the split view of QBasic. The windows can show two places in one file, or two files. `F6` moves the cursor between the windows.

### Files and the agent

- The editor saves a file in the same way as the `write` tool: it writes a temporary file in the same directory and moves it into place, with an atomic move when the file system allows it. It keeps the permissions of the file and the line end style of the file (LF or CRLF). See [tools.md](tools.md).
- When the agent changes the open file on disk, the editor shows a notice in the status bar and offers Reload in the File menu and Show Changes in the View menu. If the editor text has unsaved changes, Reload asks for a confirmation (Reload, Show Changes, or Cancel). On a save, the editor checks the file again. If the file changed on disk, a dialog asks to Overwrite, Show Changes, or Cancel. The editor never overwrites a file that changed on disk without this dialog.
- The editor copies to its own in-memory clipboard. It does not send the text to the clipboard of the terminal yet.
- After a save, Jeorje records the new content hash of the file in the file-state table of the foreground agent. See [tools.md](tools.md).
- The editor does not call a tool and does not use the permission policy. The user edits the file directly.

## Key bindings

| Key | Action |
|-----|--------|
| `Enter` | Send the input |
| `Alt-Enter` | New line in the input |
| `Ctrl-C` | Interrupt the foreground agent. Press it a second time within 2 s to exit. |
| `Ctrl-D` on an empty line | Exit |
| `Ctrl-X a` | Open the agent switcher |
| `Ctrl-X f` | Open the file browser |
| `Ctrl-X o` | Expand or collapse the last tool result |
| `Ctrl-L` | Redraw the screen |
| `Ctrl-X b` | Jump to the bottom of the chat view |
| `Ctrl-X c` | Copy the selected text in the chat view (no effect yet. See [Current limits](#current-limits).) |
| `PgUp`, `PgDn` | Scroll the chat view by a page |

The input line keeps the JLine emacs key bindings, such as `Ctrl-A` (start of line) and `Ctrl-F` (forward one character). Jeorje uses the `Ctrl-X` prefix for its own commands, so they do not replace the editing keys. Every key in this section is a default. The config can change all of them, except for the fixed keys. See [configuration.md](configuration.md#key-bindings) for the notation, the rules, and the action ids.

## Slash commands

| Command | Action |
|---------|--------|
| `/agents [name\|number]` | Print the list of agents, or switch to one. `Ctrl-X a` opens the switcher pane. |
| `/back` | Return to the parent agent |
| `/cancel [name]` | Cancel the foreground agent or a named subagent |
| `/files [filter\|add <path>]` | Print the file tree, or add a path to the next prompt. `Ctrl-X f` opens the browser pane. |
| `/edit <path>` | Open a file in the editor |
| `/compact` | Run compaction now |
| `/compactor [slug\|off]` | Show or set the compactor model for this session |
| `/advisor [slug\|off]` | Show or set the advisor model for this session |
| `/plan [goal\|show\|edit\|done\|cancel]` | Enter, show, edit, or leave plan mode. See [planning.md](planning.md). |
| `/implement [plan] [stories]` | Implement a plan with a team of subagents. Also `pause`, `resume`, `status`, and `stop`. See [planning.md](planning.md). |
| `/worktrees [prune]`, `/merge <agent> [strategy]`, `/discard <agent>` | List, merge, and remove subagent worktrees. See [subagents.md](subagents.md#worktrees). |
| `/pin` | Pin the last assistant message |
| `/recall <query>` | Search the history and show the best five hits |
| `/peers` | List swarm peers |
| `/init`, `/instructions` | Ask the agent to write an `AGENTS.md`, or show the user files and the project file that apply. See [instructions-and-skills.md](instructions-and-skills.md). |
| `/skills`, `/commands`, `/agent-types`, `/trust` | List skills, custom commands, and subagent definitions, and trust the project |
| `/<skill-name> [args]`, `/<command-name> [args]` | Start a skill or a custom command from `.agents/` |
| `/model [slug]` | Show or set the default model for this session |
| `/keys` | Show the effective key bindings |
| `/help` | Show the commands, the custom commands, the skills, and the warnings about names that clash |
| `/quit` | Exit |

## Command line

picocli parses the command line. `JeorjeMain` is the entry point.

| Command | Purpose |
|---------|---------|
| `jeorje` | Start the interactive UI in the current directory |
| `jeorje run "<task>"` | Run a task with no UI and print the final result |
| `jeorje --resume [session]` | Start the interactive UI on an earlier session. The default is the latest session. |
| `jeorje resume [session] [--task "<text>"]` | Resume a session in the UI. With `--task`, continue the session with no UI and print the final answer. |
| `jeorje index rebuild [--session <id>]` | Build the index again from the transcript of a session. The default is the latest session. |
| `jeorje config [--format yaml\|json] [--init] [--show-prompts] [--keys]` | Print the effective config, write a new `config.yaml`, print the assembled prompts, or print the effective key bindings |

Common options: `--dir`, `--no-mouse`, `--model`, `--base-url`, `--peer host:port` (repeatable), `--swarm-token`, `--yes`, `--yolo`, `--trust`, and `--config`. They can stand before or after the command name. The option `--trust` trusts the project config of the directory for this run. See [configuration.md](configuration.md#precedence) for the config keys that the options set. `jeorje config --show-prompts` prints the assembled system prompt and compaction prompt. `--yes` approves all prompts. `--yolo` turns off all permission checks. See [permissions-and-safety.md](permissions-and-safety.md#unattended-modes).

## Terminal handling

- The UI uses the JLine terminal type detection. If the process has no terminal, `jeorje` prints a hint to use `jeorje run` and exits with code 0. `jeorje run` is then the only supported mode.
- The UI uses the alternate screen. It handles window resize with the JLine `WINCH` signal, and with in-band resize reports when the terminal supports them.
- Jeorje enters raw mode and clears the input flags `IXON` and `IXOFF` with `terminal.getAttributes()` and `setAttributes(...)`. Software flow control then does not freeze the terminal when the user presses `Ctrl-S` or `Ctrl-Q`. The terminal returns to its old attributes, the normal screen, and the old keyboard mode at exit and in a shutdown hook.
- Jeorje turns on the Kitty keyboard protocol according to `ui.keyboardProtocol`. See [configuration.md](configuration.md#key-bindings).
- Colors follow `NO_COLOR` and the terminal capabilities.
- All logs go to a file. The UI never writes logs to the terminal. See [configuration.md](configuration.md).

## Current limits

The `jeorje-ui` module has some parts that the interactive launcher in `jeorje-cli` does not connect yet. These parts have no effect in the chat view.

- **Chat highlighting.** The launcher makes the screen with no highlighting context. The chat view therefore shows no syntax colors and no diff tints. The keys `ui.chat.highlight.*` have no effect.
- **Chat selection and copy.** `ChatRegion`, `Selection`, and `Clipboard` (OSC 52) exist. The chat view has no text selection. The action `copySelection` does nothing. The key `ui.mouse.copyOnSelect` has no effect. Click handlers for tool results and file paths (`LineClick`) are not connected.
- **Follow mode and older lines.** `ChatScroll` and `TranscriptOlderLines` implement the follow mode, the notice `N new lines - Ctrl-X b or click here to follow`, and the loading of older lines from `transcript.jsonl`. The chat view does not use them. The action `follow` scrolls the view to the bottom.
- **Key bindings of the file browser.** The pane reads fixed keys. The `browser*` actions do not change it.
- **Completion.** `SlashCommands.complete` is not connected to the input line.
- **Editor clipboard.** The editor uses an in-memory clipboard and does not set `copyOnSelect`.
