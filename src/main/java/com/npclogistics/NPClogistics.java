package com.npclogistics;

import com.npclogistics.command.WorkOrderCommand;
import com.npclogistics.data.WorkOrder.StopAction;
import com.npclogistics.entity.LivestockTaggable;
import com.npclogistics.entity.ModEntities;
import com.npclogistics.item.LivestockTagItem;
import com.npclogistics.item.LocationTokenItem;
import com.npclogistics.item.ModItems;
import com.npclogistics.item.WorkOrderScrollItem;
import com.npclogistics.network.ModNetworking;
import com.npclogistics.screen.ModScreenHandlers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NPClogistics implements ModInitializer {

    public static final String MOD_ID = "npclogistics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("NPClogistics - NPC Logistics initializing...");

        ModEntities.register();
        ModItems.register();
        ModScreenHandlers.register();
        ModNetworking.registerServerPackets();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WorkOrderCommand.register(dispatcher));

        // Intercept right-clicks on chests/barrels while holding the Work Order Scroll
        // BEFORE the container's own "open" behaviour runs, so we record a stop instead.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof WorkOrderScrollItem)) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            if (!WorkOrderScrollItem.isContainer(world, pos)) return ActionResult.PASS;

            // Cancel the vanilla container-open on both sides; do the mutation server-side.
            if (world.isClient) return ActionResult.SUCCESS;

            StopAction action = player.isSneaking() ? StopAction.DELIVER : StopAction.COLLECT;
            WorkOrderScrollItem.AddResult result = WorkOrderScrollItem.addStop(stack, pos, action);
            switch (result) {
                case ADDED -> player.sendMessage(Text.literal("Added " + action.name() + " stop at "
                        + pos.toShortString()).formatted(Formatting.YELLOW), true);
                case UPDATED -> player.sendMessage(Text.literal("Updated stop at " + pos.toShortString()
                        + " → " + action.name()).formatted(Formatting.YELLOW), true);
                case DUPLICATE -> player.sendMessage(Text.literal("Already a " + action.name()
                        + " stop at " + pos.toShortString()).formatted(Formatting.GRAY), true);
            }
            // Creative mode is client-authoritative: the client reverts server-side NBT mutations
            // unless we push an explicit slot update. Always push to guarantee the stop persists.
            if (player instanceof ServerPlayerEntity sp) {
                int slot = sp.getInventory().selectedSlot;
                sp.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, 0, slot, stack));
            }
            return ActionResult.SUCCESS;
        });

        // Location token right-click: stamp a block's position into the token's NBT.
        // Only valid block types are accepted: storage blocks for COLLECT/DEPOSIT,
        // crafting table for CRAFT. Wrong-type clicks pass through to vanilla interaction.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof LocationTokenItem token)) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            if (!isValidBlockForToken(world, pos, token.tokenType)) {
                if (!world.isClient)
                    player.sendMessage(Text.literal(token.tokenType.defaultName
                            + " can't be set here — wrong block type.").formatted(Formatting.RED), true);
                return ActionResult.PASS; // let vanilla interaction (e.g. open chest) happen
            }

            if (world.isClient) return ActionResult.SUCCESS; // block vanilla open, server handles stamp

            String blockName = world.getBlockState(pos).getBlock().getName().getString();
            LocationTokenItem.stampPos(stack, pos, blockName);
            LocationTokenItem.stampCreator(stack, player.getName().getString());
            player.sendMessage(Text.literal(token.tokenType.defaultName + " set to "
                    + blockName + " " + pos.toShortString()).formatted(Formatting.AQUA), true);

            if (player instanceof ServerPlayerEntity sp) {
                int slot = sp.getInventory().selectedSlot;
                sp.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, 0, slot, stack));
            }
            return ActionResult.SUCCESS;
        });

        // Livestock Tag: right-click any non-air block to stamp the pen position into the tag.
        // Intercepts before containers open so stamping a chest doesn't also open it.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof LivestockTagItem)) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            if (world.getBlockState(pos).isAir()) return ActionResult.PASS;

            if (world.isClient) return ActionResult.SUCCESS;

            LivestockTagItem.setPos(stack, pos);
            player.sendMessage(Text.literal("Pen set to " + pos.toShortString()).formatted(Formatting.AQUA), true);
            if (player instanceof ServerPlayerEntity sp) {
                int slot = sp.getInventory().selectedSlot;
                sp.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, 0, slot, stack));
            }
            return ActionResult.SUCCESS;
        });

        // Livestock Tag: right-click an animal with a stamped tag to claim it to that pen.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof LivestockTagItem)) return ActionResult.PASS;
            if (!(entity instanceof SheepEntity || entity instanceof CowEntity
                    || entity instanceof PigEntity || entity instanceof ChickenEntity))
                return ActionResult.PASS;

            if (world.isClient) return ActionResult.SUCCESS;

            // Sneak + right-click: untag
            if (player.isSneaking()) {
                LivestockTaggable taggable = (LivestockTaggable) entity;
                if (taggable.npclogistics_isTagged()) {
                    taggable.npclogistics_setTagged(false, null);
                    player.sendMessage(
                            Text.literal(entity.getName().getString() + " untagged.").formatted(Formatting.YELLOW),
                            true);
                }
                return ActionResult.SUCCESS;
            }

            if (!LivestockTagItem.hasPos(stack)) {
                player.sendMessage(
                        Text.literal("Right-click a block first to set the pen location.").formatted(Formatting.RED),
                        true);
                return ActionResult.FAIL;
            }

            BlockPos jobsite = LivestockTagItem.getPos(stack);
            ((LivestockTaggable) entity).npclogistics_setTagged(true, jobsite);
            player.sendMessage(
                    Text.literal(entity.getName().getString() + " tagged to pen at " + jobsite.toShortString())
                            .formatted(Formatting.GREEN),
                    true);

            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        });

        // Every 5 seconds (100 ticks): nudge tagged animals that have strayed >20 blocks back toward their jobsite.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 100 != 0) return;
            for (ServerWorld world : server.getWorlds()) {
                for (net.minecraft.entity.Entity e : world.iterateEntities()) {
                    if (!(e instanceof MobEntity mob)) continue;
                    if (!(mob instanceof LivestockTaggable taggable)) continue;
                    if (!taggable.npclogistics_isTagged()) continue;
                    BlockPos jobsite = taggable.npclogistics_getJobsite();
                    if (jobsite == null) continue;
                    if (!mob.getNavigation().isIdle()) continue;
                    double dx = mob.getX() - (jobsite.getX() + 0.5);
                    double dz = mob.getZ() - (jobsite.getZ() + 0.5);
                    if (dx * dx + dz * dz > 20.0 * 20.0) {
                        mob.getNavigation().startMovingTo(
                                jobsite.getX() + 0.5, jobsite.getY(), jobsite.getZ() + 0.5, 0.7);
                    }
                }
            }
        });

        LOGGER.info("NPClogistics initialized.");
    }

    private static boolean isValidBlockForToken(World world, BlockPos pos,
                                                 LocationTokenItem.TokenType type) {
        return switch (type) {
            case COLLECT, DEPOSIT -> world.getBlockEntity(pos) instanceof Inventory;
            case CRAFT             -> world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE);
            case JOBSITE           -> !world.getBlockState(pos).isAir();
            case BED               -> world.getBlockState(pos).getBlock() instanceof net.minecraft.block.BedBlock;
        };
    }
}
