package com.travelguide.harness;

public class HarnessConflictException extends RuntimeException {
    public HarnessConflictException(String message) {
        super(message);
    }

    public HarnessConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
