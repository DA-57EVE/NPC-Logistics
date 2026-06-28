package com.npclogistics.entity;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface LivestockTaggable {
    boolean npclogistics_isTagged();
    void npclogistics_setTagged(boolean tagged, @Nullable BlockPos jobsite);
    @Nullable BlockPos npclogistics_getJobsite();
    int npclogistics_getOwnerColor();
    void npclogistics_setOwnerColor(int packed);

    static int colorForOwner(UUID uuid) {
        long bits = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        float hue = (float) ((bits & 0xFFFFFFFFL) / 4294967296.0);
        float s = 0.85f, b = 1.0f;
        int h = (int) (hue * 6);
        float f = hue * 6 - h;
        float p = b * (1 - s);
        float q = b * (1 - f * s);
        float t = b * (1 - (1 - f) * s);
        float r, g, bl;
        switch (h % 6) {
            case 0: r = b; g = t; bl = p; break;
            case 1: r = q; g = b; bl = p; break;
            case 2: r = p; g = b; bl = t; break;
            case 3: r = p; g = q; bl = b; break;
            case 4: r = t; g = p; bl = b; break;
            default: r = b; g = p; bl = q; break;
        }
        return ((int)(r * 255) << 16) | ((int)(g * 255) << 8) | (int)(bl * 255);
    }
}
