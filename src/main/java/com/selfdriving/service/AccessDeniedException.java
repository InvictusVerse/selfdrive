package com.selfdriving.service;

/** The logged-in user is not allowed to do this. */
public final class AccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public AccessDeniedException(String message) {
        super(message);
    }
}
