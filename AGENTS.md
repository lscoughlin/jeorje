# Agents

Conventions for anyone (human or AI) working in this repository.

## Orientation

Jeorje is a Java coding agent designed to run in a swarm, with a JLine terminal UI and a turn-chunked, queryable context index. Read [README.md](README.md) for the overview, then [doc/design/index.md](doc/design/index.md) for the design.

## Layout

- `doc/` — all documentation
- `plan/` — all plans
- `jeorje-core`, `jeorje-index`, `jeorje-tools`, `jeorje-swarm`, `jeorje-llm`, `jeorje-ui`, `jeorje-testkit`, `jeorje-cli` — Maven modules, each with the standard `src/main/java` and `src/test/java` layout. [doc/design/architecture.md](doc/design/architecture.md) describes them.

## Documentation Rules

- All documentation lives in `doc/` and is written in markdown.
- Every file has YAML frontmatter of at most 20 lines.
- Files over 1000 lines are split into smaller files.
- Documentation is descriptive and explicit.
- Documentation never references plans.

## Writing Style

All output is formatted in ASD-STE100 Simplified Technical English: short sentences, one idea per sentence, active voice, approved vocabulary, and consistent terms.

## Plan Rules

- All plans live in `plan/`.
- Plans may reference documentation.

## Code Style

- Modern, idiomatic Java 25.
- Use `var` wherever possible.
- Prefer enhanced (arrow-form) `switch` statements and expressions.
- Log with the lazy fluent form: `log.atLevel(level).log(() -> "stuff")`.

## Tech Stack

- Java 25
- Maven
- Jackson 3
- SLF4J with Logback
- JLine 4 (`org.jline:jline`, pinned in the parent pom)
- picocli
- Apache Lucene (BM25 text search and `KnnFloatVectorField` cosine vector search)
- ONNX Runtime (local MiniLM embeddings)
- JmDNS (`org.jmdns:jmdns`; zeroconf peer discovery in `jeorje-swarm`)
- java-diff-utils (`io.github.java-diff-utils:java-diff-utils`; diff and patch tools)

## Build and Test

- Build and run all tests: `mvn verify`
- Run tests only: `mvn test`
- Tests use the JUnit Jupiter API (JUnit 5 API) through the JUnit BOM in the parent pom. They live under `src/test/java` in each module, mirroring the main package structure.
- Run `mvn verify` before considering a change finished.
- `mvn verify` must pass offline. Tests never load real embedding model bytes or touch the network; they use a deterministic stub implementation of the `Embedder` interface.
- The real ONNX embedder downloads its model once at runtime into `~/.cache/`, and degrades to text-only search if the model is unavailable.
- Run with `--add-modules jdk.incubator.vector` when the JDK provides it, to enable Lucene's SIMD vector path. The Maven profile `jdk-vector` does this for the tests. For the jar, pass the flag to `java`.
- Add or update tests under `src/test/java` for every behavior change.
- A new dependency needs a clear justification in the commit message.
