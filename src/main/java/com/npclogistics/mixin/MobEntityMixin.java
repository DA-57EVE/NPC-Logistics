package com.npclogistics.mixin;

import com.npclogistics.entity.LivestockTaggable;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEntity.class)
public class MobEntityMixin implements LivestockTaggable {

    @Unique
    private static final TrackedData<Boolean> NPCLOGISTICS_TAGGED =
            DataTracker.registerData(MobEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    @Unique
    private @Nullable BlockPos npclogistics_jobsite = null;

    @Inject(at = @At("TAIL"), method = "initDataTracker")
    private void npclogistics_initDataTracker(CallbackInfo ci) {
        ((MobEntity) (Object) this).getDataTracker().startTracking(NPCLOGISTICS_TAGGED, false);
    }

    @Inject(at = @At("TAIL"), method = "writeCustomDataToNbt")
    private void npclogistics_writeNbt(NbtCompound nbt, CallbackInfo ci) {
        if (npclogistics_jobsite != null) {
            NbtCompound pos = new NbtCompound();
            pos.putInt("x", npclogistics_jobsite.getX());
            pos.putInt("y", npclogistics_jobsite.getY());
            pos.putInt("z", npclogistics_jobsite.getZ());
            nbt.put("npclogistics_jobsite", pos);
        }
    }

    @Inject(at = @At("TAIL"), method = "readCustomDataFromNbt")
    private void npclogistics_readNbt(NbtCompound nbt, CallbackInfo ci) {
        if (nbt.contains("npclogistics_jobsite")) {
            NbtCompound pos = nbt.getCompound("npclogistics_jobsite");
            npclogistics_jobsite = new BlockPos(pos.getInt("x"), pos.getInt("y"), pos.getInt("z"));
            ((MobEntity) (Object) this).getDataTracker().set(NPCLOGISTICS_TAGGED, true);
        }
    }

    @Override
    public boolean npclogistics_isTagged() {
        return ((MobEntity) (Object) this).getDataTracker().get(NPCLOGISTICS_TAGGED);
    }

    @Override
    public void npclogistics_setTagged(boolean tagged, @Nullable BlockPos jobsite) {
        this.npclogistics_jobsite = tagged ? jobsite : null;
        ((MobEntity) (Object) this).getDataTracker().set(NPCLOGISTICS_TAGGED, tagged);
    }

    @Override
    public @Nullable BlockPos npclogistics_getJobsite() {
        return npclogistics_jobsite;
    }
}
