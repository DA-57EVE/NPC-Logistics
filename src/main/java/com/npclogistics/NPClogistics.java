package com.npclogistics;

import com.npclogistics.command.WorkOrderCommand;
import com.npclogistics.data.WorkOrder.StopAction;
import com.npclogistics.entity.ModEntities;
import com.npclogistics.item.LocationTokenItem;
import com.npclogistics.item.ModItems;
import com.npclogistics.item.WorkOrderScrollItem;
import com.npclogistics.network.ModNetworking;
import com.npclogistics.screen.ModScreenHandlers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
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

        // Location token right-click: stamp any block's position into the token's NBT.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof LocationTokenItem token)) return ActionResult.PASS;
            if (world.isClient) return ActionResult.SUCCESS;

            BlockPos pos = hitResult.getBlockPos();
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

        LOGGER.info("NPClogistics initialized.");
    }
}
