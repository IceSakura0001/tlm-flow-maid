package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Final physics-stage guard: navigation cannot accelerate past a narrow ledge in the same tick. */
public final class FlowMovementSafety {
    private FlowMovementSafety() { }
    public static Vec3 limit(EntityMaid maid, MoverType type, Vec3 movement) {
        if (type != MoverType.SELF || maid.level().isClientSide || (!FlowRuntime.ownsMovement(maid) && !FlowRuntime.managesNative(maid))
                || !FlowLibrary.data(maid).getBoolean("armed") || maid.isPassenger() || maid.isInWaterOrBubble() || maid.isFallFlying()) return movement;
        double x = movement.x, z = movement.z;
        boolean edge = maid.onGround() && (!supported(maid, .48, 0) || !supported(maid, -.48, 0)
                || !supported(maid, 0, .48) || !supported(maid, 0, -.48));
        double cap = edge ? .08 : maid.onGround() ? .16 : .12;
        double speed = Math.sqrt(x * x + z * z);
        if (speed > cap) { x *= cap / speed; z *= cap / speed; }
        // Grounded only: permit actual jumps, gravity and one-block downward steps.
        // Trim horizontal motion before crossing a drop, like careful edge walking.
        if (maid.onGround()) {
            for (int i = 0; i < 12 && !supported(maid, x, z) && (Math.abs(x) > .0001 || Math.abs(z) > .0001); i++) {
                x = Math.abs(x) <= .01 ? 0 : x - Math.copySign(.01, x);
                z = Math.abs(z) <= .01 ? 0 : z - Math.copySign(.01, z);
            }
        }
        if (Math.abs(x - movement.x) > .00001 || Math.abs(z - movement.z) > .00001) {
            Vec3 velocity = maid.getDeltaMovement(); maid.setDeltaMovement(x, velocity.y, z);
        }
        return new Vec3(x, movement.y, z);
    }
    private static boolean supported(EntityMaid maid, double dx, double dz) {
        double x = maid.getX() + dx, z = maid.getZ() + dz, y = maid.getY();
        AABB foot = new AABB(x - .001, y - 1.1, z - .001, x + .001, y + .01, z + .001);
        return maid.level().getBlockCollisions(maid, foot).iterator().hasNext();
    }
}
