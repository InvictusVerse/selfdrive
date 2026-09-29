package com.selfdriving.service;

/** Input that breaks a rule (shown to the user as is). */
public final class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
