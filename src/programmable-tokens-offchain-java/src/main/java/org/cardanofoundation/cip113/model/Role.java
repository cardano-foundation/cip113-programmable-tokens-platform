package org.cardanofoundation.cip113.model;

public enum Role {
    USER(0),
    INSTITUTIONAL(1),
    VLEI(2);

    private final int value;

    Role(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static Role fromValue(int value) {
        return switch (value) {
            case 0 -> USER;
            case 1 -> INSTITUTIONAL;
            case 2 -> VLEI;
            default -> throw new IllegalArgumentException("Unknown role value: " + value);
        };
    }

    public static Role fromString(String name) {
        return Role.valueOf(name.toUpperCase());
    }
}
