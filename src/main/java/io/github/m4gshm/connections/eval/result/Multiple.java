package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.IllegalMultipleResultsInvocationException;
import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Multiple extends Result {
    List<Result> results;
    private Method method;
    private Component component;

    public Multiple(InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                    List<Result> results, Component component, Method method) {
        super(firstInstruction, lastInstruction);
        this.results = results;
        this.component = component;
        this.method = method;
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
    public String toString() {
        return "multiple" + results;
    }
}
