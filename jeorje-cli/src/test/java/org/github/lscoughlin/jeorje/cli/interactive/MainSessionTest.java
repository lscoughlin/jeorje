package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.junit.jupiter.api.Test;

/** The per-conversation state of the interface. */
class MainSessionTest {
    @Test
    void theFirstSessionIsMain() {
        var main = MainSession.first();
        assertEquals(new AgentId("main"), main.id());
        assertEquals("main", main.name());
    }

    @Test
    void aNewSessionIsNotBusy() {
        assertFalse(MainSession.first().busy().get());
    }

    @Test
    void theFirstSessionHoldsOnePlanMode() {
        var main = MainSession.first();
        assertSame(main.planMode(), main.planMode());
    }

    @Test
    void twoSessionsHaveTheirOwnState() {
        var one = MainSession.first();
        var two = new MainSession(new AgentId("second"), "second");
        one.busy().set(true);
        assertEquals("second", two.name());
        assertNotSame(one.busy(), two.busy());
        assertFalse(two.busy().get());
        assertNotSame(one.planMode(), two.planMode());
    }

    @Test
    void aNewSessionHoldsNoConversationYet() {
        var main = MainSession.first();
        assertNull(main.session());
        assertNull(main.store());
        assertNull(main.transcript());
        assertNull(main.agent());
        assertNull(main.runtime());
        assertNull(main.policy());
    }
}
