package com.omnissa.access.approval.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;

@ControllerAdvice
public class RestResponseEntityExceptionHandler {

    @ExceptionHandler({MyResourceNotFoundException.class})
    void handleMyResourceNotFound(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.NOT_FOUND.value());
    }

    @ExceptionHandler({MyCannotConnectToServerException.class})
    void handleMyCannotConnectToServer(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.GATEWAY_TIMEOUT.value());
    }

    @ExceptionHandler({MyConfigurationMissingException.class})
    void handleMyConfigurationMissing(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.BAD_REQUEST.value());
    }
}
