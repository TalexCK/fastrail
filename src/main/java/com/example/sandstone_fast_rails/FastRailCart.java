package com.example.sandstone_fast_rails;

/** Energy-model state of a minecart, implemented by the minecart mixin. */
public interface FastRailCart {
    /** True while the energy model (not vanilla) controls this cart's speed. */
    boolean sandstoneFastRails$isBoosted();

    /** Track speed in blocks per tick while boosted. */
    double sandstoneFastRails$getRailSpeed();
}
