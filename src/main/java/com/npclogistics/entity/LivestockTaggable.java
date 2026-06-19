package com.npclogistics.entity;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public interface LivestockTaggable {
    boolean npclogistics_isTagged();
    void npclogistics_setTagged(boolean tagged, @Nullable BlockPos jobsite);
    @Nullable BlockPos npclogistics_getJobsite();
}
