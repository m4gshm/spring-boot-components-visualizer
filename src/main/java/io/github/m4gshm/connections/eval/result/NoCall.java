package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.NoCallException;
import io.github.m4gshm.connections.model.Component;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;

import static lombok.AccessLevel.PRIVATE;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class NoCall extends Result implements ContextAware {
    Delay delay;

    public NoCall(Delay delay) {
        super(delay.firstInstruction, delay.lastInstruction);
        this.delay = delay;
    }

    @Override
    public Object getValue() {
        throw new NoCallException(delay);
    }

    @Override
    public boolean isResolved() {
        return delay.isResolved();
    }

    @Override
    public Method getMethod() {
        return delay.getMethod();
    }

    @Override
    public Component getComponent() {
        return delay.getComponent();
    }
}
