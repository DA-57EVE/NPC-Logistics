package com.npclogistics.ai;

import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;

public class LogisticsNavigation extends MobNavigation {

    public LogisticsNavigation(MobEntity entity, World world) {
        super(entity, world);
    }

    @Override
    protected PathNodeNavigator createPathNodeNavigator(int range) {
        this.nodeMaker = new LogisticsPathNodeMaker();
        this.nodeMaker.setCanEnterOpenDoors(true);
        this.nodeMaker.setCanOpenDoors(true);
        return new PathNodeNavigator(this.nodeMaker, range);
    }
}
