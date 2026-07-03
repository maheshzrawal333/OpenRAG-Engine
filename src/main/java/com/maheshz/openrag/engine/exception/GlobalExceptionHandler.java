package com.maheshz.openrag.engine.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RAGProcessingException.class)
    public ProblemDetail handleRAGException(RAGProcessingException ex) {
        log.error("RAG Processing Error: {}", ex.getMessage(), ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problemDetail.setTitle("Vector Processing Failure");
        problemDetail.setType(URI.create("https://api.openrag.com/errors/vector-processing-failed"));
        return problemDetail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Invalid Request");
        return problemDetail;
    }

    // SENIOR FIX: The Silent Drop
    // Catches Tomcat's broken pipe errors when a user closes their browser mid-stream.
    // Returning 'void' prevents Spring from trying to write a JSON ProblemDetail into a dead text stream!
    @ExceptionHandler(java.io.IOException.class)
    public void handleClientDisconnect(java.io.IOException ex) {
        // We use log.trace() or log.debug() so it doesn't pollute the INFO logs in production.
        log.debug("Client disconnected during an active connection. Stream closed safely.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred.");
        problemDetail.setTitle("Internal Server Error");
        return problemDetail;
    }
}