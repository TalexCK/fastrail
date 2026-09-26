package com.example.sandstone_fast_rails.mixin;

import com.example.sandstone_fast_rails.RailStepState;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractMinecart.class)
public abstract class MinecartFrictionMixin {
    @Inject(method = "applyNaturalSlowdown", at = @At("HEAD"), cancellable = true)
    private void sandstoneFastRails$applyFrictionOnce(Vec3 velocity, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof RailStepState state) {
            if (state.sandstoneFastRails$isExtraRailStep()) {
                cir.setReturnValue(velocity.multiply(1.0D, 0.0D, 1.0D));
            } else if (state.sandstoneFastRails$hasReducedCurveFriction()) {
                // 0.1% loss per tick near sandstone curves. Other supports retain
                // vanilla's passenger-dependent friction.
                cir.setReturnValue(velocity.multiply(0.999D, 0.0D, 0.999D));
            }
        }
    }
}
