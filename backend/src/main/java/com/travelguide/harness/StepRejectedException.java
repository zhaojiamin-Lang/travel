package com.travelguide.harness;

public class StepRejectedException extends Exception {
    private final String code;

    public StepRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public StepRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
