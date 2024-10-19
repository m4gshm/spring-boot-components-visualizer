package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.NotInvokedException;
import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class NotInvoked extends Result implements ContextAware {
    Delay delay;

    public NotInvoked(Delay delay) {
        super(delay.firstInstruction, delay.lastInstruction);
        this.delay = delay;
    }

    @Override
    public Object getValue() {
        throw new NotInvokedException(delay);
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

    @Override
    public String toString() {
        return "noCall(" + delay + ")";
    }
}
