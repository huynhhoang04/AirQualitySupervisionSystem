package com.aqss.backend.exception;

/** Thrown for invalid client input → HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
