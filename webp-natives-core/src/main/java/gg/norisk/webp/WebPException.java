package gg.norisk.webp;

import java.io.IOException;

/** Thrown when WEBP encode/decode fails or natives are unavailable. */
public class WebPException extends IOException {
    private static final long serialVersionUID = 1L;

    public WebPException(String message) {
        super(message);
    }

    public WebPException(String message, Throwable cause) {
        super(message, cause);
    }
}
