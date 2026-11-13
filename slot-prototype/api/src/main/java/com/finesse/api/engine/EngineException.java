package com.finesse.api.engine;

public class EngineException extends RuntimeException {

    private final EngineErrorCode code;

    public EngineException(EngineErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public EngineErrorCode code() { return code; }

    public static EngineException invalid(String message) {
        return new EngineException(EngineErrorCode.INVALID_PAYLOAD, message);
    }
}
