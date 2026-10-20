package org.github.lscoughlin.jeorje.tools.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.github.lscoughlin.jeorje.core.port.InputValidator;
import java.nio.file.Files;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BinaryAndValidatorTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void detectsBinaryFiles() throws Exception {
        try (var ws = new TempWorkspace()) {
            var text = ws.write("a.txt", "hello é中\n");
            var zero = Files.write(ws.resolve("z.bin"), new byte[] {'a', 0, 'b'});
            var invalid = Files.write(ws.resolve("i.bin"), new byte[] {(byte) 0xff, (byte) 0xfe, 'a'});
            assertFalse(BinaryFiles.isBinary(text));
            assertTrue(BinaryFiles.isBinary(zero));
            assertTrue(BinaryFiles.isBinary(invalid));
        }
    }

    @Test
    void cutCharacterAtSampleEndIsNotBinary() {
        var bytes = ("a".repeat(7_999) + "é").getBytes(StandardCharsets.UTF_8);
        assertFalse(BinaryFiles.isBinary(bytes, true));
    }

    @Test
    void validatesNestedSchemas() {
        var schema = JSON.readTree("""
            {"type":"object","required":["items"],"additionalProperties":false,
             "properties":{"items":{"type":"array","items":{"type":"integer","minimum":1,"maximum":5}},
                           "name":{"type":"string","minLength":2}}}""");
        assertTrue(InputValidator.validate(schema, JSON.readTree("{\"items\":[1,5],\"name\":\"ab\"}")).isEmpty());
        var errors = InputValidator.validate(schema, JSON.readTree("{\"items\":[0,\"x\",9],\"name\":\"a\"}"));
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("input.items[0] must be at least 1")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("input.items[1] must be of type integer")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("input.items[2] must be at most 5")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("input.name must have at least 2")));
        assertEquals(1, InputValidator.validate(schema, JSON.readTree("{}")).size());
    }

    @Test
    void nullSchemaAcceptsAnything() {
        assertTrue(InputValidator.validate(null, JSON.readTree("{\"a\":1}")).isEmpty());
    }
}
