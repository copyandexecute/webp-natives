package gg.norisk.webm;

public enum PixelLayout {
    ARGB_INT(0),
    RGBA_BYTES(1);

    private final int nativeId;

    PixelLayout(int nativeId) {
        this.nativeId = nativeId;
    }

    int nativeId() {
        return nativeId;
    }
}
