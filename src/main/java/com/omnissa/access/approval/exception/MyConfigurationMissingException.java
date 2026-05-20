package com.omnissa.access.approval.exception;

public class MyConfigurationMissingException extends RuntimeException {

    public MyConfigurationMissingException() {
        super();
    }

    public MyConfigurationMissingException(String message) {
        super(message);
    }

    public MyConfigurationMissingException(String message, Throwable cause) {
        super(message, cause);
    }

    public MyConfigurationMissingException(Throwable cause) {
        super(cause);
    }
}
