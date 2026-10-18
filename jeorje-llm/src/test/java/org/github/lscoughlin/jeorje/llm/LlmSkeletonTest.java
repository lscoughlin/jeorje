package org.github.lscoughlin.jeorje.llm;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class LlmSkeletonTest {

    @Test
    void packageIsPresent() {
        var pkg = Package.getPackage("org.github.lscoughlin.jeorje.llm");
        assertNotNull(getClass().getPackage());
        assertNotNull(pkg == null ? getClass().getPackageName() : pkg.getName());
    }
}
