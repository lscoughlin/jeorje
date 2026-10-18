package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SwarmSkeletonTest {

    @Test
    void packageIsPresent() {
        var pkg = Package.getPackage("org.github.lscoughlin.jeorje.swarm");
        assertNotNull(getClass().getPackage());
        assertNotNull(pkg == null ? getClass().getPackageName() : pkg.getName());
    }
}
