package com.winlator.cmod.core;

public class VKD3DVersionItem {
    private final String identifier; // Unique identifier based on verName and verCode
    private final String displayName; // Name shown in the spinner

    public VKD3DVersionItem(String verName, int verCode) {
        this.identifier = verName + "-" + verCode; // Unique ID
        this.displayName = verName + "-" + verCode; // Distinguish installed builds sharing a name.
    }

    public VKD3DVersionItem(String identifier, String displayName) {
        this.identifier = identifier;
        this.displayName = displayName;
    }

    public String getIdentifier() {
        return identifier;
    }

    public static int retainSelection(java.util.List<VKD3DVersionItem> items, String identifier) {
        if (identifier == null || identifier.isEmpty()) return 0;
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).getIdentifier().equals(identifier)) return i;
        items.add(new VKD3DVersionItem(identifier, identifier));
        return items.size() - 1;
    }

    @Override
    public String toString() {
        return displayName; // Spinner uses this for display
    }
}

