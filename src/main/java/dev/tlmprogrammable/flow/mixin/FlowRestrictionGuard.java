package dev.tlmprogrammable.flow.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidAwaitTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.server.FlowRuntime;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaidAwaitTask.class, remap = false)
public abstract class FlowRestrictionGuard {
    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;J)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void keepExplicitFlowDestination(ServerLevel level, EntityMaid maid, long time, CallbackInfo callback) {
        if (FlowRuntime.ownsMovement(maid)) callback.cancel();
    }
}
