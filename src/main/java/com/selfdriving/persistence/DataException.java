package com.selfdriving.persistence;

/** A database operation failed (wraps {@link java.sql.SQLException} so callers need no JDBC). */
public final class DataException extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public DataException(String message, Throwable cause) {
        super(message, cause);
    }
}
