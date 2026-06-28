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
    private static final TrackedData<Integer> NPCLOGISTICS_OWNER_COLOR =
            DataTracker.registerData(MobEntity.class, TrackedDataHandlerRegistry.INTEGER);

    @Unique
    private @Nullable BlockPos npclogistics_jobsite = null;

    @Inject(at = @At("TAIL"), method = "initDataTracker")
    private void npclogistics_initDataTracker(CallbackInfo ci) {
        ((MobEntity) (Object) this).getDataTracker().startTracking(NPCLOGISTICS_TAGGED, false);
        ((MobEntity) (Object) this).getDataTracker().startTracking(NPCLOGISTICS_OWNER_COLOR, 0xFFD700);
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
        int color = ((MobEntity) (Object) this).getDataTracker().get(NPCLOGISTICS_OWNER_COLOR);
        if (color != 0xFFD700) nbt.putInt("npclogistics_owner_color", color);
    }

    @Inject(at = @At("TAIL"), method = "readCustomDataFromNbt")
    private void npclogistics_readNbt(NbtCompound nbt, CallbackInfo ci) {
        if (nbt.contains("npclogistics_jobsite")) {
            NbtCompound pos = nbt.getCompound("npclogistics_jobsite");
            npclogistics_jobsite = new BlockPos(pos.getInt("x"), pos.getInt("y"), pos.getInt("z"));
            ((MobEntity) (Object) this).getDataTracker().set(NPCLOGISTICS_TAGGED, true);
        }
        if (nbt.contains("npclogistics_owner_color")) {
            ((MobEntity) (Object) this).getDataTracker().set(NPCLOGISTICS_OWNER_COLOR, nbt.getInt("npclogistics_owner_color"));
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
    public int npclogistics_getOwnerColor() {
        return ((MobEntity) (Object) this).getDataTracker().get(NPCLOGISTICS_OWNER_COLOR);
    }

    @Override
    public void npclogistics_setOwnerColor(int packed) {
        ((MobEntity) (Object) this).getDataTracker().set(NPCLOGISTICS_OWNER_COLOR, packed);
    }

    @Override
    public @Nullable BlockPos npclogistics_getJobsite() {
        return npclogistics_jobsite;
    }
}
