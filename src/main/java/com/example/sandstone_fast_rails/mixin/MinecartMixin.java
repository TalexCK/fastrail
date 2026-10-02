package com.example.sandstone_fast_rails.mixin;

import com.example.sandstone_fast_rails.FastRailCart;
import com.example.sandstone_fast_rails.SandstoneRailUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kinetic-energy rail model.
 *
 * A cart on a sandstone rail keeps its own track speed V (up to 2 blocks/tick).
 * Every tick vanilla moves the cart once; the remaining distance is covered by
 * extra vanilla moveAlongTrack steps of at most 0.4 blocks, so curves,
 * junctions and slopes use vanilla geometry at any speed. The steps only move
 * the cart. Speed changes once per tick from energy:
 * powered rails add energy per block travelled, climbing trades speed for
 * height, the vanilla slowdown factor applies per tick, and inactive powered
 * rails brake. Turning never costs energy.
 *
 * After leaving sandstone the cart keeps its energy and slows with vanilla
 * friction (ordinary rails never speed it up), then hands back to vanilla
 * once it is at vanilla speed. Ordinary active powered rails slow it to vanilla
 * powered-rail speed within a few blocks; ordinary inactive powered rails brake
 * it more gently than sandstone ones. Carts that are not boosted are never touched.
 */
@Mixin(Minecart.class)
public abstract class MinecartMixin implements FastRailCart {
    @Unique private boolean sandstoneFastRails$boosted;
    @Unique private double sandstoneFastRails$speed;
    @Unique private Vec3 sandstoneFastRails$lastSetMovement = Vec3.ZERO;

    @Unique private boolean sandstoneFastRails$tracking;
    @Unique private Vec3 sandstoneFastRails$headPos;
    @Unique private Vec3 sandstoneFastRails$headMovement;
    @Unique private BlockPos sandstoneFastRails$headRail;
    @Unique private double sandstoneFastRails$headSpeed;

    @Unique private double sandstoneFastRails$sandstoneDistance;
    @Unique private double sandstoneFastRails$sandstonePoweredDistance;
    @Unique private double sandstoneFastRails$poweredDistance;
    @Unique private double sandstoneFastRails$sandstoneBrakeDistance;
    @Unique private double sandstoneFastRails$brakeDistance;

    @Override
    public boolean sandstoneFastRails$isBoosted() {
        return sandstoneFastRails$boosted;
    }

    @Override
    public double sandstoneFastRails$getRailSpeed() {
        return sandstoneFastRails$speed;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sandstoneFastRails$tickHead(CallbackInfo ci) {
        Minecart cart = (Minecart) (Object) this;
        Level level = cart.level();
        sandstoneFastRails$tracking = false;

        if (!(level instanceof ServerLevel)
                || !(cart.getBehavior() instanceof OldMinecartBehavior)
                || cart.isInWater()) {
            sandstoneFastRails$reset();
            return;
        }

        BlockPos rail = cart.getCurrentBlockPosOrRailBelow();
        if (!SandstoneRailUtil.isRailAt(level, rail)) {
            sandstoneFastRails$reset();
            return;
        }

        Vec3 movement = cart.getDeltaMovement();
        double factor = sandstoneFastRails$movementFactor(cart);

        if (sandstoneFastRails$boosted) {
            // Something outside the rail tick (entity push, command, other mod)
            // changed the velocity: scale the track speed by the same ratio.
            if (movement.distanceToSqr(sandstoneFastRails$lastSetMovement) > 1.0E-12D) {
                double lastLength = SandstoneRailUtil.horizontalLength(sandstoneFastRails$lastSetMovement);
                double length = SandstoneRailUtil.horizontalLength(movement);
                sandstoneFastRails$speed = lastLength > 1.0E-6D
                        ? sandstoneFastRails$speed * length / lastLength
                        : length * factor;
            }
        } else {
            if (!SandstoneRailUtil.isFastRailAt(level, rail)) {
                return; // Pure vanilla.
            }
            sandstoneFastRails$boosted = true;
            sandstoneFastRails$speed = Math.min(
                    SandstoneRailUtil.horizontalLength(movement) * factor,
                    SandstoneRailUtil.VANILLA_MAX_STEP);
        }

        sandstoneFastRails$speed = Math.min(sandstoneFastRails$speed, SandstoneRailUtil.MAX_SPEED);
        sandstoneFastRails$tracking = true;
        sandstoneFastRails$headPos = cart.position();
        sandstoneFastRails$headMovement = movement;
        sandstoneFastRails$headRail = rail.immutable();
        sandstoneFastRails$headSpeed = sandstoneFastRails$speed;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void sandstoneFastRails$tickTail(CallbackInfo ci) {
        if (!sandstoneFastRails$tracking) {
            return;
        }
        sandstoneFastRails$tracking = false;

        Minecart cart = (Minecart) (Object) this;
        if (!(cart.level() instanceof ServerLevel level) || cart.isRemoved() || cart.isInWater()) {
            sandstoneFastRails$reset();
            return;
        }

        double factor = sandstoneFastRails$movementFactor(cart);
        double headSpeed = sandstoneFastRails$headSpeed;
        Vec3 vanillaMovement = cart.getDeltaMovement();
        double moved = cart.position().distanceTo(sandstoneFastRails$headPos);
        if (moved > SandstoneRailUtil.TELEPORT_DISTANCE) {
            sandstoneFastRails$reset();
            return;
        }

        Vec3 direction = SandstoneRailUtil.horizontalDirection(vanillaMovement);
        if (direction == null) {
            direction = SandstoneRailUtil.horizontalDirection(sandstoneFastRails$headMovement);
        }

        sandstoneFastRails$sandstoneDistance = 0.0D;
        sandstoneFastRails$sandstonePoweredDistance = 0.0D;
        sandstoneFastRails$poweredDistance = 0.0D;
        sandstoneFastRails$sandstoneBrakeDistance = 0.0D;
        sandstoneFastRails$brakeDistance = 0.0D;
        sandstoneFastRails$recordDistance(level, sandstoneFastRails$headRail, moved);

        boolean collided = cart.horizontalCollision
                && SandstoneRailUtil.horizontalLength(vanillaMovement) < 1.0E-6D;
        boolean onTrack = true;

        // Cover the rest of this tick's distance with vanilla-sized steps.
        // Each step's speed is set by us, so vanilla friction, slope push and
        // powered-rail boost inside the step never change the track speed.
        double budget = Math.min(headSpeed, SandstoneRailUtil.MAX_SPEED) - moved;
        for (int step = 0; step < SandstoneRailUtil.MAX_SUB_STEPS
                && !collided && direction != null && budget > 1.0E-4D; step++) {
            BlockPos rail = cart.getCurrentBlockPosOrRailBelow();
            if (!SandstoneRailUtil.isRailAt(level, rail)) {
                onTrack = false; // Ran off the end of the track.
                break;
            }
            if (cart.isInWater()) {
                break;
            }

            double stepLength = Math.min(budget, SandstoneRailUtil.VANILLA_MAX_STEP);
            Vec3 before = cart.position();
            cart.setDeltaMovement(direction.x * stepLength / factor, 0.0D, direction.z * stepLength / factor);
            cart.getBehavior().moveAlongTrack(level);
            if (cart.isRemoved()) {
                return;
            }

            double stepMoved = cart.position().distanceTo(before);
            sandstoneFastRails$recordDistance(level, rail, stepMoved);
            budget -= stepMoved;

            Vec3 after = cart.getDeltaMovement();
            if (cart.horizontalCollision && SandstoneRailUtil.horizontalLength(after) < 1.0E-6D) {
                collided = true;
                break;
            }
            Vec3 nextDirection = SandstoneRailUtil.horizontalDirection(after);
            if (nextDirection != null) {
                direction = nextDirection;
            }
            if (stepMoved < 1.0E-4D) {
                break; // Stopped by a brake or stuck.
            }
        }

        double speed = collided || direction == null
                ? 0.0D
                : sandstoneFastRails$nextSpeed(cart, level, headSpeed, vanillaMovement, factor);

        if (!onTrack || !SandstoneRailUtil.isRailAt(level, cart.getCurrentBlockPosOrRailBelow())) {
            // Left the track (rail end): continue with vanilla's off-rail motion.
            if (direction != null) {
                double offRail = Math.min(speed, SandstoneRailUtil.VANILLA_MAX_STEP);
                cart.setDeltaMovement(direction.x * offRail, 0.0D, direction.z * offRail);
            }
            sandstoneFastRails$reset();
            return;
        }

        Vec3 movement = direction == null
                ? Vec3.ZERO
                : direction.scale(Math.min(speed, SandstoneRailUtil.VANILLA_MAX_STEP) / factor);
        boolean onSandstone = SandstoneRailUtil.isFastRailAt(level, cart.getCurrentBlockPosOrRailBelow());

        if (!onSandstone && speed <= SandstoneRailUtil.VANILLA_MAX_STEP * factor) {
            // Back at vanilla speed on an ordinary rail: vanilla moves factor * |v|
            // per tick, so this velocity continues at exactly the same speed.
            cart.setDeltaMovement(direction == null ? Vec3.ZERO : direction.scale(speed / factor));
            sandstoneFastRails$reset();
            return;
        }

        cart.setDeltaMovement(movement);
        sandstoneFastRails$lastSetMovement = cart.getDeltaMovement();
        sandstoneFastRails$speed = speed;
    }

    /** New track speed from this tick's energy changes. */
    @Unique
    private double sandstoneFastRails$nextSpeed(Minecart cart, Level level, double headSpeed,
                                                Vec3 vanillaMovement, double factor) {
        double energy = 0.5D * headSpeed * headSpeed;
        energy += sandstoneFastRails$sandstonePoweredDistance * SandstoneRailUtil.SANDSTONE_POWERED_RAIL_ENERGY;
        energy -= SandstoneRailUtil.GRAVITY * (cart.getY() - sandstoneFastRails$headPos.y);
        double speed = energy > 0.0D ? Math.sqrt(2.0D * energy) : 0.0D;

        speed *= cart.getBehavior().getSlowdownFactor();

        // Sandstone brake: strong, per block and per tick.
        BlockPos headRail = sandstoneFastRails$headRail;
        boolean headOnSandstoneBrake = SandstoneRailUtil.isInactivePoweredRail(level.getBlockState(headRail))
                && SandstoneRailUtil.isFastRailAt(level, headRail);
        speed *= Math.pow(SandstoneRailUtil.SANDSTONE_BRAKE_RETENTION, sandstoneFastRails$sandstoneBrakeDistance);
        if (headOnSandstoneBrake) {
            speed *= SandstoneRailUtil.SANDSTONE_BRAKE_TICK_RETENTION;
        }
        if ((headOnSandstoneBrake || sandstoneFastRails$sandstoneBrakeDistance > 0.0D)
                && speed < SandstoneRailUtil.SANDSTONE_BRAKE_STOP_SPEED) {
            return 0.0D;
        }

        // Ordinary brake on a boosted cart: milder, per block only. At vanilla
        // speed the cart is handed back and vanilla's own brake applies.
        speed *= Math.pow(SandstoneRailUtil.BRAKE_RETENTION, sandstoneFastRails$brakeDistance);
        if (sandstoneFastRails$brakeDistance > 0.0D && speed < SandstoneRailUtil.BRAKE_STOP_SPEED) {
            return 0.0D;
        }

        // Ordinary active powered rail: slow an over-speed cart down to vanilla
        // powered-rail speed, never below it.
        double vanillaSpeed = SandstoneRailUtil.VANILLA_MAX_STEP * factor;
        if (sandstoneFastRails$poweredDistance > 0.0D && speed > vanillaSpeed) {
            speed = Math.max(vanillaSpeed, speed
                    * Math.pow(SandstoneRailUtil.POWERED_RAIL_OVERSPEED_RETENTION, sandstoneFastRails$poweredDistance));
        }

        // From (almost) rest, keep vanilla's start-up impulses: player input,
        // the powered-rail kick off a solid block, entity pushes.
        if (headSpeed < SandstoneRailUtil.START_SPEED) {
            speed = Math.max(speed, SandstoneRailUtil.horizontalLength(vanillaMovement) * factor);
        }

        // Ordinary rails (powered rails, downhill) never speed up a boosted
        // cart: above vanilla speed it only slows down until vanilla takes over.
        if (sandstoneFastRails$sandstoneDistance <= 0.0D) {
            speed = Math.min(speed, headSpeed);
        }

        speed = Math.min(speed, SandstoneRailUtil.MAX_SPEED);
        return speed < SandstoneRailUtil.STOP_SPEED ? 0.0D : speed;
    }

    /** Attribute a travelled distance to the rail the movement started on. */
    @Unique
    private void sandstoneFastRails$recordDistance(Level level, BlockPos rail, double distance) {
        if (distance <= 0.0D || rail == null) {
            return;
        }
        BlockState state = level.getBlockState(rail);
        boolean sandstone = SandstoneRailUtil.isFastRailAt(level, rail);
        if (sandstone) {
            sandstoneFastRails$sandstoneDistance += distance;
        }
        if (SandstoneRailUtil.isActivePoweredRail(state)) {
            if (sandstone) {
                sandstoneFastRails$sandstonePoweredDistance += distance;
            } else {
                sandstoneFastRails$poweredDistance += distance;
            }
        } else if (SandstoneRailUtil.isInactivePoweredRail(state)) {
            if (sandstone) {
                sandstoneFastRails$sandstoneBrakeDistance += distance;
            } else {
                sandstoneFastRails$brakeDistance += distance;
            }
        }
    }

    @Unique
    private static double sandstoneFastRails$movementFactor(Minecart cart) {
        return cart.isVehicle() ? SandstoneRailUtil.RIDDEN_MOVEMENT_FACTOR : 1.0D;
    }

    @Unique
    private void sandstoneFastRails$reset() {
        sandstoneFastRails$boosted = false;
        sandstoneFastRails$speed = 0.0D;
        sandstoneFastRails$lastSetMovement = Vec3.ZERO;
        sandstoneFastRails$tracking = false;
    }
}
