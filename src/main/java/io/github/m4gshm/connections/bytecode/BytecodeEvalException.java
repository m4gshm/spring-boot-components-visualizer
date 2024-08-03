package io.github.m4gshm.connections.bytecode;


public class BytecodeEvalException extends RuntimeException {
    public BytecodeEvalException(Throwable e) {
        super(e);
    }

    public BytecodeEvalException(String message) {
        super(message);
    }
}
