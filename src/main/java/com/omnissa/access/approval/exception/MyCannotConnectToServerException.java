package com.omnissa.access.approval.exception;

public class MyCannotConnectToServerException extends RuntimeException {

    public MyCannotConnectToServerException() {
        super();
    }

    public MyCannotConnectToServerException(String message) {
        super(message);
    }

    public MyCannotConnectToServerException(String url, int port) {
        super("A connection could not be established with '" + url + "' on port '" + port + "'");
    }

    public MyCannotConnectToServerException(String message, Throwable cause) {
        super(message, cause);
    }

    public MyCannotConnectToServerException(Throwable cause) {
        super(cause);
    }
}
