package com.npclogistics.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.data.WorkOrder.StopAction;
import com.npclogistics.entity.LogisticsWorkerEntity;
import com.npclogistics.entity.ModEntities;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /workorder <subcommand>
 *
 *   spawn <pos>                            - Spawn a Logistics Worker at the given position
 *   assign <worker> start                  - Start a simple test order
 *   assign <worker> cancel                 - Cancel the worker's current order
 *   addstop <worker> <pos> collect         - Add a COLLECT stop at pos
 *   addstop <worker> <pos> deliver         - Add a DELIVER stop at pos
 *   status <worker>                        - Print current state of the worker
 *   setrepeating <worker> <true|false>     - Toggle repeating mode
 */
public class WorkOrderCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {

        dispatcher.register(literal("workorder")
            .requires(source -> source.hasPermissionLevel(2))

            // ---- bare /workorder prints friendly usage ----
            .executes(ctx -> {
                ServerCommandSource s = ctx.getSource();
                s.sendFeedback(() -> Text.literal("§e§lNPC Logistics — /workorder"), false);
                s.sendFeedback(() -> Text.literal("§7  spawn [x y z] §f– spawn a worker (at you if no coords)"), false);
                s.sendFeedback(() -> Text.literal("§7  status <worker> §f– show a worker's current job"), false);
                s.sendFeedback(() -> Text.literal("§7  startorder/addstop/cancel/setrepeating <worker> …"), false);
                s.sendFeedback(() -> Text.literal("§aTip: §fright-click a worker with an §eempty hand§f to open the editor,"), false);
                s.sendFeedback(() -> Text.literal("§for use a §eWork Order Scroll§f on chests, then on the worker."), false);
                return 1;
            })

            // ---- spawn ----  (no pos = at the command source's feet)
            .then(literal("spawn")
                .executes(ctx -> spawnWorker(ctx.getSource(), BlockPos.ofFloored(ctx.getSource().getPosition())))
                .then(argument("pos", BlockPosArgumentType.blockPos())
                    .executes(ctx -> spawnWorker(ctx.getSource(), BlockPosArgumentType.getBlockPos(ctx, "pos")))
                )
            )

            // ---- addstop ----
            .then(literal("addstop")
                .then(argument("worker", EntityArgumentType.entity())
                    .then(argument("pos", BlockPosArgumentType.blockPos())
                        .then(literal("collect")
                            .executes(ctx -> addStop(ctx.getSource(),
                                EntityArgumentType.getEntity(ctx, "worker"),
                                BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                StopAction.COLLECT))
                        )
                        .then(literal("deliver")
                            .executes(ctx -> addStop(ctx.getSource(),
                                EntityArgumentType.getEntity(ctx, "worker"),
                                BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                StopAction.DELIVER))
                        )
                        .then(literal("both")
                            .executes(ctx -> addStop(ctx.getSource(),
                                EntityArgumentType.getEntity(ctx, "worker"),
                                BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                StopAction.BOTH))
                        )
                    )
                )
            )

            // ---- startorder ----
            .then(literal("startorder")
                .then(argument("worker", EntityArgumentType.entity())
                    .then(argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            var entity = EntityArgumentType.getEntity(ctx, "worker");
                            if (!(entity instanceof LogisticsWorkerEntity worker)) {
                                ctx.getSource().sendError(Text.literal("Target is not a Logistics Worker."));
                                return 0;
                            }
                            String name = StringArgumentType.getString(ctx, "name");
                            WorkOrder order = new WorkOrder(name, worker.getHomePos(), false);
                            worker.startWorkOrder(order);
                            ctx.getSource().sendFeedback(() -> Text.literal("Started work order '" + name + "'."), true);
                            return 1;
                        })
                    )
                )
            )

            // ---- cancel ----
            .then(literal("cancel")
                .then(argument("worker", EntityArgumentType.entity())
                    .executes(ctx -> {
                        var entity = EntityArgumentType.getEntity(ctx, "worker");
                        if (!(entity instanceof LogisticsWorkerEntity worker)) {
                            ctx.getSource().sendError(Text.literal("Target is not a Logistics Worker."));
                            return 0;
                        }
                        worker.cancelWorkOrder();
                        ctx.getSource().sendFeedback(() -> Text.literal("Work order cancelled."), true);
                        return 1;
                    })
                )
            )

            // ---- status ----
            .then(literal("status")
                .then(argument("worker", EntityArgumentType.entity())
                    .executes(ctx -> {
                        var entity = EntityArgumentType.getEntity(ctx, "worker");
                        if (!(entity instanceof LogisticsWorkerEntity worker)) {
                            ctx.getSource().sendError(Text.literal("Target is not a Logistics Worker."));
                            return 0;
                        }
                        WorkOrder order = worker.getActiveWorkOrder();
                        StringBuilder sb = new StringBuilder();
                        sb.append("Worker: ").append(worker.getName().getString()).append("\n");
                        sb.append("State: ").append(worker.getWorkerState()).append("\n");
                        sb.append("Home: ").append(worker.getHomePos().toShortString()).append("\n");
                        if (order != null) {
                            sb.append("Order: ").append(order.getName())
                              .append(" [").append(worker.getCurrentStopIndex())
                              .append("/").append(order.getStops().size()).append("] ")
                              .append(order.isRepeating() ? "(repeating)" : "").append("\n");
                            for (int i = 0; i < order.getStops().size(); i++) {
                                RouteStop stop = order.getStops().get(i);
                                sb.append("  ").append(i == worker.getCurrentStopIndex() ? ">> " : "   ")
                                  .append("[").append(i).append("] ")
                                  .append(stop.action).append(" @ ").append(stop.pos.toShortString())
                                  .append(" (filter: ").append(stop.itemFilter.isEmpty() ? "all" : stop.itemFilter.size() + " items").append(")\n");
                            }
                        } else {
                            sb.append("Order: none\n");
                        }
                        String msg = sb.toString();
                        ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
                        return 1;
                    })
                )
            )

            // ---- setrepeating ----
            .then(literal("setrepeating")
                .then(argument("worker", EntityArgumentType.entity())
                    .then(literal("true").executes(ctx -> setRepeating(ctx.getSource(),
                            EntityArgumentType.getEntity(ctx, "worker"), true)))
                    .then(literal("false").executes(ctx -> setRepeating(ctx.getSource(),
                            EntityArgumentType.getEntity(ctx, "worker"), false)))
                )
            )
        );
    }

    // -----------------------------------------------------------------------

    private static int spawnWorker(ServerCommandSource src, BlockPos pos) {
        LogisticsWorkerEntity worker = new LogisticsWorkerEntity(ModEntities.LOGISTICS_WORKER, src.getWorld());
        worker.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        worker.setHomePos(pos);
        src.getWorld().spawnEntity(worker);
        src.sendFeedback(() -> Text.literal("Spawned Logistics Worker at " + pos.toShortString()
                + " — right-click it with an empty hand to open the editor."), true);
        return 1;
    }

    private static int addStop(ServerCommandSource src, net.minecraft.entity.Entity entity,
                                BlockPos pos, StopAction action) {
        if (!(entity instanceof LogisticsWorkerEntity worker)) {
            src.sendError(Text.literal("Target is not a Logistics Worker."));
            return 0;
        }
        if (worker.getActiveWorkOrder() == null) {
            // Auto-create a work order if none exists
            worker.startWorkOrder(new WorkOrder("Auto Order", worker.getHomePos(), false));
        }
        worker.getActiveWorkOrder().addStop(new RouteStop(pos, new ArrayList<>(), action, 0));
        src.sendFeedback(() -> Text.literal("Added " + action.name() + " stop at " + pos.toShortString()), true);
        return 1;
    }

    private static int setRepeating(ServerCommandSource src, net.minecraft.entity.Entity entity, boolean repeating) {
        if (!(entity instanceof LogisticsWorkerEntity worker)) {
            src.sendError(Text.literal("Target is not a Logistics Worker."));
            return 0;
        }
        if (worker.getActiveWorkOrder() == null) {
            src.sendError(Text.literal("Worker has no active work order."));
            return 0;
        }
        worker.getActiveWorkOrder().setRepeating(repeating);
        src.sendFeedback(() -> Text.literal("Repeating set to " + repeating), true);
        return 1;
    }
}
