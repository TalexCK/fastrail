package com.example.sandstone_fast_rails.mixin;

import com.example.sandstone_fast_rails.SandstoneRailUtil;
import com.example.sandstone_fast_rails.RailStepState;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecart.class)
public abstract class MinecartMixin implements RailStepState {
    @Unique private boolean sandstoneFastRails$extraRailStep;
    @Unique private boolean sandstoneFastRails$reducedCurveFriction;
    @Unique private boolean sandstoneFastRails$boostMomentum;

    @Override
    public boolean sandstoneFastRails$isExtraRailStep() {
        return sandstoneFastRails$extraRailStep;
    }

    @Override
    public boolean sandstoneFastRails$hasReducedCurveFriction() {
        return sandstoneFastRails$reducedCurveFriction;
    }

    @Unique
    private BlockPos sandstoneFastRails$tickStartRailPos = null;

    @Unique
    private BlockPos sandstoneFastRails$lastRailPos = null;

    @Unique
    private Direction sandstoneFastRails$lastDirection = null;

    @Inject(method = "tick", at = @At("HEAD"))
    private void sandstoneFastRails$tickHead(CallbackInfo ci) {
        Minecart cart = (Minecart) (Object) this;
        Level level = cart.level();

        sandstoneFastRails$tickStartRailPos = copyPos(
                SandstoneRailUtil.findRailAtCart(level, cart.blockPosition())
        );
        sandstoneFastRails$reducedCurveFriction = level instanceof ServerLevel
                && cart.getBehavior() instanceof OldMinecartBehavior
                && !cart.isInWater()
                && sandstoneFastRails$tickStartRailPos != null
                && SandstoneRailUtil.isFastRailAt(level, sandstoneFastRails$tickStartRailPos)
                && SandstoneRailUtil.needsVanillaCurveMovement(level, sandstoneFastRails$tickStartRailPos);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void sandstoneFastRails$tickTail(CallbackInfo ci) {
        Minecart cart = (Minecart) (Object) this;
        Level level = cart.level();

        if (!(level instanceof ServerLevel)) {
            return;
        }

        BlockPos currentRailPos = SandstoneRailUtil.findRailAtCart(level, cart.blockPosition());

        if (currentRailPos == null || cart.isInWater()) {
            sandstoneFastRails$boostMomentum = false;
            sandstoneFastRails$clearRailHistory();
            return;
        }

        // Ordinary supports always use vanilla friction. Never use the old
        // sandstone look-ahead/recovery branch while actually on an ordinary rail.
        if (!SandstoneRailUtil.isFastRailAt(level, currentRailPos)) {
            sandstoneFastRails$clearRailHistory();
            if (sandstoneFastRails$boostMomentum) {
                sandstoneFastRails$applyVanillaRailSteps(cart, (ServerLevel) level);
            }
            return;
        }

        // Curves AND material boundaries use short vanilla steps. In particular,
        // do not teleport to the center of the first ordinary rail and discard
        // the remaining travel distance on the sandstone-to-normal handoff.
        if (SandstoneRailUtil.needsVanillaCurveMovement(level, sandstoneFastRails$tickStartRailPos)
                || SandstoneRailUtil.needsVanillaCurveMovement(level, currentRailPos)
                || SandstoneRailUtil.needsVanillaMaterialHandoff(level, currentRailPos)) {
            sandstoneFastRails$clearRailHistory();
            if (sandstoneFastRails$boostMomentum
                    || SandstoneRailUtil.isFastRailAt(level, currentRailPos)
                    || sandstoneFastRails$reducedCurveFriction) {
                sandstoneFastRails$applyVanillaRailSteps(cart, (ServerLevel) level);
            }
            return;
        }

        if (sandstoneFastRails$tickStartRailPos != null
                && currentRailPos != null
                && !sandstoneFastRails$tickStartRailPos.equals(currentRailPos)) {
            Direction realDirection = SandstoneRailUtil.directionBetween(
                    sandstoneFastRails$tickStartRailPos,
                    currentRailPos
            );

            if (realDirection != null) {
                sandstoneFastRails$lastDirection = realDirection;
                sandstoneFastRails$lastRailPos = copyPos(sandstoneFastRails$tickStartRailPos);
            }
        }

        BlockPos fastRailPos = SandstoneRailUtil.findFastRailAtCartOrExpected(
                level,
                cart.blockPosition(),
                sandstoneFastRails$lastRailPos,
                sandstoneFastRails$lastDirection
        );

        if (fastRailPos == null) {
            if (currentRailPos != null) {
                sandstoneFastRails$lastRailPos = copyPos(currentRailPos);
            }

            return;
        }

        // The recovery path can predict a rail other than the one under the cart.
        if (SandstoneRailUtil.needsVanillaCurveMovement(level, fastRailPos)) {
            sandstoneFastRails$clearRailHistory();
            sandstoneFastRails$applyVanillaRailSteps(cart, (ServerLevel) level);
            return;
        }

        BlockPos previousRailForBoost = sandstoneFastRails$lastRailPos;

        if (sandstoneFastRails$tickStartRailPos != null
                && !sandstoneFastRails$tickStartRailPos.equals(fastRailPos)) {
            previousRailForBoost = sandstoneFastRails$tickStartRailPos;
        }

        sandstoneFastRails$applyRailFollowingBoost(
                cart,
                level,
                fastRailPos,
                previousRailForBoost
        );
        sandstoneFastRails$boostMomentum = SandstoneRailUtil.horizontalSpeed(cart.getDeltaMovement())
                > SandstoneRailUtil.MIN_MOVEMENT_SPEED;
    }

    @Unique
    private void sandstoneFastRails$applyVanillaRailSteps(Minecart cart, ServerLevel level) {
        if (!(cart.getBehavior() instanceof OldMinecartBehavior)) {
            sandstoneFastRails$boostMomentum = false;
            return;
        }
        sandstoneFastRails$boostMomentum = true;
        // The normal tick already moved and applied friction once. Additional
        // geometry steps must not compound that friction.
        for (int step = 1; step < (int) SandstoneRailUtil.FAST_RAIL_VISUAL_MULTIPLIER; step++) {
            BlockPos rail = cart.getCurrentBlockPosOrRailBelow();
            if (!SandstoneRailUtil.isRailAt(level, rail)) {
                sandstoneFastRails$boostMomentum = false;
                break;
            }
            var state = level.getBlockState(rail);
            if (SandstoneRailUtil.isAscendingRail(level, rail)
                    || (state.is(Blocks.POWERED_RAIL) && !state.getValue(PoweredRailBlock.POWERED))) {
                break; // Avoid repeating slope forces or powered-rail braking.
            }
            double speed = SandstoneRailUtil.horizontalSpeed(cart.getDeltaMovement());
            // The stored velocity drives 5 movement steps. Stop only when the
            // total visible speed is below the near-zero threshold, not while
            // still travelling at five times that threshold.
            double stopSpeed = SandstoneRailUtil.MIN_MOVEMENT_SPEED
                    / SandstoneRailUtil.FAST_RAIL_VISUAL_MULTIPLIER;
            if (speed <= stopSpeed) {
                if (!SandstoneRailUtil.isFastRailAt(level, rail)) {
                    Vec3 stopped = cart.getDeltaMovement();
                    cart.setDeltaMovement(0.0D, stopped.y, 0.0D);
                }
                sandstoneFastRails$boostMomentum = false;
                break;
            }
            double stepSpeed = Math.min(speed, SandstoneRailUtil.MAX_STORED_SPEED);
            Vec3 velocity = cart.getDeltaMovement();
            cart.setDeltaMovement(velocity.x * stepSpeed / speed, velocity.y, velocity.z * stepSpeed / speed);
            sandstoneFastRails$extraRailStep = true;
            try {
                cart.getBehavior().moveAlongTrack(level);
            } finally {
                sandstoneFastRails$extraRailStep = false;
            }
            // Do not multiply powered-rail acceleration in the extra steps.
            Vec3 after = cart.getDeltaMovement();
            double afterSpeed = SandstoneRailUtil.horizontalSpeed(after);
            if (afterSpeed > stepSpeed) {
                cart.setDeltaMovement(after.x * stepSpeed / afterSpeed, after.y, after.z * stepSpeed / afterSpeed);
            }
            if (cart.horizontalCollision) {
                break;
            }
        }
    }

    @Unique
    private void sandstoneFastRails$applyRailFollowingBoost(
            Minecart cart,
            Level level,
            BlockPos startRailPos,
            BlockPos previousRailForBoost
    ) {
        Vec3 oldVelocity = cart.getDeltaMovement();
        double oldSpeed = SandstoneRailUtil.horizontalSpeed(oldVelocity);

        Direction currentDirection = sandstoneFastRails$selectDirection(
                level,
                startRailPos,
                previousRailForBoost,
                oldVelocity
        );

        if (currentDirection == null) {
            sandstoneFastRails$lastRailPos = copyPos(startRailPos);
            return;
        }

        boolean powered = SandstoneRailUtil.isPoweredRailAt(level, startRailPos);

        double storedSpeedAfterThisTick = sandstoneFastRails$calculateStoredSpeed(
                powered,
                oldSpeed
        );

        if (storedSpeedAfterThisTick <= SandstoneRailUtil.MIN_MOVEMENT_SPEED) {
            cart.setDeltaMovement(Vec3.ZERO);
            sandstoneFastRails$lastRailPos = copyPos(startRailPos);
            sandstoneFastRails$lastDirection = null;
            return;
        }

        double targetVisualSpeed = storedSpeedAfterThisTick * SandstoneRailUtil.FAST_RAIL_VISUAL_MULTIPLIER;

        if (targetVisualSpeed > SandstoneRailUtil.FAST_MAX_SPEED) {
            targetVisualSpeed = SandstoneRailUtil.FAST_MAX_SPEED;
        }

        if (SandstoneRailUtil.isSlopeAhead(level, startRailPos, currentDirection)) {
            if (targetVisualSpeed > SandstoneRailUtil.SLOPE_MAX_VISUAL_SPEED) {
                targetVisualSpeed = SandstoneRailUtil.SLOPE_MAX_VISUAL_SPEED;
            }
        }

        double remainingExtraDistance = targetVisualSpeed - oldSpeed;

        if (remainingExtraDistance <= 0.00001D) {
            Vec3 finalVelocity = SandstoneRailUtil.velocityForRailDirection(
                    level,
                    startRailPos,
                    currentDirection,
                    storedSpeedAfterThisTick
            );

            cart.setDeltaMovement(finalVelocity);

            sandstoneFastRails$lastRailPos = copyPos(startRailPos);
            sandstoneFastRails$lastDirection = currentDirection;
            return;
        }

        BlockPos currentRail = startRailPos;
        BlockPos previousRail = previousRailForBoost;

        int safetyCounter = 5;

        while (remainingExtraDistance > 0.00001D && safetyCounter-- > 0) {
            BlockPos nextRail = SandstoneRailUtil.findNextConnectedRail(
                    level,
                    currentRail,
                    currentDirection
            );

            if (nextRail == null) {
                if (SandstoneRailUtil.isRailEndExitClear(level, currentRail, currentDirection)) {
                    Vec3 exitPos = SandstoneRailUtil.railExitPosition(currentRail, currentDirection);

                    cart.setPos(exitPos.x, exitPos.y, exitPos.z);

                    cart.setDeltaMovement(SandstoneRailUtil.velocityFromDirection(
                            currentDirection,
                            storedSpeedAfterThisTick
                    ));

                    sandstoneFastRails$lastRailPos = null;
                    sandstoneFastRails$lastDirection = currentDirection;

                    return;
                }

                sandstoneFastRails$stopCartAtRail(cart, currentRail);
                return;
            }

            /*
             * 不再检测侧边、上方、下方的其它方块是否“挡住”。
             * 只要下一节铁轨本身存在，就继续跑。
             */
            if (!SandstoneRailUtil.isFastRailAt(level, nextRail)) {
                Vec3 nextCenter = SandstoneRailUtil.railCenter(nextRail);
                cart.setPos(nextCenter.x, nextCenter.y, nextCenter.z);

                cart.setDeltaMovement(SandstoneRailUtil.velocityTowardRail(
                        currentRail,
                        nextRail,
                        storedSpeedAfterThisTick
                ));

                sandstoneFastRails$lastRailPos = copyPos(nextRail);
                sandstoneFastRails$lastDirection = currentDirection;

                return;
            }

            boolean slopeSegment = SandstoneRailUtil.isSlopeBetween(currentRail, nextRail)
                    || SandstoneRailUtil.isAscendingRail(level, currentRail)
                    || SandstoneRailUtil.isAscendingRail(level, nextRail);

            if (slopeSegment) {
                double slopeStep = Math.min(remainingExtraDistance, 0.75D);

                Vec3 partialPos = SandstoneRailUtil.railInterpolatedPosition(
                        currentRail,
                        nextRail,
                        slopeStep
                );

                cart.setPos(partialPos.x, partialPos.y, partialPos.z);

                cart.setDeltaMovement(SandstoneRailUtil.velocityTowardRail(
                        currentRail,
                        nextRail,
                        storedSpeedAfterThisTick
                ));

                if (slopeStep >= 0.70D) {
                    previousRail = currentRail;
                    currentRail = nextRail;

                    Direction nextDirection = SandstoneRailUtil.chooseNextDirection(
                            level,
                            currentRail,
                            previousRail,
                            SandstoneRailUtil.velocityForRailDirection(
                                    level,
                                    currentRail,
                                    currentDirection,
                                    targetVisualSpeed
                            )
                    );

                    if (nextDirection != null) {
                        currentDirection = nextDirection;
                    }
                }

                remainingExtraDistance = 0.0D;
            } else if (remainingExtraDistance >= 1.0D) {
                Vec3 nextCenter = SandstoneRailUtil.railCenter(nextRail);
                cart.setPos(nextCenter.x, nextCenter.y, nextCenter.z);

                previousRail = currentRail;
                currentRail = nextRail;

                remainingExtraDistance -= 1.0D;

                Direction nextDirection = SandstoneRailUtil.chooseNextDirection(
                        level,
                        currentRail,
                        previousRail,
                        SandstoneRailUtil.velocityForRailDirection(
                                level,
                                currentRail,
                                currentDirection,
                                targetVisualSpeed
                        )
                );

                if (nextDirection != null) {
                    currentDirection = nextDirection;
                }

                sandstoneFastRails$lastDirection = currentDirection;
            } else {
                Vec3 partialPos = SandstoneRailUtil.railInterpolatedPosition(
                        currentRail,
                        nextRail,
                        remainingExtraDistance
                );

                cart.setPos(partialPos.x, partialPos.y, partialPos.z);

                cart.setDeltaMovement(SandstoneRailUtil.velocityTowardRail(
                        currentRail,
                        nextRail,
                        storedSpeedAfterThisTick
                ));

                remainingExtraDistance = 0.0D;
            }
        }

        cart.setDeltaMovement(SandstoneRailUtil.velocityForRailDirection(
                level,
                currentRail,
                currentDirection,
                storedSpeedAfterThisTick
        ));

        sandstoneFastRails$lastRailPos = copyPos(currentRail);
        sandstoneFastRails$lastDirection = currentDirection;
    }

    @Unique
    private double sandstoneFastRails$calculateStoredSpeed(boolean powered, double oldSpeed) {
        if (powered) {
            double accelerated = oldSpeed + SandstoneRailUtil.POWERED_FAST_RAIL_ACCELERATION;

            if (accelerated < SandstoneRailUtil.POWERED_FAST_RAIL_MIN_START_SPEED) {
                accelerated = SandstoneRailUtil.POWERED_FAST_RAIL_MIN_START_SPEED;
            }

            if (accelerated > SandstoneRailUtil.MAX_STORED_SPEED) {
                accelerated = SandstoneRailUtil.MAX_STORED_SPEED;
            }

            return accelerated;
        }

        double slowed = oldSpeed * SandstoneRailUtil.UNPOWERED_FAST_RAIL_FRICTION_MULTIPLIER
                - SandstoneRailUtil.UNPOWERED_FAST_RAIL_FIXED_SPEED_LOSS;

        if (slowed <= SandstoneRailUtil.MIN_MOVEMENT_SPEED) {
            return 0.0D;
        }

        return slowed;
    }

    @Unique
    private Direction sandstoneFastRails$selectDirection(
            Level level,
            BlockPos currentRail,
            BlockPos previousRail,
            Vec3 oldVelocity
    ) {
        if (previousRail != null && !previousRail.equals(currentRail)) {
            Direction direction = SandstoneRailUtil.chooseNextDirection(
                    level,
                    currentRail,
                    previousRail,
                    oldVelocity
            );

            if (direction != null) {
                return direction;
            }
        }

        if (sandstoneFastRails$lastDirection != null
                && SandstoneRailUtil.isDirectionRailExit(level, currentRail, sandstoneFastRails$lastDirection)) {
            return sandstoneFastRails$lastDirection;
        }

        double speed = SandstoneRailUtil.horizontalSpeed(oldVelocity);

        if (speed > 0.0001D) {
            Direction velocityDirection = SandstoneRailUtil.directionFromVelocity(oldVelocity);

            if (SandstoneRailUtil.isDirectionRailExit(level, currentRail, velocityDirection)) {
                return velocityDirection;
            }
        }

        int connectedCount = 0;
        Direction onlyConnectedDirection = null;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (SandstoneRailUtil.isDirectionRailExit(level, currentRail, direction)
                    && SandstoneRailUtil.hasConnectedRail(level, currentRail, direction)) {
                connectedCount++;
                onlyConnectedDirection = direction;
            }
        }

        if (connectedCount == 1) {
            return onlyConnectedDirection;
        }

        return null;
    }

    @Unique
    private void sandstoneFastRails$clearRailHistory() {
        sandstoneFastRails$lastRailPos = null;
        sandstoneFastRails$lastDirection = null;
    }

    @Unique
    private void sandstoneFastRails$stopCartAtRail(Minecart cart, BlockPos railPos) {
        Vec3 center = SandstoneRailUtil.railCenter(railPos);

        cart.setPos(center.x, center.y, center.z);
        cart.setDeltaMovement(Vec3.ZERO);

        sandstoneFastRails$lastRailPos = copyPos(railPos);
        sandstoneFastRails$lastDirection = null;
    }

    @Unique
    private static BlockPos copyPos(BlockPos pos) {
        if (pos == null) {
            return null;
        }

        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }
}













