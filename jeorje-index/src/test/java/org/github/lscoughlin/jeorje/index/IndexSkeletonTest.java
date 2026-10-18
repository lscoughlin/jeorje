package org.github.lscoughlin.jeorje.index;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class IndexSkeletonTest {

    @Test
    void packageIsPresent() {
        var pkg = Package.getPackage("org.github.lscoughlin.jeorje.index");
        assertNotNull(getClass().getPackage());
        assertNotNull(pkg == null ? getClass().getPackageName() : pkg.getName());
    }
}
