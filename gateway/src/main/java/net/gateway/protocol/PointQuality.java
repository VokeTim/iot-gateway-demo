package net.gateway.protocol;

public enum PointQuality {

    GOOD(192), GOOD_UNCERTAIN(160),
    SUSPECT_ROC(96), SUSPECT_STUCK(80),
    BAD_INVALID(0);

    private final int code;

    PointQuality(int code) {
        this.code = code;
    }

    public boolean isGood() {
        return code >= 160;
    }

    public boolean isBad() {
        return code < 48;
    }
}
