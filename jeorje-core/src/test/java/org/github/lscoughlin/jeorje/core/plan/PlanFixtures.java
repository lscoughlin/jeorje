package org.github.lscoughlin.jeorje.core.plan;

import java.util.List;

/** Small plans for the tests. */
final class PlanFixtures {
    private PlanFixtures() {}

    static Story story(String id, List<String> dependsOn, List<String> touches) {
        return new Story(id, "Title " + id, "Goal of " + id, dependsOn, touches, null,
                "The module uses java.time.Clock. Use constructor injection.",
                "- Tests cover refill.", "mvn -q test",
                List.of(new Task(id + ".T1", "Write the class."), new Task(id + ".T2", "Write the tests.")));
    }

    static Plan plan(Story... stories) {
        return new Plan(java.util.Map.of("title", "Sample plan", "status", "draft", "goal", "A goal.",
                "stories", String.valueOf(stories.length), "verification", "mvn verify"),
                List.of(), "# Plan: Sample plan", List.of(stories), "");
    }

    static final String SAMPLE = """
        ---
        title: Add rate limiting
        status: draft            # draft, approved
        goal: Requests above 100 per minute get HTTP 429.
        stories: 2
        verification: mvn verify
        ---

        # Plan: Add rate limiting

        Intro text.

        ## Story S1: Token bucket
        - **Goal:** A bucket.
        - **Depends on:** none
        - **Touches:** `src/a/`, `src/b.java`
        - **Model:** default

        ### Context
        Use a clock.

        ```
        ### not a heading
        ```

        ### Acceptance criteria
        - It works.

        ### Verification
        `mvn test`

        ### Tasks
        - [ ] S1.T1 Create the class.
        - [x] S1.T2 Add tests
          with a continuation.
        - [!] S1.T3 Blocked task.

        ## Story S2: Filter
        - **Goal:** A filter.
        - **Depends on:** S1
        - **Touches:** `src/c/`

        ### Context
        Context two.

        ### Acceptance criteria
        - Filter works.

        ### Tasks
        - [ ] S2.T1 Write it.
        """.replace("\n        ", "\n").stripLeading();
}
