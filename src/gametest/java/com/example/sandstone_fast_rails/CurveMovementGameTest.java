package com.example.sandstone_fast_rails;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CurveMovementGameTest {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final double SPEED_EPSILON = 1.0E-7D;
    private static final double DISTANCE_EPSILON = 0.05D;
    private static final int BOOSTERS = 12;

    private static final RailShape[] CURVES = {
            RailShape.NORTH_EAST, RailShape.NORTH_WEST,
            RailShape.SOUTH_EAST, RailShape.SOUTH_WEST
    };

    /** One cell of a test track. */
    private record Cell(BlockPos pos, BlockState rail, Block support) {
    }

    // ---------------------------------------------------------------- tests

    @GameTest
    public void ordinaryRailsStayVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 2, 4));
        for (RailShape curve : CURVES) {
            for (Direction heading : Direction.Plane.HORIZONTAL) {
                for (double speed : new double[]{0.12D, 0.4D}) {
                    buildJunction(level, origin, curve, Blocks.STONE);
                    Vec3[][] stone = traceJunction(level, origin, heading, speed, true);
                    buildJunction(level, origin, curve, Blocks.COBBLESTONE);
                    Vec3[][] cobble = traceJunction(level, origin, heading, speed, true);
                    String context = curve + "/" + heading + "/" + speed;
                    require(stone.length == cobble.length, "Ordinary rail timing changed: " + context);
                    for (int tick = 0; tick < stone.length; tick++) {
                        require(stone[tick][0].distanceToSqr(cobble[tick][0]) < 1.0E-16D,
                                "Ordinary rail position changed: " + context);
                        require(stone[tick][1].distanceToSqr(cobble[tick][1]) < 1.0E-16D,
                                "Ordinary rail velocity changed: " + context);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void sandstoneJunctionRoutesLikeVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 2, 4));
        for (RailShape curve : CURVES) {
            for (Direction heading : Direction.Plane.HORIZONTAL) {
                for (double speed : new double[]{0.12D, 0.4D}) {
                    buildJunction(level, origin, curve, Blocks.STONE);
                    Vec3[][] vanilla = traceJunction(level, origin, heading, speed, true);
                    buildJunction(level, origin, curve, Blocks.SANDSTONE);
                    Vec3[][] sandstone = traceJunction(level, origin, heading, speed, false);
                    require(exitSide(origin, vanilla) == exitSide(origin, sandstone),
                            "Junction route changed: " + curve + "/" + heading + "/" + speed);
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void plainSandstoneUsesVanillaFriction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 20, 4));
        for (boolean ridden : new boolean[]{false, true}) {
            List<Cell> track = new ArrayList<>();
            addStraight(track, origin, Direction.EAST, 400, Blocks.SANDSTONE, plainRail(Direction.EAST));
            build(level, track);
            Minecart cart = cart(helper, level, track.get(0).pos(), Direction.EAST, 0.4D, ridden);
            double retention = cart.getBehavior().getSlowdownFactor();

            cart.tick();
            require(boosted(cart), "Cart on sandstone was not boosted");
            for (int tick = 0; tick < 300 && speed(cart) > 0.0D; tick++) {
                double before = speed(cart);
                cart.tick();
                requireOnRail(level, cart, "plain sandstone");
                double after = speed(cart);
                if (after == 0.0D) {
                    require(before * retention < SandstoneRailUtil.STOP_SPEED + SPEED_EPSILON,
                            "Stopped too early on plain sandstone");
                    break;
                }
                require(Math.abs(after - before * retention) < SPEED_EPSILON,
                        "Plain sandstone friction is not vanilla f: ridden=" + ridden
                                + " before=" + before + " after=" + after);
            }
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void poweredSandstoneReachesButNeverExceedsMaxSpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 20, 4));
        for (boolean ridden : new boolean[]{false, true}) {
            List<Cell> track = new ArrayList<>();
            addStraight(track, origin, Direction.EAST, 30, Blocks.SANDSTONE, poweredRail(Direction.EAST, true));
            addStraight(track, origin.east(30), Direction.EAST, 80, Blocks.SANDSTONE, plainRail(Direction.EAST));
            build(level, track);

            Minecart cart = cart(helper, level, track.get(0).pos(), Direction.EAST, 0.1D, ridden);
            double top = 0.0D;
            for (int tick = 0; tick < 60 && cart.getX() < origin.getX() + 100; tick++) {
                Vec3 before = cart.position();
                cart.tick();
                requireOnRail(level, cart, "powered sandstone");
                require(cart.position().distanceTo(before) <= SandstoneRailUtil.MAX_SPEED + DISTANCE_EPSILON,
                        "Cart moved faster than 40 blocks/s: " + cart.position().distanceTo(before));
                top = Math.max(top, speed(cart));
            }
            require(top > SandstoneRailUtil.MAX_SPEED - 0.01D, "Powered sandstone never reached 40 blocks/s: " + top);
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void sandstoneCurvesKeepSpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 20, 4));
        for (boolean ridden : new boolean[]{false, true}) {
            // Boosters east, then east -> south -> east -> north -> east with a
            // detector rail on the last straight.
            List<Cell> track = new ArrayList<>();
            BlockPos pos = addStraight(track, origin, Direction.EAST, BOOSTERS, Blocks.SANDSTONE,
                    poweredRail(Direction.EAST, true));
            pos = addStraight(track, pos, Direction.EAST, 6, Blocks.SANDSTONE, plainRail(Direction.EAST));
            pos = addCorner(track, pos, Direction.EAST, Direction.SOUTH);
            pos = addStraight(track, pos, Direction.SOUTH, 6, Blocks.SANDSTONE, plainRail(Direction.SOUTH));
            pos = addCorner(track, pos, Direction.SOUTH, Direction.EAST);
            pos = addCorner(track, pos, Direction.EAST, Direction.NORTH);
            pos = addCorner(track, pos, Direction.NORTH, Direction.EAST);
            pos = addStraight(track, pos, Direction.EAST, 4, Blocks.SANDSTONE, plainRail(Direction.EAST));
            track.add(new Cell(pos, Blocks.DETECTOR_RAIL.defaultBlockState()
                    .setValue(DetectorRailBlock.SHAPE, RailShape.EAST_WEST), Blocks.SANDSTONE));
            pos = pos.east();
            addStraight(track, pos, Direction.EAST, 60, Blocks.SANDSTONE, plainRail(Direction.EAST));
            build(level, track);
            Map<BlockPos, Integer> index = index(track);

            Minecart cart = cart(helper, level, track.get(0).pos(), Direction.EAST, 0.1D, ridden);
            double retention = cart.getBehavior().getSlowdownFactor();
            int checked = 0;
            for (int tick = 0; tick < 80; tick++) {
                Integer start = index.get(cart.getCurrentBlockPosOrRailBelow());
                double before = speed(cart);
                Vec3 beforePos = cart.position();
                cart.tick();
                requireOnRail(level, cart, "sandstone curves ridden=" + ridden);
                require(cart.position().distanceTo(beforePos) <= SandstoneRailUtil.MAX_SPEED + DISTANCE_EPSILON,
                        "Cart moved faster than 40 blocks/s in a curve");
                if (start != null && start > BOOSTERS && speed(cart) > 0.0D) {
                    require(Math.abs(speed(cart) - before * retention) < SPEED_EPSILON,
                            "Curve/detector rail changed speed beyond vanilla f: ridden=" + ridden
                                    + " cell=" + start + " before=" + before + " after=" + speed(cart));
                    checked++;
                }
                if (index.getOrDefault(cart.getCurrentBlockPosOrRailBelow(), 0) > track.size() - 20) {
                    break;
                }
            }
            require(checked > 5, "Cart never reached the curves");
            require(index.getOrDefault(cart.getCurrentBlockPosOrRailBelow(), 0) > track.size() - 20,
                    "Cart did not follow the curves to the final straight");
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void leavingSandstoneCoastsWithoutSuddenSlowdown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 20, 4));
        for (boolean ridden : new boolean[]{false, true}) {
            List<Cell> track = new ArrayList<>();
            BlockPos pos = addStraight(track, origin, Direction.EAST, BOOSTERS, Blocks.SANDSTONE,
                    poweredRail(Direction.EAST, true));
            pos = addStraight(track, pos, Direction.EAST, 4, Blocks.SANDSTONE, plainRail(Direction.EAST));
            addStraight(track, pos, Direction.EAST, 700, Blocks.COBBLESTONE, plainRail(Direction.EAST));
            build(level, track);
            int cobbleX = pos.getX();

            Minecart cart = cart(helper, level, track.get(0).pos(), Direction.EAST, 0.1D, ridden);
            double retention = cart.getBehavior().getSlowdownFactor();
            for (int tick = 0; tick < 100 && cart.getX() < cobbleX; tick++) {
                cart.tick();
            }
            require(cart.getX() >= cobbleX, "Never reached cobblestone");

            double previous = Double.NaN;
            boolean handedBack = false;
            for (int tick = 0; tick < 400; tick++) {
                double beforeX = cart.getX();
                cart.tick();
                requireOnRail(level, cart, "cobblestone coast");
                double distance = cart.getX() - beforeX;
                require(distance >= -1.0E-8D, "Coasting cart reversed");
                if (!Double.isNaN(previous)) {
                    require(distance <= previous + 1.0E-6D, "Ordinary rail accelerated the cart");
                    require(distance >= previous * retention - 1.0E-3D,
                            "Sudden slowdown after leaving sandstone: " + previous + " -> " + distance);
                }
                handedBack |= !boosted(cart);
                previous = distance;
                if (distance < 0.01D) {
                    break;
                }
            }
            require(ridden || handedBack, "Empty cart never handed back to vanilla");
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void ordinaryRailsNeverSpeedUpABoostedCart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 20, 4));
        List<Cell> track = new ArrayList<>();
        BlockPos pos = addStraight(track, origin, Direction.EAST, BOOSTERS, Blocks.SANDSTONE,
                poweredRail(Direction.EAST, true));
        int cobbleX = pos.getX();
        pos = addStraight(track, pos, Direction.EAST, 120, Blocks.COBBLESTONE, poweredRail(Direction.EAST, true));
        addStraight(track, pos, Direction.EAST, 60, Blocks.COBBLESTONE, plainRail(Direction.EAST));
        build(level, track);

        Minecart cart = cart(helper, level, track.get(0).pos(), Direction.EAST, 0.1D, false);
        for (int tick = 0; tick < 100 && cart.getX() < cobbleX + 1; tick++) {
            cart.tick();
        }
        require(boosted(cart), "Cart left the sandstone boosters without being boosted");
        boolean handedBack = false;
        for (int tick = 0; tick < 200 && !handedBack; tick++) {
            double before = speed(cart);
            cart.tick();
            requireOnRail(level, cart, "ordinary powered rails");
            handedBack = !boosted(cart);
            require(handedBack || speed(cart) <= before + 1.0E-9D,
                    "Ordinary powered rail sped up a boosted cart: " + before + " -> " + speed(cart));
        }
        require(handedBack, "Boosted cart never returned to vanilla on ordinary powered rails");
        cart.discard();
        helper.succeed();
    }

    @GameTest
    public void inactivePoweredSandstoneBrakesHard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 20, 4));
        for (boolean ridden : new boolean[]{false, true}) {
            List<Cell> track = new ArrayList<>();
            BlockPos pos = addStraight(track, origin, Direction.EAST, BOOSTERS, Blocks.SANDSTONE,
                    poweredRail(Direction.EAST, true));
            pos = addStraight(track, pos, Direction.EAST, 4, Blocks.SANDSTONE, plainRail(Direction.EAST));
            int brakeX = pos.getX();
            pos = addStraight(track, pos, Direction.EAST, 3, Blocks.SANDSTONE, poweredRail(Direction.EAST, false));
            addStraight(track, pos, Direction.EAST, 60, Blocks.SANDSTONE, plainRail(Direction.EAST));
            build(level, track);

            Minecart cart = cart(helper, level, track.get(0).pos(), Direction.EAST, 0.1D, ridden);
            for (int tick = 0; tick < 100; tick++) {
                cart.tick();
                requireOnRail(level, cart, "brake");
            }
            require(cart.getDeltaMovement().horizontalDistanceSqr() == 0.0D, "Brake did not stop the cart");
            require(cart.getX() < brakeX + 6, "Brake stopped the cart too late: x=" + (cart.getX() - brakeX));
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void hillsTradeSpeedForHeight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 20, 4));
        List<Cell> track = new ArrayList<>();
        BlockPos pos = addStraight(track, origin, Direction.EAST, BOOSTERS, Blocks.SANDSTONE,
                poweredRail(Direction.EAST, true));
        pos = addStraight(track, pos, Direction.EAST, 4, Blocks.SANDSTONE, plainRail(Direction.EAST));
        for (int i = 0; i < 4; i++, pos = pos.east().above()) {
            track.add(new Cell(pos, rail(RailShape.ASCENDING_EAST), Blocks.SANDSTONE));
        }
        pos = addStraight(track, pos, Direction.EAST, 3, Blocks.SANDSTONE, plainRail(Direction.EAST));
        pos = pos.below();
        for (int i = 0; i < 4; i++, pos = pos.east().below()) {
            track.add(new Cell(pos, rail(RailShape.ASCENDING_WEST), Blocks.SANDSTONE));
        }
        pos = pos.above();
        int bottomX = pos.getX();
        addStraight(track, pos, Direction.EAST, 80, Blocks.SANDSTONE, plainRail(Direction.EAST));
        build(level, track);

        Minecart cart = cart(helper, level, track.get(0).pos(), Direction.EAST, 0.1D, true);
        double retention = cart.getBehavior().getSlowdownFactor();
        int ticks = 0;
        double atHill = 0.0D;
        int hillTick = 0;
        while (cart.getX() < bottomX + 2 && ticks++ < 200) {
            if (atHill == 0.0D && cart.getX() > origin.getX() + BOOSTERS + 1) {
                atHill = speed(cart);
                hillTick = ticks;
            }
            cart.tick();
            requireOnRail(level, cart, "hill");
        }
        require(cart.getX() >= bottomX + 2, "Cart did not cross the hill");
        double expected = atHill * Math.pow(retention, ticks - hillTick + 1);
        require(Math.abs(speed(cart) - expected) < 0.05D,
                "Hill cost more than friction: expected ~" + expected + ", got " + speed(cart));
        cart.discard();
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    private static Vec3[][] traceJunction(ServerLevel level, BlockPos origin, Direction heading, double speed,
                                          boolean requireVanilla) {
        Minecart cart = new Minecart(EntityTypes.MINECART, level);
        cart.setPos(origin.getX() + 0.5D - heading.getStepX() * 1.25D,
                origin.getY() + 0.1D,
                origin.getZ() + 0.5D - heading.getStepZ() * 1.25D);
        cart.setDeltaMovement(heading.getStepX() * speed, 0.0D, heading.getStepZ() * speed);
        List<Vec3[]> samples = new ArrayList<>();
        Vec3 center = new Vec3(origin.getX() + 0.5D, origin.getY() + 0.1D, origin.getZ() + 0.5D);
        boolean reachedCurve = false;
        for (int tick = 0; tick < 40; tick++) {
            cart.tick();
            samples.add(new Vec3[]{cart.position(), cart.getDeltaMovement()});
            requireOnRail(level, cart, "junction " + heading);
            require(!requireVanilla || !boosted(cart), "Mod took over a cart on ordinary rails");
            reachedCurve |= (cart.getX() - center.x) * heading.getStepX()
                    + (cart.getZ() - center.z) * heading.getStepZ() > -0.7D;
            double distance = Math.max(Math.abs(cart.getX() - center.x), Math.abs(cart.getZ() - center.z));
            if (reachedCurve && distance > 1.75D) {
                break;
            }
        }
        require(reachedCurve, "Test cart never reached the junction");
        cart.discard();
        return samples.toArray(Vec3[][]::new);
    }

    private static void buildJunction(ServerLevel level, BlockPos origin, RailShape curve, Block support) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos pos = origin.offset(x, 0, z);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), FLAGS);
                level.setBlock(pos.below(), support.defaultBlockState(), FLAGS);
            }
        }
        for (int i = -4; i <= 4; i++) {
            level.setBlock(origin.offset(i, 0, 0), rail(RailShape.EAST_WEST), FLAGS);
            level.setBlock(origin.offset(0, 0, i), rail(RailShape.NORTH_SOUTH), FLAGS);
        }
        level.setBlock(origin, rail(curve), FLAGS);
    }

    private static Direction exitSide(BlockPos origin, Vec3[][] samples) {
        Vec3 offset = samples[samples.length - 1][0].subtract(origin.getX() + 0.5D, 0.0D, origin.getZ() + 0.5D);
        if (Math.abs(offset.x) >= Math.abs(offset.z)) {
            return offset.x >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return offset.z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    private static BlockPos addStraight(List<Cell> track, BlockPos start, Direction direction, int length,
                                        Block support, BlockState rail) {
        BlockPos pos = start;
        for (int i = 0; i < length; i++, pos = pos.relative(direction)) {
            track.add(new Cell(pos, rail, support));
        }
        return pos;
    }

    private static BlockPos addCorner(List<Cell> track, BlockPos pos, Direction from, Direction to) {
        track.add(new Cell(pos, rail(cornerShape(from.getOpposite(), to)), Blocks.SANDSTONE));
        return pos.relative(to);
    }

    private static RailShape cornerShape(Direction a, Direction b) {
        boolean north = a == Direction.NORTH || b == Direction.NORTH;
        boolean east = a == Direction.EAST || b == Direction.EAST;
        return north ? (east ? RailShape.NORTH_EAST : RailShape.NORTH_WEST)
                : (east ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST);
    }

    private static void build(ServerLevel level, List<Cell> track) {
        for (Cell cell : track) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int y = 0; y <= 2; y++) {
                        level.setBlock(cell.pos().offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
        for (Cell cell : track) {
            level.setBlock(cell.pos().below(), cell.support().defaultBlockState(), FLAGS);
            if (SandstoneRailUtil.isActivePoweredRail(cell.rail())) {
                // Keep boosters powered even if the rail re-checks its signal.
                level.setBlock(cell.pos().north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), FLAGS);
            }
        }
        for (Cell cell : track) {
            level.setBlock(cell.pos(), cell.rail(), FLAGS);
        }
    }

    private static Map<BlockPos, Integer> index(List<Cell> track) {
        Map<BlockPos, Integer> index = new HashMap<>();
        for (int i = 0; i < track.size(); i++) {
            index.put(track.get(i).pos(), i);
        }
        return index;
    }

    private static Minecart cart(GameTestHelper helper, ServerLevel level, BlockPos rail, Direction heading,
                                 double speed, boolean ridden) {
        Minecart cart = new Minecart(EntityTypes.MINECART, level);
        cart.setPos(rail.getX() + 0.5D, rail.getY() + 0.1D, rail.getZ() + 0.5D);
        cart.setDeltaMovement(heading.getStepX() * speed, 0.0D, heading.getStepZ() * speed);
        if (ridden) {
            helper.makeMockPlayer(GameType.SURVIVAL).startRiding(cart, true, true);
        }
        return cart;
    }

    private static BlockState rail(RailShape shape) {
        return Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape);
    }

    private static BlockState plainRail(Direction direction) {
        return rail(direction.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
    }

    private static BlockState poweredRail(Direction direction, boolean powered) {
        return Blocks.POWERED_RAIL.defaultBlockState()
                .setValue(PoweredRailBlock.SHAPE,
                        direction.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
                .setValue(PoweredRailBlock.POWERED, powered);
    }

    private static boolean boosted(Minecart cart) {
        return ((FastRailCart) cart).sandstoneFastRails$isBoosted();
    }

    private static double speed(Minecart cart) {
        return ((FastRailCart) cart).sandstoneFastRails$getRailSpeed();
    }

    private static void requireOnRail(ServerLevel level, Minecart cart, String context) {
        require(SandstoneRailUtil.isRailAt(level, cart.getCurrentBlockPosOrRailBelow()),
                "Cart derailed (" + context + ") at " + cart.position());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
