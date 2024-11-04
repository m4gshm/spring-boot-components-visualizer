package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.UnresolvedVariableException;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import static lombok.AccessLevel.PRIVATE;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Stub extends Result implements ContextAware {
    private final Method method;
    private final Component component;
    private final Variable stubbed;

    public Stub(Method method, Component component, Variable stubbed) {
        super(stubbed.getFirstInstruction(), stubbed.getLastInstruction());
        this.method = method;
        this.component = component;
        this.stubbed = stubbed;
    }

    @Override
    public Object getValue() {
        throw new UnresolvedVariableException("stubbed", stubbed);
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

    public Method getMethod() {
        return this.method;
    }

    public Component getComponent() {
        return this.component;
    }

    public Variable getStubbed() {
        return this.stubbed;
    }
}
