package com.selfdriving.persistence;

/** A database operation failed (wraps {@link java.sql.SQLException} so callers need no JDBC). */
public final class DataException extends RuntimeException {

    public DataException(String message, Throwable cause) {
        super(message, cause);
    }
}
