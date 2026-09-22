package net.tfminecraft.interactiblefurniture.furniture;

public enum SlotType {
    ITEM,
    FURNITURE;

    public static SlotType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return ITEM;
        }
        try {
            return SlotType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ITEM;
        }
    }
}
