package rest;

import java.io.IOException;

public class TOAException extends IOException {
    private int statusCode;

    protected TOAException() {
    }

    public TOAException(TOAException original) {
        super(original);
        this.statusCode = original.statusCode;
    }

    public TOAException(int statusCode, String message) {
        super(statusCode + " - " + message);
        this.statusCode = statusCode;
    }

    public TOAException(int statusCode, String message, Throwable cause) {
        super(statusCode + " - " + message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
