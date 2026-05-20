package com.omnissa.access.approval.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class MyConfigurationAlreadyExistsException extends RuntimeException {

    public MyConfigurationAlreadyExistsException() {
        super();
    }

    public MyConfigurationAlreadyExistsException(String message) {
        super(message);
    }

    public MyConfigurationAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }

    public MyConfigurationAlreadyExistsException(Throwable cause) {
        super(cause);
    }
}
