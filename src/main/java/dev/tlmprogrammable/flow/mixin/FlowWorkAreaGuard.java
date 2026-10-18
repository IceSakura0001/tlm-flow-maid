package dev.tlmprogrammable.flow.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.server.FlowRuntime;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Explicit flow targets replace the ordinary owner/home radius; collision/path rules stay native. */
@Mixin(value = EntityMaid.class, remap = false)
public abstract class FlowWorkAreaGuard {
    @Inject(method = {"isWithinRestriction(Lnet/minecraft/core/BlockPos;)Z", "m_21444_(Lnet/minecraft/core/BlockPos;)Z"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void useFlowArea(BlockPos position, CallbackInfoReturnable<Boolean> callback) {
        if (FlowRuntime.ownsMovement((EntityMaid) (Object) this)) callback.setReturnValue(true);
    }
}
