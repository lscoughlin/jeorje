package org.github.lscoughlin.jeorje.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class UiSkeletonTest {

    @Test
    void packageIsPresent() {
        var pkg = Package.getPackage("org.github.lscoughlin.jeorje.ui");
        assertNotNull(getClass().getPackage());
        assertNotNull(pkg == null ? getClass().getPackageName() : pkg.getName());
    }
}
