package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtraBodyLogTest {
    @Test
    void theLogTextHidesFieldsWithSecretLikeNames() {
        var text = Wiring.describeExtraBody(Map.of("provider", Map.of("require_parameters", true),
                "api_key", "sk-secret", "Authorization", "Bearer abc"));
        assertEquals("Authorization=***, api_key=***, provider={require_parameters=true}", text);
        assertFalse(text.contains("sk-secret") || text.contains("abc"));
    }
}
