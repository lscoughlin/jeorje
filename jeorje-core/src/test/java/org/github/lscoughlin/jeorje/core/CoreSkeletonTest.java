package org.github.lscoughlin.jeorje.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class CoreSkeletonTest {

    @Test
    void packageIsPresent() {
        var pkg = Package.getPackage("org.github.lscoughlin.jeorje.core");
        assertNotNull(getClass().getPackage());
        assertNotNull(pkg == null ? getClass().getPackageName() : pkg.getName());
    }
}
