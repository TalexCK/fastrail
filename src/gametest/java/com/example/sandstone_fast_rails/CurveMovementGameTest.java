package com.example.sandstone_fast_rails;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

public class CurveMovementGameTest {
    private static final RailShape[] CURVES = {
            RailShape.NORTH_EAST, RailShape.NORTH_WEST,
            RailShape.SOUTH_EAST, RailShape.SOUTH_WEST
    };

    @GameTest
    public void sandstoneJunctionMatchesVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 2, 4));
        int cases = 0;
        for (RailShape curve : CURVES) {
            for (Direction heading : Direction.Plane.HORIZONTAL) {
                for (double speed : new double[]{0.12D, 0.4D}) {
                    for (boolean wholeTrack : new boolean[]{false, true}) {
                        // Run on the SAME coordinates, changing only the support
                        // material. This avoids rounding/chunk/location differences.
                        buildJunction(level, origin, curve, false, false);
                        Vec3[][] vanilla = trace(level, origin, heading, speed);
                        buildJunction(level, origin, curve, true, wholeTrack);
                        Vec3[][] sandstone = trace(level, origin, heading, speed);
                        String context = curve + "/" + heading + "/" + speed + "/" + wholeTrack;
                        require(exitSide(origin, vanilla) == exitSide(origin, sandstone),
                                "Junction route changed: " + context);
                        require(sandstone.length <= vanilla.length, "Sandstone curve is slower: " + context);

                        // An unboosted cobblestone cart must still match vanilla
                        // at every tick, including velocity and junction routing.
                        buildJunction(level, origin, curve, false, false);
                        replaceSupport(level, origin, Blocks.COBBLESTONE);
                        Vec3[][] cobble = trace(level, origin, heading, speed);
                        require(vanilla.length == cobble.length, "Cobble timing changed: " + context);
                        for (int tick = 0; tick < vanilla.length; tick++) {
                            require(vanilla[tick][0].distanceToSqr(cobble[tick][0]) < 1.0E-16D,
                                    "Cobble position changed: " + context);
                            require(vanilla[tick][1].distanceToSqr(cobble[tick][1]) < 1.0E-16D,
                                    "Cobble friction changed: " + context);
                        }
                        cases++;
                    }
                }
            }
        }

        checkFrictionAndTransitions(helper, origin);

        // A long straight sandstone track must still get the extra movement.
        buildJunction(level, origin, RailShape.EAST_WEST, false, false);
        Minecart vanilla = cart(level, origin, Direction.EAST, 0.4D);
        vanilla.tick();
        buildJunction(level, origin, RailShape.EAST_WEST, true, true);
        Minecart fast = cart(level, origin, Direction.EAST, 0.4D);
        fast.tick();
        require(fast.getX() > vanilla.getX() + 0.5D, "Straight sandstone acceleration was lost");
        SandstoneFastRailsMod.LOGGER.info("Passed {} junction routes/cobble comparisons, curve friction, material transitions and straight acceleration", cases);
        helper.succeed();
    }

    private static Vec3[][] trace(ServerLevel level, BlockPos origin, Direction heading, double speed) {
        Minecart cart = cart(level, origin, heading, speed);
        java.util.List<Vec3[]> samples = new java.util.ArrayList<>();
        boolean reachedCurve = false;
        Vec3 center = new Vec3(origin.getX() + 0.5D, origin.getY() + 0.1D, origin.getZ() + 0.5D);
        for (int tick = 0; tick < 40; tick++) {
            cart.tick();
            samples.add(new Vec3[]{cart.position(), cart.getDeltaMovement()});
            double distance = Math.max(Math.abs(cart.getX() - center.x), Math.abs(cart.getZ() - center.z));
            require(SandstoneRailUtil.findRailAtCart(level, cart.blockPosition()) != null,
                    "Cart derailed at " + cart.position() + " heading " + heading);
            reachedCurve |= (cart.getX() - center.x) * heading.getStepX()
                    + (cart.getZ() - center.z) * heading.getStepZ() > -0.7D;
            if (reachedCurve && distance > 1.75D) {
                break;
            }
        }
        require(reachedCurve, "Test cart never reached the junction");
        return samples.toArray(Vec3[][]::new);
    }

    private static Minecart cart(ServerLevel level, BlockPos origin, Direction heading, double speed) {
        Minecart cart = new Minecart(EntityTypes.MINECART, level);
        cart.setPos(origin.getX() + 0.5D - heading.getStepX() * 1.25D,
                origin.getY() + 0.1D,
                origin.getZ() + 0.5D - heading.getStepZ() * 1.25D);
        cart.setDeltaMovement(heading.getStepX() * speed, 0.0D, heading.getStepZ() * speed);
        return cart;
    }

    private static void buildJunction(ServerLevel level, BlockPos origin, RailShape curve,
                                      boolean sandstone, boolean wholeTrack) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos pos = origin.offset(x, 0, z);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), flags);
                level.setBlock(pos.below(),
                        sandstone && (wholeTrack || (x == 0 && z == 0))
                                ? Blocks.SANDSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState(), flags);
            }
        }
        for (int i = -4; i <= 4; i++) {
            level.setBlock(origin.offset(i, 0, 0),
                    Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST), flags);
            level.setBlock(origin.offset(0, 0, i),
                    Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH), flags);
        }
        // Set the switch last, without neighbor shape updates.
        level.setBlock(origin, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, curve), flags);
    }

    private static Direction exitSide(BlockPos origin, Vec3[][] samples) {
        Vec3 position = samples[samples.length - 1][0];
        return SandstoneRailUtil.directionFromVelocity(position.subtract(
                new Vec3(origin.getX() + 0.5D, position.y, origin.getZ() + 0.5D)));
    }

    private static void replaceSupport(ServerLevel level, BlockPos origin, Block block) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                level.setBlock(origin.offset(x, -1, z), block.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    private static void checkFrictionAndTransitions(GameTestHelper helper, BlockPos origin) {
        ServerLevel level = helper.getLevel();
        for (boolean occupied : new boolean[]{false, true}) {
            for (RailShape curve : CURVES) {
                for (Direction heading : Direction.Plane.HORIZONTAL) {
                    // First tick is a fast sandstone approach outside the curve
                    // guard. Switch to cobble ahead, before the cart reaches it.
                    buildJunction(level, origin, curve, true, true);
                    Minecart boosted = cart(level, origin, heading, 0.4D);
                    boosted.setPos(boosted.getX() - heading.getStepX() * 2.0D,
                            boosted.getY(), boosted.getZ() - heading.getStepZ() * 2.0D);
                    if (occupied) {
                        helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
                                .startRiding(boosted, true, true);
                    }
                    boosted.tick();
                    replaceSupport(level, origin, Blocks.COBBLESTONE);
                    double before = SandstoneRailUtil.horizontalSpeed(boosted.getDeltaMovement());
                    double retention = boosted.getBehavior().getSlowdownFactor();
                    Vec3 start = boosted.position();
                    boosted.tick();
                    double after = SandstoneRailUtil.horizontalSpeed(boosted.getDeltaMovement());
                    require(Math.abs(after - before * retention) < 1.0E-7D,
                            "Cobble must apply vanilla friction exactly once: " + curve + "/" + heading
                                    + "/occupied=" + occupied + ", before=" + before + ", after=" + after);
                    require(boosted.position().distanceTo(start) > 0.5D,
                            "Sandstone-to-cobble transition abruptly lost the extra movement");
                    require(SandstoneRailUtil.findRailAtCart(level, boosted.blockPosition()) != null,
                            "Sandstone-to-cobble transition derailed");
                    boosted.ejectPassengers();
                }

                // Test low-speed drift on a curved rail, both empty and ridden.
                buildJunction(level, origin, curve, true, true);
                Minecart drift = cart(level, origin, Direction.EAST, 0.1D);
                var exits = net.minecraft.world.entity.vehicle.minecart.AbstractMinecart.exits(curve);
                double dx = exits.getSecond().getX() - exits.getFirst().getX();
                double dz = exits.getSecond().getZ() - exits.getFirst().getZ();
                double length = Math.sqrt(dx * dx + dz * dz);
                drift.setPos(origin.getX() + 0.5D, origin.getY() + 0.1D, origin.getZ() + 0.5D);
                drift.setDeltaMovement(dx / length * 0.1D, 0.0D, dz / length * 0.1D);
                if (occupied) {
                    helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
                            .startRiding(drift, true, true);
                }
                drift.tick();
                double retained = SandstoneRailUtil.horizontalSpeed(drift.getDeltaMovement());
                require(Math.abs(retained - 0.0999D) < 1.0E-7D,
                        "Sandstone curve lost too much speed: " + curve + "/occupied=" + occupied + "/" + retained);
                require(SandstoneRailUtil.findRailAtCart(level, drift.blockPosition()) != null,
                        "Sandstone curve derailed");
                drift.ejectPassengers();
            }
        }
    }

    @GameTest
    public void ordinaryStraightTrackCoastsToRest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Elevated isolated track; long enough for vanilla ridden-cart friction.
        BlockPos origin = helper.absolutePos(new BlockPos(4, 14, 4));
        int cases = 0;
        for (Block support : new Block[]{Blocks.COBBLESTONE, Blocks.STONE, Blocks.OAK_PLANKS}) {
            for (boolean occupied : new boolean[]{false, true}) {
                int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                for (int x = -8; x <= 768; x++) {
                    BlockPos rail = origin.offset(x, 0, 0);
                    level.setBlock(rail.above(), Blocks.AIR.defaultBlockState(), flags);
                    level.setBlock(rail.below(),
                            (x < 0 ? Blocks.SANDSTONE : support).defaultBlockState(), flags);
                    level.setBlock(rail, Blocks.RAIL.defaultBlockState()
                            .setValue(RailBlock.SHAPE, RailShape.EAST_WEST), flags);
                }
                Minecart cart = cart(level, origin, Direction.EAST, 0.4D);
                cart.setPos(origin.getX() - 6.25D, origin.getY() + 0.1D, origin.getZ() + 0.5D);
                if (occupied) {
                    helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
                            .startRiding(cart, true, true);
                }
                int approachTicks = 0;
                while (cart.getX() < origin.getX() && approachTicks++ < 30) {
                    cart.tick();
                }
                require(cart.getX() >= origin.getX(), "Never reached ordinary support");
                double entryX = cart.getX();
                double retention = cart.getBehavior().getSlowdownFactor();
                double priorDistance = Double.POSITIVE_INFINITY;
                int coastTicks = 0;
                boolean stopped = false;
                for (; coastTicks < 4000; coastTicks++) {
                    double beforeSpeed = SandstoneRailUtil.horizontalSpeed(cart.getDeltaMovement());
                    double beforeX = cart.getX();
                    cart.tick();
                    double afterSpeed = SandstoneRailUtil.horizontalSpeed(cart.getDeltaMovement());
                    double distance = cart.getX() - beforeX;
                    require(distance >= -1.0E-8D, "Coasting cart reversed");
                    require(distance <= priorDistance + 1.0E-7D, "Ordinary track accelerated the cart");
                    require(SandstoneRailUtil.findRailAtCart(level, cart.blockPosition()) != null,
                            "Coasting cart derailed");
                    if (coastTicks == 0) {
                        require(distance > 0.5D, "Abrupt speed loss when entering ordinary straight rail");
                    }
                    priorDistance = distance;
                    if (afterSpeed == 0.0D) {
                        double cutoff = SandstoneRailUtil.MIN_MOVEMENT_SPEED
                                / SandstoneRailUtil.FAST_RAIL_VISUAL_MULTIPLIER;
                        require(beforeSpeed * retention <= cutoff + 1.0E-7D, "Stopped too abruptly");
                        stopped = true;
                        break;
                    }
                    require(Math.abs(afterSpeed - beforeSpeed * retention) < 1.0E-7D,
                            "Ordinary rail friction is not vanilla: " + support + "/occupied=" + occupied
                                    + "/tick=" + coastTicks + "/before=" + beforeSpeed + "/after=" + afterSpeed);
                }
                require(stopped, "Cart kept creeping instead of stopping");
                require(coastTicks > 40 && cart.getX() - entryX > 5.0D,
                        "Cart did not coast gradually");
                Vec3 rest = cart.position();
                for (int tick = 0; tick < 40; tick++) {
                    cart.tick();
                    require(SandstoneRailUtil.horizontalSpeed(cart.getDeltaMovement()) == 0.0D,
                            "Stopped cart restarted itself");
                    require(cart.position().distanceToSqr(rest) < 1.0E-16D, "Stopped cart drifted");
                }
                cart.ejectPassengers();
                SandstoneFastRailsMod.LOGGER.info(
                        "Coasting passed: support={}, occupied={}, ticks={}, distance={}",
                        support, occupied, coastTicks, cart.getX() - entryX);
                cases++;
            }
        }
        SandstoneFastRailsMod.LOGGER.info("Passed {} full ordinary-track coasting-to-rest cases", cases);
        helper.succeed();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
