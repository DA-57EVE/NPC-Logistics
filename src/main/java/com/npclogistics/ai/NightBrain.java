package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LogisticsWorkerEntity;
import com.npclogistics.item.LocationTokenItem;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.state.property.Properties;
import net.minecraft.entity.EntityPose;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Handles NPC night behaviour:
 *   1. Navigate to the assigned bed token, or scan for any nearby bed.
 *   2. Fall back to navigating inside the nearest door (a building).
 *   3. If no shelter is found, stop in place and snore.
 *
 * The brain activates when {@link LogisticsWorkerEntity#isTooDarkToWork} returns true
 * and releases control when {@link #wakeIfSleeping} is called at dawn.
 */
public class NightBrain {

    private static final double NAV_SPEED        = 0.9;
    private static final double ARRIVAL_DIST     = 1.5;   // door/in-place shelter
    private static final double BED_ARRIVAL_DIST = 2.5;   // bed — teleported on, just need to be close
    private static final int    BED_SCAN_RADIUS  = 16;
    private static final int    DOOR_SCAN_RADIUS = 32;
    private static final int    SNORE_INTERVAL   = 100;  // ~5 s
    private static final int    NAV_TIMEOUT      = 300;  // 15 s stuck guard

    private enum Phase { SEEKING, SLEEPING }

    private final LogisticsWorkerEntity worker;
    private Phase    phase       = Phase.SEEKING;
    private boolean  inBed       = false;
    private boolean  settled     = false;
    private int      snoreTimer  = 0;
    private int      navTimer    = 0;
    private BlockPos sleepTarget = null; // approach position to navigate to
    private BlockPos bedBlockPos = null; // actual bed block (may differ from approach)

    public NightBrain(LogisticsWorkerEntity worker) {
        this.worker = worker;
    }

    // -----------------------------------------------------------------------
    //  Main entry points
    // -----------------------------------------------------------------------

    public void tick(ServerWorld world) {
        switch (phase) {
            case SEEKING  -> tickSeeking(world);
            case SLEEPING -> tickSleeping(world);
        }
    }

    /** Called once when daylight returns.  Clears sleeping pose and resets for next night. */
    public void wakeIfSleeping(ServerWorld world) {
        if (!settled) return;
        if (inBed && bedBlockPos != null) {
            // Move to a safe floor space beside the bed before standing to avoid
            // clipping into a low ceiling.
            BlockPos standPos = findSafeStandPosition(world, bedBlockPos);
            if (standPos != null) {
                worker.refreshPositionAndAngles(
                        standPos.getX() + 0.5, standPos.getY(),
                        standPos.getZ() + 0.5, worker.getYaw(), worker.getPitch());
            }
        }
        // Always clear sleeping state — stale SLEEPING pose must never survive into daytime.
        worker.clearSleepingPosition();
        worker.setPose(EntityPose.STANDING);
        worker.getNavigation().stop();
        settled      = false;
        inBed        = false;
        phase        = Phase.SEEKING;
        sleepTarget  = null;
        bedBlockPos  = null;
        navTimer     = 0;
        NPClogistics.LOGGER.info("{} woke up at dawn.", worker.getName().getString());
    }

    /**
     * Scans outward from the bed (radius 1 then 2) for a floor position with 2 clear blocks of
     * headroom.  Returns null only if the entire 5×5 area around the bed is blocked/ceilinged.
     */
    private static BlockPos findSafeStandPosition(ServerWorld world, BlockPos bedPos) {
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // perimeter only
                    BlockPos candidate = bedPos.add(dx, 0, dz);
                    if (world.getBlockState(candidate).isAir()
                            && world.getBlockState(candidate.up()).isAir()
                            && !world.getBlockState(candidate.down()).isAir()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    //  SEEKING
    // -----------------------------------------------------------------------

    private void tickSeeking(ServerWorld world) {
        // First tick of this night — locate a sleep spot.
        if (sleepTarget == null && bedBlockPos == null) {
            locateSleepSpot(world);
            if (sleepTarget == null) {
                settle(world);   // sleep in place, no shelter found
                return;
            }
        }

        // Check if we've arrived.
        double arrivalDist = (bedBlockPos != null) ? BED_ARRIVAL_DIST : ARRIVAL_DIST;
        if (sleepTarget != null && worker.getPos().distanceTo(sleepTarget.toCenterPos()) <= arrivalDist) {
            settle(world);
            return;
        }

        // Stuck detection.
        if (++navTimer > NAV_TIMEOUT) {
            NPClogistics.LOGGER.info("{} night: navigation timeout, sleeping in place.", worker.getName().getString());
            sleepTarget = null;
            bedBlockPos = null;
            settle(world);
            return;
        }

        if (worker.getNavigation().isIdle() && sleepTarget != null) {
            worker.getNavigation().startMovingTo(
                    sleepTarget.getX() + 0.5, sleepTarget.getY(), sleepTarget.getZ() + 0.5, NAV_SPEED);
        }
    }

    /**
     * Determines and caches a sleep target for this night.
     * Priority: assigned bed token > nearest bed block > nearest door interior > in place.
     */
    private void locateSleepSpot(ServerWorld world) {
        // 1. Assigned bed token.
        BlockPos tokenPos = getBedTokenPos();
        if (tokenPos != null) {
            bedBlockPos = resolveBedHead(world, tokenPos);
            sleepTarget = bedBlockPos;
            NPClogistics.LOGGER.info("{} night: heading to assigned bed at {}.", worker.getName().getString(), bedBlockPos);
            return;
        }

        // 2. Scan for any nearby bed block.
        BlockPos nearestBed = scanForBed(world);
        if (nearestBed != null) {
            bedBlockPos = resolveBedHead(world, nearestBed);
            sleepTarget = bedBlockPos;
            NPClogistics.LOGGER.info("{} night: heading to nearby bed at {}.", worker.getName().getString(), bedBlockPos);
            return;
        }

        // 3. Scan for a door and go inside.
        BlockPos insidePos = scanForDoorInside(world);
        if (insidePos != null) {
            sleepTarget = insidePos;
            bedBlockPos = null;
            NPClogistics.LOGGER.info("{} night: heading inside door shelter at {}.", worker.getName().getString(), insidePos);
            return;
        }

        // 4. Nowhere to go — snore in place.
        NPClogistics.LOGGER.info("{} night: no shelter found, sleeping in place.", worker.getName().getString());
    }

    // -----------------------------------------------------------------------
    //  SLEEPING
    // -----------------------------------------------------------------------

    private void settle(ServerWorld world) {
        worker.getNavigation().stop();
        settled = true;
        phase   = Phase.SLEEPING;

        if (bedBlockPos != null && world.getBlockState(bedBlockPos).getBlock() instanceof BedBlock
                && findSafeStandPosition(world, bedBlockPos) != null) {
            // Only sleep in the bed if there is a confirmed safe position to stand up into at dawn.
            // If the room is too cramped (all adjacent spots blocked or ceiling too low), fall through
            // to in-place snoring — better than waking up clipped into the ceiling.
            inBed = true;
            // Offset 0.25 blocks toward foot so the NPC isn't pressed against the headboard.
            BlockState headState = world.getBlockState(bedBlockPos);
            Direction towardFoot = headState.get(Properties.HORIZONTAL_FACING).getOpposite();
            worker.refreshPositionAndAngles(
                    bedBlockPos.getX() + 0.5 + 0.25 * towardFoot.getOffsetX(),
                    bedBlockPos.getY() + 0.5625,
                    bedBlockPos.getZ() + 0.5 + 0.25 * towardFoot.getOffsetZ(),
                    worker.getYaw(), worker.getPitch());
            worker.setSleepingPosition(bedBlockPos);
            worker.setPose(EntityPose.SLEEPING);
        } else {
            inBed = false;
            if (bedBlockPos != null)
                NPClogistics.LOGGER.info("{} night: no safe wake position beside bed, snoring in place.",
                        worker.getName().getString());
        }
        snoreTimer = 0;
    }

    private void tickSleeping(ServerWorld world) {
        if (--snoreTimer <= 0) {
            snoreTimer = SNORE_INTERVAL;
            world.playSound(null, worker.getX(), worker.getY(), worker.getZ(),
                    SoundEvents.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, 0.25f, 0.6f);
        }
    }

    // -----------------------------------------------------------------------
    //  Bed token helpers
    // -----------------------------------------------------------------------

    private BlockPos getBedTokenPos() {
        net.minecraft.item.ItemStack token = worker.getBedToken();
        if (!token.isEmpty() && LocationTokenItem.hasPos(token))
            return LocationTokenItem.getPos(token);
        return null;
    }

    // -----------------------------------------------------------------------
    //  World scans
    // -----------------------------------------------------------------------

    private BlockPos scanForBed(ServerWorld world) {
        BlockPos origin = worker.getBlockPos();
        int r = BED_SCAN_RADIUS;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (world.getBlockState(p).getBlock() instanceof BedBlock) {
                        double d = worker.getPos().distanceTo(p.toCenterPos());
                        if (d < bestDist) { bestDist = d; best = p.toImmutable(); }
                    }
                }
            }
        }
        return best;
    }

    /**
     * If pos is the FOOT half of a bed, returns the HEAD half; otherwise returns pos unchanged.
     * Beds must be slept in at the HEAD so the entity renders in the correct half.
     */
    private BlockPos resolveBedHead(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) return pos;
        if (state.get(Properties.BED_PART) == BedPart.FOOT) {
            BlockPos head = pos.offset(state.get(Properties.HORIZONTAL_FACING));
            if (world.getBlockState(head).getBlock() instanceof BedBlock)
                return head.toImmutable();
        }
        return pos;
    }

    /** Returns a position 2 blocks past the nearest door, on the inside of the building. */
    private BlockPos scanForDoorInside(ServerWorld world) {
        BlockPos origin = worker.getBlockPos();
        int r = DOOR_SCAN_RADIUS;
        BlockPos bestDoor = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (world.getBlockState(p).getBlock() instanceof DoorBlock) {
                        double d = worker.getPos().distanceTo(p.toCenterPos());
                        if (d < bestDist) { bestDist = d; bestDoor = p.toImmutable(); }
                    }
                }
            }
        }
        if (bestDoor == null) return null;

        // Determine "inward" direction: from NPC toward the door, then 2 blocks past it.
        int dx = bestDoor.getX() - origin.getX();
        int dz = bestDoor.getZ() - origin.getZ();
        Direction inward = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        return bestDoor.offset(inward, 2).toImmutable();
    }

}
