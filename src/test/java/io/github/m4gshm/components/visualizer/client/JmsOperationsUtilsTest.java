package io.github.m4gshm.components.visualizer.client;

import org.junit.jupiter.api.Test;

import static io.github.m4gshm.components.visualizer.client.JmsOperationsUtils.getJmsDirection;
import static io.github.m4gshm.components.visualizer.model.Direction.in;
import static io.github.m4gshm.components.visualizer.model.Direction.out;
import static io.github.m4gshm.components.visualizer.model.Direction.outIn;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JmsOperationsUtilsTest {
    @Test
    public void getJmsDirectionTest() {
        assertEquals(outIn, getJmsDirection("sendAndReceive"));
        assertEquals(in, getJmsDirection("receive"));
        assertEquals(out, getJmsDirection("send"));
    }
}
