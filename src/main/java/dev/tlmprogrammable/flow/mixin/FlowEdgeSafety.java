package dev.tlmprogrammable.flow.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.server.FlowMovementSafety;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Named and production SRG entry points; scoped to an armed flow maid's own movement. */
@Mixin(value = Entity.class, remap = false)
public abstract class FlowEdgeSafety {
    @ModifyVariable(method = {"move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", "m_6478_(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"},
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private Vec3 carefulFlowMovement(Vec3 movement, MoverType type, Vec3 original) {
        return (Object) this instanceof EntityMaid maid ? FlowMovementSafety.limit(maid, type, movement) : movement;
    }
}
