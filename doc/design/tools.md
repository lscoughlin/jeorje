---
title: Tools
description: The Tool interface, the registry, limits, and the built-in tool set for files, shell, web, search, tasks, recall, and agents.
---

# Tools

Tools are in `jeorje-tools`. They implement the `Tool` port from `jeorje-core`. [permissions-and-safety.md](permissions-and-safety.md) describes permissions.

## Interface

```java
public interface Tool {
    ToolDefinition definition();            // name, description, JSON schema for input
    boolean readOnly();                     // can run at the same time as other read-only calls
    ToolResultData execute(JsonNode input, ToolContext context) throws Exception;
}

public record ToolContext(Path workingDirectory, AgentId agent, CancellationToken cancel,
                          ContextStore history, Clock clock) {}
```

`ToolDefinition` has a name, a description for the model, and a JSON Schema. Jackson 3 has no schema generator. `SchemaGenerator` makes the schema from the input record of the tool with reflection.

- It maps strings, numbers, booleans, enums, lists, maps, nested records, and `JsonNode` (any value).
- Every field is optional unless the record component has `@Required`. `@Describe` adds a description. `@Allowed` adds an `enum`.
- The schema sets `additionalProperties` to `false`.

`InputValidator` checks the input against the schema before `execute`. It supports `type`, `required`, `properties`, `additionalProperties`, `items`, `enum`, `minimum`, `maximum`, `minLength`, and `maxLength`. If validation fails, the registry returns an error result and does not call the tool. `RecordTool` is the base class of the typed tools. It converts the input to the record and rejects unknown properties.

## Registry

`ToolRegistry` holds the tools by name. A name can be used one time. The CLI builds the registry at startup from the file, search, shell, web, recall, and todo tools and from the configured extension tools. The CLI adds the tools that need session objects (for example `skill`, `advisor`, and the agent tools) beside the registry. The registry gives the model the tool list in registration order.

A registry can make restricted copies of itself: `only` (named tools), `without` (all except named tools), `readOnlyTools`, and `constrain` (a fixed set of values for one string property). See [subagents.md](subagents.md).

`execute` never throws. An unknown tool, invalid input, a timeout, a cancellation, and an exception of the tool all give an error result. The error text of an unknown tool lists the available tools.

## Output limits

- The default cap for a tool result is 30,000 characters. If a result is cut, the registry adds a note. The note gives the number of characters shown and tells the model how to get more: use the offset and limit parameters, narrow the request, or use `recall` for the stored chunk.
- A tool result that has a zero byte becomes an error result. Tools that read files refuse binary files. A file is binary if it has a zero byte in its first 8,000 bytes or bytes that are not valid UTF-8.
- Each tool has a timeout. The default is 120 s. A tool can set its own timeout with the `TimedTool` interface, and the builder can set a timeout for one tool. On timeout, the registry interrupts the tool and returns an error result. `bash` handles its own timeout: the `timeout` input is 120 s by default and 600 s at most.

## Built-in tools

| Tool | Read-only | Purpose |
|------|-----------|---------|
| `read` | yes | Read a file with line numbers. Use `offset` and `limit` for a range. |
| `write` | no | Create a file or replace its content. |
| `edit` | no | Replace an exact string in a file. It fails if the match is missing or not unique, unless `replaceAll` is set. |
| `diff` | yes | Compare two files, or a file and given text, and return a unified diff. See [diff](#diff). |
| `patch` | no | Apply a unified diff (one or many files) to the workspace, all or nothing. See [patch](#patch). |
| `ls` | yes | List a directory. Directories come first. Use `offset` and `limit` for a range. |
| `glob` | yes | Find files with an Ant-style pattern. Sort by modification time, newest first. |
| `grep` | yes | Search file content with a regular expression. Give context lines, a file filter, and an output mode (`content`, `files`, or `count`). |
| `bash` | no | Run a shell command in the working directory. It has a timeout, captured output, and a background mode. |
| `webfetch` | yes | Fetch a URL, convert HTML to markdown text, and refuse a response over the size cap. |
| `todo` | no | Keep the task list of the agent. Each call replaces the list. The context classifier protects the chunk of this tool. |
| `recall` | yes | Search the history of the session, or read a chunk. It covers the chunks of all agents of the session and never the chunks of another session. See [context-and-compaction.md](context-and-compaction.md#recall). |
| `ask_user` | no | Ask the user a question. It can give choices. |
| `spawn_agent` | no | Start a subagent. It can use a `type` from a subagent definition. See [subagents.md](subagents.md) and [instructions-and-skills.md](instructions-and-skills.md#subagent-definitions). |
| `skill` | yes | Load the body of a skill. See [instructions-and-skills.md](instructions-and-skills.md#skills). |
| `agent_message` | no | Send a message to a subagent or peer. See [swarm.md](swarm.md). |
| `agent_ask`, `agent_answer` | no | Ask another agent a question and wait, or answer a question that came in. See [subagents.md](subagents.md#talking-to-other-agents). |
| `story_read` | yes | List the stories of the plan, or read one story. Only for `/implement` workers. See [planning.md](planning.md#story-visibility). |
| `agent_status` | yes | List subagents and peers with their state. |
| `sync_done`, `sync_abort` | no | End the conflict resolution of a subagent after the sync from the source branch. Only in the `RESOLVING` state. See [subagents.md](subagents.md#completion-sequence). |
| `worktree_merge`, `worktree_discard` | no | Run the completion sequence for a subagent in manual mode, or remove the worktree branch of a subagent. See [subagents.md](subagents.md#worktrees). |
| `plan_submit` | no | Check a plan and write it to the plan directory. Only in plan mode. See [planning.md](planning.md). |
| `task_done`, `story_done`, `story_blocked` | no | Report progress of a story. Only for `/implement` workers. See [planning.md](planning.md#workers). |
| `advisor` | yes | Ask the advisor model for a review of the conversation. Jeorje registers it only when an advisor model is set. |

The tools `ls`, `glob`, and `grep` skip `.git` and `.jeorje` directories. They also skip each file that the permission policy does not allow. A search tool cannot ask the user, so it treats `ASK` like `DENY`. Other tools refuse a call only when the policy returns `DENY`. The agent loop handles `ASK`. See [permissions-and-safety.md](permissions-and-safety.md).

### read

Input: `path`, and optional `offset` and `limit`. `offset` is a line number that starts at 1. `limit` is 2,000 lines by default. `read` cuts a line of more than 2,000 characters. When lines remain, the output ends with a note that gives the next `offset`. A directory is an error, and the message points to `ls`.

Output: three parts, in this order: directory context, front matter, and the numbered lines. A read records the modification time and the content hash of the file in the file-state table of the agent.

#### Front matter

Before it returns the content, `read` checks the file for front matter. Front matter is a short block of YAML at the start of a file. It describes the file.

1. `read` scans the first 25 lines of the file. It looks for a line that is `---` after an optional comment prefix and spaces.
2. The comment prefix lets source code carry front matter. Jeorje accepts these prefixes: `//`, `#`, `*`, `--`, `;`, `%`, `<!--`, and `/*`. The prefix `--` needs a space or tab after it. A line such as `---` is therefore not a marker with the prefix `--`. The marker can therefore start after a license header, a shebang, a `package` line, or an opening comment line. Example: `// ---` in Java, `# ---` in a shell script.
3. When `read` finds the opening marker, it looks for the closing `---` that has the same comment prefix. The block can have at most 40 lines. The 40 lines include both marker lines. If no closing marker is in that range, `read` finds no front matter and goes on.
4. `read` removes the comment prefix from each line of the block and parses the lines as YAML. Jeorje accepts the block as front matter only if the YAML is a mapping (key-value pairs) with at least one key. In all other cases, `read` finds no front matter. It adds no note and goes on to the next marker in the scan range, if any.
5. If the block is front matter, `read` returns the lines as plain text in a `front matter` part. It does not change the numbered lines. The front matter stays in the numbered content also.

A bare `---` is common in files that have no front matter. Examples: the underline of a Markdown heading, a horizontal rule, and the `---` document marker of a YAML file. The mapping rule makes `read` ignore these.

`read` does not check front matter in `.yaml`, `.yml`, and `.json` files, because these files are data. It checks front matter only when it reads from line 1 (no `offset`, or `offset` is 1). The scan is cheap because it reads only the first lines.

#### Directory context

For the file it reads, `read` also looks for `AGENTS.md` (not the one in the workspace root, which is in the system prompt), `README.md`, `llms.txt`, and `llm.txt` in the directory roots on the path to the file. These are the directory of the file and each parent directory up to the workspace root. The `tools.read.directoryDocs` key sets the list of names.

- In each of these directories, `read` looks for the names exactly, in the order of the list. `llms.txt` is the usual name of the file. `llm.txt` is also accepted. It never looks outside the workspace.
- `read` skips the file that it reads. It skips a document that it cannot read or decode.
- For each file that exists, `read` returns the path and the content with the same front matter check as above. If the file has front matter, `read` returns the front matter. If not, `read` returns the first 20 lines (`tools.read.directoryDocFallbackLines`). Each file is cut at 2,000 characters (`tools.read.directoryDocChars`). A cut text ends with `[cut]`. `read` reads at most 256 KB of a document.
- The directory context goes before the front matter of the file. Jeorje orders it from the workspace root to the directory of the file.
- Each agent gets the context of a directory one time. `read` marks a directory as delivered only when it returned at least one document for it. When the agent reads another file in the same directory, `read` does not return the same context again. The agent state is in memory. A new run of the agent starts with an empty set, so the agent gets the context again. A pruned chunk does not reset the set.
- If none of these files exists in any of these directories, `read` returns no directory context.

`read` does not write to the file-state table or to the directory-context set. Calls can run at the same time. The tool returns the updates in `ToolResultData`. The agent loop applies them in order after the calls end. The agent owns both structures.

The directory context and the front matter are part of the result. Compaction prunes them like other tool output. Their size counts toward the output limit of the result.

The `tools.read` config keys set the scan range, the block limit, the document names, and the cuts. See [configuration.md](configuration.md).

### write and edit

`write` and `edit` refuse to change an existing file in two cases: the agent did not read the file in this session, or the content of the file changed on disk after the read. Jeorje compares the SHA-256 hash of the content. The file-state table also records the modification time, but the check does not use it. A file that has the same content with a new modification time is not stale. The error tells the model to read the file again. `write` needs no earlier read for a new file. `write` and `edit` keep the line ends of the file: a file with `\r\n` stays `\r\n`.

A write is atomic. The tool writes the content to a temporary file in the same directory. It copies the file permissions of an existing file. It then moves the file into place. `edit` and `write` return a unified diff of the change with the number of added and removed lines. Jeorje makes the diff with the diff library. See [Diff library](#diff-library). `edit` needs a non-empty `oldString` that differs from `newString`.

### Diff library

The `diff` tool, the `patch` tool, the diffs of `edit` and `write`, and the change view of the editor use the library **java-diff-utils** (`io.github.java-diff-utils:java-diff-utils`, Apache License 2.0, package `com.github.difflib`). A facade, `TextDiff` in `jeorje-core`, is the code that the tools call. The editor view `DiffView` in `jeorje-ui` also uses the library for its rows.

The library does the diff generation and the inline rows. Jeorje has its own code for reading and applying diffs. The library parser and the library patch applier do not give the multi-file input, the offsets, the fuzz, and the conflict markers that `patch` needs.

| Need | Code |
|------|------|
| Compare two lists of lines | `DiffUtils.diff(original, revised, algorithm, listener)`. The algorithm is `MyersDiff` (default) or `MyersDiffWithLinearSpace`. Both take an equality function for the white space modes. The listener stops the work when the time limit passes. |
| Unified diff text | `UnifiedDiffUtils.generateUnifiedDiff(originalName, revisedName, originalLines, patch, contextSize)` |
| Inline rows | `DiffRowGenerator` with inline diffs by word |
| Read a unified diff for many files | `UnifiedDiffParser` (Jeorje code). It gives a `FileDiff` for each file. |
| Apply a diff | `PatchApplier` (Jeorje code). It applies the hunks in memory, with offset, fuzz, and conflict markers. |
| Undo a diff | `FileDiff.reversed()` (Jeorje code) swaps the two sides. |

Jeorje does not use the optional `java-diff-utils-jgit` module, so the histogram algorithm is not available. Jeorje does not add a JGit dependency.

The library works on lists of lines. `TextDiff` handles the parts that the library does not handle:

- **Line ends.** `SplitText` splits a file into lines with no line terminator, remembers the terminator of each line and the dominant terminator (`\n` or `\r\n`), and puts them back when it writes. A file with mixed terminators keeps the terminator of each line that did not change.
- **Final newline.** `SplitText` records if the file ends with a newline. In a generated diff `TextDiff` writes the line `\ No newline at end of file` after the last line that has no newline. When it reads a diff, it understands that line.
- **Limits.** Each side can have at most `maxLines` lines (default 50,000). The facade can also stop a diff after a time limit. The tools use the default line limit and set no time limit in the facade. The timeout of the registry stops a slow tool. A larger input returns an error that says so.
- **Binary files.** A file with a zero byte in its first 8,000 bytes, or with bytes that are not valid UTF-8, is refused with a message.
- **Diff reading.** `UnifiedDiffParser` reads `diff --git` headers, `--- ` and `+++ ` headers, hunks, `new file mode`, `deleted file mode`, and the no-newline line. It ignores a time stamp after a tab in a file name. It rejects renames, copies, mode changes, and binary changes with an error.

### diff

Input:

| Field | Meaning |
|-------|---------|
| `a` | Path of the first file |
| `b` | Path of the second file. Use `b` or `bContent`. |
| `bContent` | Text to compare with `a`, in place of a second file |
| `context` | Lines of context around each change (default 3) |
| `ignoreWhitespace` | `none` (default), `trailing`, or `all` |
| `algorithm` | `myers` (default) or `myers-linear` |
| `format` | `unified` (default) or `inline` (changed words marked, for reading) |

Exactly one of `b` and `bContent` is required. A missing `b` file is an error. The `context`, `ignoreWhitespace`, and `algorithm` values that the call does not set come from the defaults of `TextDiff` (3, `none`, `myers`).

Output for `unified`: the line `+<added> -<removed>`, then a unified diff with the headers `--- a/<path>` and `+++ b/<path>`. With `bContent`, both headers use the path of `a`. For equal texts the output is `no differences`.

Output for `inline`: one row for each changed line, or `no differences`. A deleted line is `- <number>: <text>`. An added line is `+ <number>: <text>`. A changed line gives both rows. Removed words are marked `[-word-]` and added words are marked `{+word+}`.

The tool is read-only. The permission policy checks the paths, and the tool refuses a path on `DENY`. The chat view shows the result with diff rendering. See [ui.md](ui.md#syntax-highlighting-in-the-chat-view).

### patch

Apply a unified diff to files in the workspace.

Input:

| Field | Meaning |
|-------|---------|
| `diff` | The diff text. Use `diff` or `diffFile`. |
| `diffFile` | Path of a file that holds the diff |
| `strip` | Number of leading path parts to remove from file names, as text. The default is `auto`: remove the first part when the names have the `a/` and `b/` prefixes (a created or deleted file has `/dev/null` for one name). |
| `dryRun` | If `true`, check the diff and write nothing |
| `fuzz` | The highest number of context lines at the start and at the end of a hunk that the tool can ignore (0 to 3). The default is 2. |
| `reverse` | If `true`, undo the diff |
| `conflicts` | `fail` (default) or `markers` |

Behavior:

1. **Read.** `TextDiff` reads the diff with `UnifiedDiffParser`. A diff can have many files. A file name of `/dev/null` as the old name creates a file, and as the new name deletes a file. Renames, copies, mode changes, and binary changes are not supported. The tool returns an error that says so.
2. **Check paths.** Every file in the diff must be inside the workspace. `patch` refuses a path with `..` that leaves the workspace, an absolute path outside the workspace, and each path that has a `.git` part. It refuses these paths itself, also when the permission policy would allow them. The policy can also return `DENY` for a path. A diff that names the same file two times is refused. A file that Jeorje must change needs the same file-state check as `edit`: the agent must have read the file in this session, and the content of the file must not have changed since. For a file to create, the file must not exist. `diffFile` goes through the same path checks.
3. **Apply in memory.** For each file, `PatchApplier` applies the hunks to the lines of the file. A hunk applies at its line number, or at the closest place where the context matches (the offset is in the report). With `fuzz`, a hunk can apply when the tool ignores up to that many context lines at its start and at its end. Each hunk starts after the end of the earlier hunk.
4. **All or nothing.** If a hunk of any file fails, the tool changes no file. The result lists each failed hunk with the file, the hunk header, the reason, the expected line, and the lines of the file near the expected place, so the model can correct the diff.
5. **Conflict markers.** With `conflicts: markers`, a hunk that fails writes the markers `<<<<<<< current`, `=======`, and `>>>>>>> patch` into the file text, and the tool reports the file as `conflicted`. The tool still writes the files that applied without conflict, and writes the conflicted files with markers. This mode is for the user and for merge repair. The default mode (`fail`) changes nothing.
6. **Write.** The tool writes each file with an atomic write (temporary file, then move). If a write fails in the middle, the tool restores the files it already wrote from the copies it holds in memory.
7. **Result.** The tool returns the list of files with `created`, `modified`, or `deleted`, the number of each hunk with its offset and the fuzz that it needed, and the unified diff of what changed on disk.

`patch` is `ASK` by default, like `write` and `edit`. The tool is not read-only, also with `dryRun`. In `dryRun` mode, the tool writes nothing and the result starts with `Dry run. Nothing was written.`

### bash

- `bash` runs the shell command in the working directory. The shell is `/bin/sh -c` by default. The `tools.bash.shell` key sets the shell. The process gets a minimal environment. See [permissions-and-safety.md](permissions-and-safety.md#shell-commands).
- Input: `command`, and optional `operation` (`run`, `poll`, or `kill`), `timeout` in seconds, `background`, and `id`.
- Output streams to a listener while the command runs. The result for the model is shortened. It keeps the first 10,000 and the last 14,000 characters. A foreground result ends with the line `[exit code N]`. A non-zero exit code makes an error result.
- Background mode returns a process id such as `bg-1`. The operations `poll` and `kill` use that id. `poll` returns the new output. `kill` stops the process tree.
- The tool refuses a command when the permission policy returns `DENY`. The agent loop handles `ASK` before the call. On timeout or cancellation, Jeorje kills the full process tree. The tool kills the processes of a worktree before a cleanup removes the worktree.

### webfetch

`webfetch` accepts only `http` and `https`. It follows up to five redirects, and it checks the network policy again for each redirect target. It refuses private, loopback, and link-local addresses. The CLI passes no setting that allows them. It enforces a response size cap (default 2 MB): a response over the cap is an error, not a cut text. It accepts text types only (`text/*`, JSON, XML, and JavaScript) and refuses other types. It converts HTML to markdown text and returns other text as it is. A status of 400 or more is an error. The request timeout is 30 s. The domain policy applies.

### advisor

The advisor is a second model. It is usually stronger than the default model. The agent calls `advisor` before it starts large work, when it is stuck, and before it says that a task is done.

- Input: an optional `question`. With no question, the advisor reviews the whole task.
- Jeorje sends the working context of the calling agent to the advisor model. The pruned chunks in the context have their keyword hints. The advisor has no tools. The answer has at most 4,096 tokens.
- Output: the advice as text. The calling agent receives it as a normal tool result.
- The user sets the model with `/advisor <slug>`. `/advisor off` removes the model and the tool.
- The advisor call is part of the turn of the calling agent. It is in the history as part of the chunk of that turn.

### Extension tools

You can add external tools in the config key `tools.extensions` as command tools. A command tool has a name, a description, a JSON schema, a command, and optional `readOnly` (default `false`) and `timeoutSeconds` values. The executable reads the input as JSON on stdin and writes the result to stdout. A non-zero exit code makes an error result. The process gets a minimal environment with no API keys. Jeorje skips an entry that is not valid or that uses the name of another tool, and logs a warning.

## Concurrency

Calls from one assistant message run at the same time only if all calls are `readOnly`. Otherwise, Jeorje runs the calls one after the other, in the order of the message. The agent loop checks the permission of each call in order before it starts a group of read-only calls.
