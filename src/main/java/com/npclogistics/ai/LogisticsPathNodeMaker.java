package com.npclogistics.ai;

import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

public class LogisticsPathNodeMaker extends LandPathNodeMaker {

    @Override
    public PathNodeType getDefaultNodeType(BlockView world, int x, int y, int z) {
        PathNodeType base = super.getDefaultNodeType(world, x, y, z);
        if (base == PathNodeType.OPEN || base == PathNodeType.WALKABLE) {
            var below = world.getBlockState(new BlockPos(x, y - 1, z)).getBlock();
            if (below instanceof ChestBlock || below == Blocks.BARREL) {
                return PathNodeType.DAMAGE_OTHER;
            }
        }
        return base;
    }
}
