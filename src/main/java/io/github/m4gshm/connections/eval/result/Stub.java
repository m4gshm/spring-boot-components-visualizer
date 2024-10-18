package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.UnresolvedResultException;
import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Stub extends Result implements ContextAware {
    Method method;
    Component component;
    Result stubbed;

    public Stub(Method method, Component component, Result stubbed) {
        super(stubbed.getFirstInstruction(), stubbed.getLastInstruction());
        this.method = method;
        this.component = component;
        this.stubbed = stubbed;
    }

    @Override
    public Object getValue() {
        throw new UnresolvedResultException("stubbed", stubbed);
    }

    @Override
    public boolean isResolved() {
        return false;
    }

    @Override
    public InstructionHandle getFirstInstruction() {
        return stubbed.getFirstInstruction();
    }

    @Override
    public InstructionHandle getLastInstruction() {
        return stubbed.getLastInstruction();
    }

    @Override
    public String toString() {
        return "stub(" + stubbed + ")";
    }
}
