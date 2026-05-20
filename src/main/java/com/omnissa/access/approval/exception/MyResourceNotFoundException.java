package com.omnissa.access.approval.exception;

public class MyResourceNotFoundException extends RuntimeException {

    public MyResourceNotFoundException() {
        super();
    }

    public MyResourceNotFoundException(String message) {
        super(message);
    }

    public MyResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public MyResourceNotFoundException(Throwable cause) {
        super(cause);
    }
}
