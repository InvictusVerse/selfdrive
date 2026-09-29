package com.selfdriving.service;

/** Input that breaks a rule (shown to the user as is). */
public final class ValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}
