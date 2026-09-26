package com.example.sandstone_fast_rails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

public final class SandstoneRailUtil {
    public static final double VANILLA_MAX_SPEED_APPROX = 0.4D;

    /**
     * 当前砂岩铁轨视觉速度倍率。
     */
    public static final double FAST_RAIL_VISUAL_MULTIPLIER = 5.0D;

    /**
     * 平地/弯道视觉最高速度。
     */
    public static final double FAST_MAX_SPEED = VANILLA_MAX_SPEED_APPROX * FAST_RAIL_VISUAL_MULTIPLIER;

    /**
     * 上坡/下坡时的最大视觉速度。
     * 防止上坡时一 tick 跨太多格而脱轨。
     */
    public static final double SLOPE_MAX_VISUAL_SPEED = 0.85D;

    /**
     * 内部速度上限。
     */
    public static final double MAX_STORED_SPEED = VANILLA_MAX_SPEED_APPROX;

    public static final double POWERED_FAST_RAIL_ACCELERATION = 0.028D;
    public static final double POWERED_FAST_RAIL_MIN_START_SPEED = 0.06D;

    public static final double UNPOWERED_FAST_RAIL_FRICTION_MULTIPLIER = 0.98D;
    public static final double UNPOWERED_FAST_RAIL_FIXED_SPEED_LOSS = 0.012D;

    public static final double MIN_MOVEMENT_SPEED = 0.003D;

    private SandstoneRailUtil() {
    }

    public static boolean isMinecartOnFastRail(Minecart cart) {
        Level level = cart.level();
        BlockPos cartPos = cart.blockPosition();

        return findFastRailAtCart(level, cartPos) != null;
    }

    /**
     * 查找矿车当前位置、脚下，以及更低几格的砂岩铁轨。
     */
    public static BlockPos findFastRailAtCart(Level level, BlockPos cartPos) {
        if (isFastRailAt(level, cartPos)) {
            return cartPos;
        }

        BlockPos below1 = cartPos.below();
        if (isFastRailAt(level, below1)) {
            return below1;
        }

        BlockPos below2 = below1.below();
        if (isFastRailAt(level, below2)) {
            return below2;
        }

        BlockPos below3 = below2.below();
        if (isFastRailAt(level, below3)) {
            return below3;
        }

        return null;
    }

    /**
     * 查找矿车当前位置、脚下，以及更低几格的任意铁轨。
     */
    public static BlockPos findRailAtCart(Level level, BlockPos cartPos) {
        if (isRailAt(level, cartPos)) {
            return cartPos;
        }

        BlockPos below1 = cartPos.below();
        if (isRailAt(level, below1)) {
            return below1;
        }

        BlockPos below2 = below1.below();
        if (isRailAt(level, below2)) {
            return below2;
        }

        BlockPos below3 = below2.below();
        if (isRailAt(level, below3)) {
            return below3;
        }

        return null;
    }

    /**
     * 当前脚下找不到砂岩铁轨时，尝试按上一节铁轨和方向预测下一节砂岩铁轨。
     */
    public static BlockPos findFastRailAtCartOrExpected(
            Level level,
            BlockPos cartPos,
            BlockPos lastRailPos,
            Direction lastDirection
    ) {
        BlockPos direct = findFastRailAtCart(level, cartPos);

        if (direct != null) {
            return direct;
        }

        if (lastRailPos == null || lastDirection == null) {
            return null;
        }

        BlockPos expectedNext = findNextConnectedRail(level, lastRailPos, lastDirection);

        if (expectedNext != null && isFastRailAt(level, expectedNext)) {
            if (isCartCloseEnoughToRail(cartPos, expectedNext, 5.0D)) {
                return expectedNext;
            }
        }

        if (expectedNext != null) {
            BlockPos expectedSecond = findNextConnectedRail(level, expectedNext, lastDirection);

            if (expectedSecond != null && isFastRailAt(level, expectedSecond)) {
                if (isCartCloseEnoughToRail(cartPos, expectedSecond, 7.0D)) {
                    return expectedSecond;
                }
            }
        }

        return null;
    }

    public static boolean isCartCloseEnoughToRail(BlockPos cartPos, BlockPos railPos, double maxDistanceSq) {
        return cartPos.distSqr(railPos) <= maxDistanceSq;
    }

    public static boolean isRailAt(Level level, BlockPos railPos) {
        return level.getBlockState(railPos).is(BlockTags.RAILS);
    }

    public static boolean isFastRailAt(Level level, BlockPos railPos) {
        BlockState railState = level.getBlockState(railPos);

        if (!railState.is(BlockTags.RAILS)) {
            return false;
        }

        BlockState belowState = level.getBlockState(railPos.below());

        return belowState.is(Blocks.SANDSTONE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean isPoweredRailAt(Level level, BlockPos railPos) {
        BlockState state = level.getBlockState(railPos);

        if (!state.is(BlockTags.RAILS)) {
            return false;
        }

        for (Property<?> property : state.getProperties()) {
            if (!"powered".equals(property.getName())) {
                continue;
            }

            Object value = state.getValue((Property) property);

            if (value instanceof Boolean powered) {
                return powered;
            }
        }

        return false;
    }

    public static double horizontalSpeed(Vec3 velocity) {
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    public static Vec3 velocityFromDirection(Direction direction, double speed) {
        return new Vec3(
                direction.getStepX() * speed,
                0.0D,
                direction.getStepZ() * speed
        );
    }

    /**
     * 根据 currentRail -> nextRail 生成带 Y 分量的速度。
     */
    public static Vec3 velocityTowardRail(BlockPos currentRail, BlockPos nextRail, double horizontalSpeed) {
        Vec3 from = railCenter(currentRail);
        Vec3 to = railCenter(nextRail);

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        double horizontalLength = Math.sqrt(dx * dx + dz * dz);

        if (horizontalLength <= 0.00001D) {
            return Vec3.ZERO;
        }

        double scale = horizontalSpeed / horizontalLength;

        double y = dy * scale;

        if (y > 0.12D) {
            y = 0.12D;
        }

        if (y < -0.12D) {
            y = -0.12D;
        }

        return new Vec3(
                dx * scale,
                y,
                dz * scale
        );
    }

    public static Vec3 velocityForRailDirection(Level level, BlockPos currentRail, Direction direction, double speed) {
        BlockPos nextRail = findNextConnectedRail(level, currentRail, direction);

        if (nextRail != null) {
            return velocityTowardRail(currentRail, nextRail, speed);
        }

        return velocityFromDirection(direction, speed);
    }

    public static Direction directionFromVelocity(Vec3 velocity) {
        double absX = Math.abs(velocity.x);
        double absZ = Math.abs(velocity.z);

        if (absX >= absZ) {
            return velocity.x >= 0.0D ? Direction.EAST : Direction.WEST;
        } else {
            return velocity.z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static RailShape getRailShape(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (!"shape".equals(property.getName())) {
                continue;
            }

            Object value = state.getValue((Property) property);

            if (value instanceof RailShape railShape) {
                return railShape;
            }
        }

        return null;
    }

    public static Direction getAscendingDirection(RailShape shape) {
        if (shape == null) {
            return null;
        }

        return switch (shape) {
            case ASCENDING_EAST -> Direction.EAST;
            case ASCENDING_WEST -> Direction.WEST;
            case ASCENDING_NORTH -> Direction.NORTH;
            case ASCENDING_SOUTH -> Direction.SOUTH;
            default -> null;
        };
    }

    public static boolean isAscendingRail(Level level, BlockPos railPos) {
        RailShape shape = getRailShape(level.getBlockState(railPos));
        return getAscendingDirection(shape) != null;
    }

    public static boolean isCurvedRail(Level level, BlockPos railPos) {
        if (railPos == null) {
            return false;
        }

        RailShape shape = getRailShape(level.getBlockState(railPos));
        return shape == RailShape.NORTH_EAST || shape == RailShape.NORTH_WEST
                || shape == RailShape.SOUTH_EAST || shape == RailShape.SOUTH_WEST;
    }

    /**
     * Let vanilla handle a curve and its approaches. A junction can be entered
     * from a side that is not one of the curve's two nominal exits, so choosing
     * an exit ourselves does not reproduce vanilla minecart physics.
     *
     * Check both travel directions over the entire possible boost distance,
     * including raised/lowered rails. This also prevents an extra movement step
     * from teleporting onto or across a curve before the next vanilla tick.
     */
    public static boolean needsVanillaCurveMovement(Level level, BlockPos railPos) {
        if (railPos == null) {
            return false;
        }
        if (isCurvedRail(level, railPos)) {
            return true;
        }

        RailShape shape = getRailShape(level.getBlockState(railPos));
        if (shape == null) {
            return false;
        }

        int lookAhead = (int) Math.ceil(FAST_MAX_SPEED);
        for (Direction direction : getRailExits(shape)) {
            BlockPos next = railPos;
            for (int i = 0; i < lookAhead; i++) {
                next = findNextConnectedRail(level, next, direction);
                if (next == null) {
                    break;
                }
                if (isCurvedRail(level, next)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Use vanilla-sized steps near a support-material boundary so the boost's
     * remaining distance is preserved while changing to ordinary rail friction.
     */
    public static boolean needsVanillaMaterialHandoff(Level level, BlockPos railPos) {
        if (railPos == null || !isFastRailAt(level, railPos)) {
            return false;
        }
        RailShape shape = getRailShape(level.getBlockState(railPos));
        for (Direction direction : getRailExits(shape)) {
            BlockPos next = railPos;
            for (int step = 0; step < (int) Math.ceil(FAST_MAX_SPEED); step++) {
                next = findNextConnectedRail(level, next, direction);
                if (next == null) {
                    break;
                }
                if (!isFastRailAt(level, next)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Direction[] getRailExits(RailShape shape) {
        if (shape == null) {
            return new Direction[]{
                    Direction.NORTH,
                    Direction.SOUTH,
                    Direction.EAST,
                    Direction.WEST
            };
        }

        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> new Direction[]{
                    Direction.NORTH,
                    Direction.SOUTH
            };

            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new Direction[]{
                    Direction.EAST,
                    Direction.WEST
            };

            case NORTH_EAST -> new Direction[]{
                    Direction.NORTH,
                    Direction.EAST
            };

            case NORTH_WEST -> new Direction[]{
                    Direction.NORTH,
                    Direction.WEST
            };

            case SOUTH_EAST -> new Direction[]{
                    Direction.SOUTH,
                    Direction.EAST
            };

            case SOUTH_WEST -> new Direction[]{
                    Direction.SOUTH,
                    Direction.WEST
            };
        };
    }

    public static boolean isDirectionRailExit(Level level, BlockPos railPos, Direction direction) {
        RailShape shape = getRailShape(level.getBlockState(railPos));

        for (Direction exit : getRailExits(shape)) {
            if (exit == direction) {
                return true;
            }
        }

        return false;
    }

    public static Direction directionBetween(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        if (dx == 0 && dz == 0) {
            return null;
        }

        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    /**
     * 找指定方向上的下一节任意铁轨。
     *
     * 对上坡和下坡做了优先处理。
     */
    public static BlockPos findNextConnectedRail(Level level, BlockPos currentRail, Direction direction) {
        BlockState currentState = level.getBlockState(currentRail);
        RailShape currentShape = getRailShape(currentState);
        Direction ascendingDirection = getAscendingDirection(currentShape);

        BlockPos front = currentRail.relative(direction);

        if (ascendingDirection == direction) {
            BlockPos up = front.above();

            if (isRailAt(level, up)) {
                return up;
            }

            if (isRailAt(level, front)) {
                return front;
            }

            BlockPos down = front.below();

            if (isRailAt(level, down)) {
                return down;
            }

            return null;
        }

        if (ascendingDirection != null && ascendingDirection.getOpposite() == direction) {
            if (isRailAt(level, front)) {
                return front;
            }

            BlockPos down = front.below();

            if (isRailAt(level, down)) {
                return down;
            }

            BlockPos up = front.above();

            if (isRailAt(level, up)) {
                return up;
            }

            return null;
        }

        if (isRailAt(level, front)) {
            return front;
        }

        BlockPos up = front.above();

        if (isRailAt(level, up)) {
            return up;
        }

        BlockPos down = front.below();

        if (isRailAt(level, down)) {
            return down;
        }

        return null;
    }

    public static boolean hasConnectedRail(Level level, BlockPos currentRail, Direction direction) {
        return findNextConnectedRail(level, currentRail, direction) != null;
    }

    /**
     * 根据当前铁轨、上一节铁轨和速度，选择下一步方向。
     *
     * 这个方法是 MinecartMixin 会调用的，所以必须保留。
     */
    public static Direction chooseNextDirection(Level level, BlockPos currentRail, BlockPos previousRail, Vec3 velocity) {
        BlockState railState = level.getBlockState(currentRail);
        RailShape shape = getRailShape(railState);
        Direction[] exits = getRailExits(shape);

        Direction directionToPrevious = null;

        if (previousRail != null) {
            directionToPrevious = directionBetween(currentRail, previousRail);
        }

        /*
         * 如果知道上一节铁轨，优先选择不是回头路、且确实连接铁轨的出口。
         */
        if (directionToPrevious != null) {
            for (Direction exit : exits) {
                if (exit == directionToPrevious) {
                    continue;
                }

                if (hasConnectedRail(level, currentRail, exit)) {
                    return exit;
                }
            }

            /*
             * 如果没有真实连接的非回头出口，也不要回头。
             * 这可以防止铁轨尽头时矿车反弹。
             */
            for (Direction exit : exits) {
                if (exit != directionToPrevious) {
                    return exit;
                }
            }
        }

        /*
         * 根据速度方向判断。
         */
        double speed = horizontalSpeed(velocity);

        if (speed > 0.0001D) {
            Direction velocityDirection = directionFromVelocity(velocity);

            for (Direction exit : exits) {
                if (exit == velocityDirection) {
                    return exit;
                }
            }
        }

        /*
         * 选择任意真实连接方向。
         */
        for (Direction exit : exits) {
            if (hasConnectedRail(level, currentRail, exit)) {
                return exit;
            }
        }

        return null;
    }

    public static boolean isSlopeBetween(BlockPos currentRail, BlockPos nextRail) {
        return currentRail != null && nextRail != null && currentRail.getY() != nextRail.getY();
    }

    public static boolean isSlopeAhead(Level level, BlockPos currentRail, Direction direction) {
        BlockPos nextRail = findNextConnectedRail(level, currentRail, direction);

        if (nextRail == null) {
            return false;
        }

        return isSlopeBetween(currentRail, nextRail)
                || isAscendingRail(level, currentRail)
                || isAscendingRail(level, nextRail);
    }

    /**
     * 只检查前方正对的一格是否有实体阻挡。
     *
     * 不再检查上方、侧边、下方，
     * 这样轨道周围有装饰方块时也不会误停。
     */
    public static boolean isRailEndExitClear(Level level, BlockPos railPos, Direction direction) {
        BlockPos front = railPos.relative(direction);
        return level.getBlockState(front).getCollisionShape(level, front).isEmpty();
    }

    public static Vec3 railCenter(BlockPos railPos) {
        return new Vec3(
                railPos.getX() + 0.5D,
                railPos.getY() + 0.1D,
                railPos.getZ() + 0.5D
        );
    }

    public static Vec3 railCenterWithOffset(BlockPos railPos, Direction direction, double offset) {
        return new Vec3(
                railPos.getX() + 0.5D + direction.getStepX() * offset,
                railPos.getY() + 0.1D,
                railPos.getZ() + 0.5D + direction.getStepZ() * offset
        );
    }

    public static Vec3 railInterpolatedPosition(BlockPos currentRail, BlockPos nextRail, double fraction) {
        if (fraction < 0.0D) {
            fraction = 0.0D;
        }

        if (fraction > 1.0D) {
            fraction = 1.0D;
        }

        Vec3 from = railCenter(currentRail);
        Vec3 to = railCenter(nextRail);

        return new Vec3(
                from.x + (to.x - from.x) * fraction,
                from.y + (to.y - from.y) * fraction,
                from.z + (to.z - from.z) * fraction
        );
    }

    public static Vec3 railExitPosition(BlockPos railPos, Direction direction) {
        BlockPos front = railPos.relative(direction);

        return new Vec3(
                front.getX() + 0.5D,
                railPos.getY() + 0.1D,
                front.getZ() + 0.5D
        );
    }
}












