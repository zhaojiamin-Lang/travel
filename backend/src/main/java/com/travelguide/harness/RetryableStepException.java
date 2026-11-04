package com.travelguide.harness;

public class RetryableStepException extends Exception {
    private final String code;

    public RetryableStepException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RetryableStepException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
