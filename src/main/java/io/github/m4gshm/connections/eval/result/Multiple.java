package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import io.github.m4gshm.connections.eval.bytecode.IllegalMultipleResultsInvocationException;
import io.github.m4gshm.connections.model.Component;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Multiple extends Result {
    List<Result> results;
    EvalBytecode evalContext;

    public Multiple(InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                    List<Result> results, EvalBytecode evalContext) {
        super(firstInstruction, lastInstruction);
        this.results = results;
        this.evalContext = evalContext;
    }

    private void checkState() {
        if (results.isEmpty()) {
            throw new IllegalStateException("empty multiple values");
        }
    }

    public List<Result> getResults() {
        checkState();
        return results;
    }

    @Override
    public Object getValue() {
        throw new IllegalMultipleResultsInvocationException(this);
    }

    @Override
    public boolean isResolved() {
        checkState();
        return results.stream().allMatch(Result::isResolved);
    }

    @Override
    public Method getMethod() {
        return evalContext.getMethod();
    }

    @Override
    public Component getComponent() {
        return evalContext.getComponent();
    }

    @Override
    public String toString() {
        return "multiple" + results;
    }
}
