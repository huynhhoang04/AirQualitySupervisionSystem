package com.aqss.backend.exception;

/** Thrown when a unique constraint would be violated → HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
