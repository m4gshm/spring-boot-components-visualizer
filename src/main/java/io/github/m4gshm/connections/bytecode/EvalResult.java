package io.github.m4gshm.connections.bytecode;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.apache.bcel.generic.InstructionHandle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static io.github.m4gshm.connections.bytecode.EvalResult.Status.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalResult.Status.notFound;

@Data
@Builder
public class EvalResult<T> {
    private final List<T> result;
    private final Set<Status> status;
    private final Object source;
    private final InstructionHandle callInstruction;
    private final InstructionHandle lastInstruction;

    public static <T> EvalResult<T> success(List<T> values, InstructionHandle callInstruction, InstructionHandle lastInstruction) {
        return EvalResult.<T>builder().result(values).callInstruction(callInstruction).lastInstruction(lastInstruction).build();
    }

    public static <T> EvalResult<T> success(T value, InstructionHandle callInstruction, InstructionHandle lastInstruction) {
        ArrayList<T> nullSupporting = new ArrayList<>();
        nullSupporting.add(value);
        return EvalResult.<T>builder().result(nullSupporting).callInstruction(callInstruction).lastInstruction(lastInstruction).build();
    }

    public static <T> EvalResult<T> notAccessible(Object source, InstructionHandle callInstruction) {
        return EvalResult.<T>builder().status(Set.of(notAccessible)).source(source).callInstruction(callInstruction).build();
    }

    public static <T> EvalResult<T> notFound(Object source, InstructionHandle callInstruction) {
        return EvalResult.<T>builder().status(Set.of(notFound)).source(source).callInstruction(callInstruction).build();
    }

    public T getResult() {
        return getResults().get(0);
    }

    public List<T> getResults() {
        throwResultExceptionIfInvalidStatus();
        return result;
    }

    public InstructionHandle getLastInstruction() {
        throwResultExceptionIfInvalidStatus();
        return lastInstruction;
    }

    private void throwResultExceptionIfInvalidStatus() {
        if (status != null && !status.isEmpty()) {
            throw new CallResultException(status, source, callInstruction);
        }
    }

    public enum Status {
        notAccessible, notFound;
    }

    @Getter
    public static class CallResultException extends RuntimeException {
        public CallResultException(Collection<Status> status, Object source, InstructionHandle instruction) {
            super(status + ", source=" + source + ", instruction=" + instruction);
        }
    }
}
