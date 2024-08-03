package io.github.m4gshm.connections.bytecode;


public class BcelException extends RuntimeException {
    public BcelException(Throwable e) {
        super(e);
    }

    public BcelException(String message) {
        super(message);
    }
}
