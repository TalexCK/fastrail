package com.example.sandstone_fast_rails;

/** Per-cart context shared with the vanilla friction hook. */
public interface RailStepState {
    boolean sandstoneFastRails$isExtraRailStep();
    boolean sandstoneFastRails$hasReducedCurveFriction();
}
