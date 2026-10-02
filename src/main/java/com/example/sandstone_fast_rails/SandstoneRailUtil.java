package com.example.sandstone_fast_rails;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Constants and rail queries for the kinetic-energy rail model.
 *
 * Speeds are real track speeds in blocks per tick (20 ticks = 1 second).
 * Energy is E = V^2 / 2 per unit mass.
 */
public final class SandstoneRailUtil {
    /** Hard speed cap: 2 blocks/tick = 40 blocks/s. */
    public static final double MAX_SPEED = 2.0D;

    /** Longest single vanilla movement step (vanilla rail speed limit). */
    public static final double VANILLA_MAX_STEP = 0.4D;

    /** Vanilla moves a ridden minecart only 75% of its velocity per tick. */
    public static final double RIDDEN_MOVEMENT_FACTOR = 0.75D;

    /** Upper bound on extra vanilla movement steps per tick. */
    public static final int MAX_SUB_STEPS = 16;

    /** Energy lost per block climbed (real gravity: 9.8 m/s^2 = 0.0245 blocks/tick^2). */
    public static final double GRAVITY = 0.0245D;

    /** Energy added per block travelled on an active powered rail on sandstone. */
    public static final double SANDSTONE_POWERED_RAIL_ENERGY = 0.5D;

    /**
     * Speed kept per block travelled on an active powered rail NOT on sandstone,
     * while the cart is faster than vanilla. It slows to vanilla powered-rail
     * speed within a few blocks, then vanilla takes over.
     */
    public static final double POWERED_RAIL_OVERSPEED_RETENTION = 0.5D;

    /** Strong sandstone brake: speed kept per block on an inactive powered rail on sandstone. */
    public static final double SANDSTONE_BRAKE_RETENTION = 0.02D;

    /** Strong sandstone brake: extra per-tick factor when the tick starts on that rail. */
    public static final double SANDSTONE_BRAKE_TICK_RETENTION = 0.25D;

    /** A cart on a sandstone brake is stopped below this speed. */
    public static final double SANDSTONE_BRAKE_STOP_SPEED = 0.05D;

    /**
     * Mild brake for a boosted cart on an inactive powered rail NOT on sandstone:
     * speed kept per block. Once at vanilla speed, vanilla's own brake applies.
     */
    public static final double BRAKE_RETENTION = 0.5D;

    /** Vanilla stops a cart on an inactive powered rail below this speed. */
    public static final double BRAKE_STOP_SPEED = 0.03D;

    /** Below this speed, vanilla start-up impulses (player input, powered-rail kick) are adopted. */
    public static final double START_SPEED = 0.02D;

    /** Sandstone-managed carts slower than this are stopped instead of creeping. */
    public static final double STOP_SPEED = 0.003D;

    /** A position change larger than this in one vanilla move is a teleport. */
    public static final double TELEPORT_DISTANCE = 4.0D;

    private SandstoneRailUtil() {
    }

    public static boolean isRailAt(Level level, BlockPos pos) {
        return pos != null && level.getBlockState(pos).is(BlockTags.RAILS);
    }

    /** A rail directly on top of a sandstone block. */
    public static boolean isFastRailAt(Level level, BlockPos pos) {
        return isRailAt(level, pos) && level.getBlockState(pos.below()).is(Blocks.SANDSTONE);
    }

    public static boolean isActivePoweredRail(BlockState state) {
        return state.is(Blocks.POWERED_RAIL) && state.getValue(PoweredRailBlock.POWERED);
    }

    public static boolean isInactivePoweredRail(BlockState state) {
        return state.is(Blocks.POWERED_RAIL) && !state.getValue(PoweredRailBlock.POWERED);
    }

    public static double horizontalLength(Vec3 vector) {
        return Math.sqrt(vector.x * vector.x + vector.z * vector.z);
    }

    /** Unit horizontal direction, or null for (near) zero horizontal movement. */
    public static Vec3 horizontalDirection(Vec3 vector) {
        double length = horizontalLength(vector);
        if (length < 1.0E-7D) {
            return null;
        }
        return new Vec3(vector.x / length, 0.0D, vector.z / length);
    }
}
