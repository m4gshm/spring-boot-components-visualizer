package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.UnresolvedVariableException;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Stub extends Result implements ContextAware, TypeAware {
    Variable stubbed;

    public Stub(Variable stubbed) {
        super(stubbed.getFirstInstruction(), stubbed.getLastInstruction());
        this.stubbed = stubbed;
    }

    @Override
    public Eval getEval() {
        return getStubbed().getEval();
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

    @Override
    public Type getType() {
        return stubbed.getType();
    }
}
