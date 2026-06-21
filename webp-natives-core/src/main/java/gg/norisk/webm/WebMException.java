package gg.norisk.webm;

import java.io.IOException;

public class WebMException extends IOException {
    private static final long serialVersionUID = 1L;

    public WebMException(String message) {
        super(message);
    }

    public WebMException(String message, Throwable cause) {
        super(message, cause);
    }
}
