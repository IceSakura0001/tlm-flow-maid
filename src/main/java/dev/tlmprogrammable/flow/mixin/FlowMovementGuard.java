package dev.tlmprogrammable.flow.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.server.FlowRuntime;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The selected custom flow mode owns movement, including its completed idle state; native work modes keep TLM's normal behavior. */
@Mixin(value = {MaidFollowOwnerTask.class, MaidFollowOwnerVehicleTask.class, MaidPickupEntitiesTask.class}, remap = false)
public abstract class FlowMovementGuard {
    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void flowOwnsMovement(ServerLevel level, EntityMaid maid, CallbackInfoReturnable<Boolean> callback) {
        if (FlowRuntime.ownsMovement(maid)) callback.setReturnValue(false);
    }
}
