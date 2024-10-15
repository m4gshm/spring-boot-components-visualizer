package io.github.m4gshm.connections.client;

import org.junit.jupiter.api.Test;

import static io.github.m4gshm.connections.client.JmsOperationsUtils.getJmsDirection;
import static io.github.m4gshm.connections.model.Interface.Direction.in;
import static io.github.m4gshm.connections.model.Interface.Direction.out;
import static io.github.m4gshm.connections.model.Interface.Direction.outIn;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JmsOperationsUtilsTest {
    @Test
    public void getJmsDirectionTest() {
        assertEquals(outIn, getJmsDirection("sendAndReceive"));
        assertEquals(in, getJmsDirection("receive"));
        assertEquals(out, getJmsDirection("send"));
    }
}
