package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.FlowRunner;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import java.util.*;

/** A single exact proposal. Consent is in-memory, owner-bound, expiring and never reused by another plan. */
public final class RoadRun {
    private static final Map<UUID, RoadRun> REQUESTS = new HashMap<>();
    private final EntityMaid maid; private final ServerPlayer owner; private final RoadPlan planner;
    private List<RoadPlan.Step> steps; private UUID ticket; private long deadline, nextAction, movementSince;
    private boolean approved, denied, stopped; private int index, editIndex, breakingTicks;
    private BlockPos cracking;
    private final Set<BlockPos> completed = new HashSet<>();
    public RoadRun(EntityMaid maid, ServerPlayer owner, BlockPos goal) { this.maid = maid; this.owner = owner; planner = new RoadPlan(maid, owner, goal); }
    public static void clear() { for (RoadRun run : new ArrayList<>(REQUESTS.values())) run.cancel(); REQUESTS.clear(); }
    public static void answer(ServerPlayer owner, EntityMaid maid, UUID ticket, boolean allow) {
        RoadRun run = REQUESTS.get(ticket);
        if (run == null || run.maid != maid || !run.owner.getUUID().equals(owner.getUUID()) || owner.level() != maid.level()
                || maid.level().getGameTime() > run.deadline || !FlowLibrary.data(maid).getBoolean("armed"))
            throw new IllegalArgumentException("该规划已过期或已取消，许可无效");
        REQUESTS.remove(ticket); run.approved = allow; run.denied = !allow;
    }
    public UUID requestId() { return ticket; }
    private void holdPosition() {
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.PATH);
        maid.setXxa(0); maid.setZza(0); maid.setSpeed(0);
        maid.getMoveControl().setWantedPosition(maid.getX(), maid.getY(), maid.getZ(), 0);
        maid.setDeltaMovement(0, maid.getDeltaMovement().y, 0);
    }
    public void cancel() {
        if (ticket != null) REQUESTS.remove(ticket);
        if (cracking != null) maid.level().destroyBlockProgress(maid.getId(), cracking, -1);
        holdPosition(); stopped = true;
    }
    private FlowRunner.WorkResult fail(String reason) { cancel(); return FlowRunner.WorkResult.failed("道路实验停止：" + reason); }
    public FlowRunner.WorkResult tick() {
        long now = maid.level().getGameTime();
        if (stopped) return FlowRunner.WorkResult.failed("道路规划已取消");
        if (!maid.isAlive() || !owner.isAlive() || owner.level() != maid.level()) return fail("女仆或主人状态/维度已变化");
        if (steps == null) {
            holdPosition();
            if (!planner.tick()) return FlowRunner.WorkResult.running();
            steps = planner.result(); if (steps.isEmpty()) return fail("16格局部范围内没有安全方案（最多24步）：" + planner.describe());
            List<RoadPlan.Edit> edits = steps.stream().flatMap(step -> step.edits().stream()).distinct().toList();
            long blocks = edits.stream().filter(edit -> edit.kind() == RoadPlan.Kind.BREAK).count();
            if (blocks > 0) {
                ticket = UUID.randomUUID(); deadline = now + 2400; REQUESTS.put(ticket, this);
                CompoundTag offer = new CompoundTag(); offer.putUUID("ticket", ticket); offer.putInt("count", (int) blocks);
                ListTag list = new ListTag();
                for (var edit : edits) if (edit.kind() == RoadPlan.Kind.BREAK) {
                    CompoundTag item = new CompoundTag(); item.putLong("pos", edit.pos().asLong());
                    item.putString("block", net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(edit.expected().getBlock()).toString()); list.add(item);
                }
                offer.put("blocks", list); offer.putInt("placeCount", (int) edits.stream().filter(edit -> edit.kind() == RoadPlan.Kind.PLACE).count());
                offer.putString("dimension", maid.level().dimension().location().toString());
                FlowNetwork.push(owner, new FlowNetwork.Reply(maid.getId(), maid.getUUID(), "road_offer", true, "女仆申请本次规划拆障", offer));
            } else approved = true;
            return FlowRunner.WorkResult.running();
        }
        if (denied) return fail("主人拒绝了本次拆障");
        if (ticket != null && now > deadline) return fail("本次规划/许可已超过2分钟，请重新运行以重新规划");
        if (!approved) { holdPosition(); return FlowRunner.WorkResult.running(); }
        for (RoadPlan.Step planned : steps) for (RoadPlan.Edit edit : planned.edits()) {
            if (completed.contains(edit.pos())) continue;
            if (!maid.level().hasChunkAt(edit.pos()) || !maid.level().getBlockState(edit.pos()).equals(edit.expected())) return fail("待执行规划已变化，必须重新申请");
            if (edit.kind() == RoadPlan.Kind.BREAK && !RoadPlan.breakAllowed(maid, owner, edit.pos())) return fail("新的禁拆或工具规则阻止了本次规划");
        }
        if (now < nextAction) return FlowRunner.WorkResult.running();
        if (index >= steps.size()) { cancel(); return FlowRunner.WorkResult.done(); }
        RoadPlan.Step step = steps.get(index);
        if (editIndex < step.edits().size()) {
            holdPosition();
            RoadPlan.Edit edit = step.edits().get(editIndex);
            if (completed.contains(edit.pos())) { editIndex++; return FlowRunner.WorkResult.running(); }
            if (!maid.level().hasChunkAt(edit.pos()) || !maid.level().getBlockState(edit.pos()).equals(edit.expected())) return fail("规划中的方块已变化，不沿用旧许可");
            if (!maid.level().mayInteract(owner, edit.pos()) || !maid.level().getFluidState(edit.pos()).isEmpty()) return fail("区域权限或液体状态不允许作业");
            Vec3 target = Vec3.atCenterOf(edit.pos());
            if (maid.getEyePosition().distanceToSqr(target) > 16) return fail("待操作方块超过触及范围");
            var hit = maid.level().clip(new ClipContext(maid.getEyePosition(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid));
            if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(edit.pos())) return fail("待操作方块被其他方块遮挡");
            if (!net.minecraftforge.event.ForgeEventFactory.getMobGriefingEvent(maid.level(), maid)) return fail("当前规则不允许女仆改动地形");
            if (edit.kind() == RoadPlan.Kind.BREAK) {
                if (!RoadPlan.breakAllowed(maid, owner, edit.pos())) return fail("禁拆规则、工具或保护权限已变化");
                cracking = edit.pos(); int needed = Math.max(5, (int) Math.ceil(edit.expected().getDestroySpeed(maid.level(), edit.pos()) * 30 / Math.max(1, maid.getMainHandItem().getDestroySpeed(edit.expected()))));
                if (++breakingTicks % 5 == 1) maid.swing(InteractionHand.MAIN_HAND);
                maid.getLookControl().setLookAt(target.x, target.y, target.z);
                maid.level().destroyBlockProgress(maid.getId(), edit.pos(), Math.min(9, breakingTicks * 10 / needed));
                if (breakingTicks < needed) return FlowRunner.WorkResult.running();
                if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(maid.level(), edit.pos(), edit.expected(), owner))) return fail("保护模组取消了破坏");
                var tool = maid.getMainHandItem();
                if (!maid.destroyBlock(maid.level(), edit.pos(), false, maid)) return fail("破坏失败");
                maid.dropResourcesToMaidInv(edit.expected(), maid.level(), edit.pos(), null, maid, tool);
                if (tool.isDamageableItem()) tool.hurtAndBreak(1, maid, entity -> entity.broadcastBreakEvent(InteractionHand.MAIN_HAND));
                maid.level().destroyBlockProgress(maid.getId(), edit.pos(), -1); cracking = null; breakingTicks = 0;
            } else {
                if (!edit.expected().isAir()) return fail("只允许在空气处搭圆石，不覆盖既有方块");
                var inventory = maid.getAvailableInv(true); int slot = -1;
                for (int i = 0; i < inventory.getSlots(); i++) if (inventory.getStackInSlot(i).is(net.minecraft.world.item.Items.COBBLESTONE)) { slot = i; break; }
                if (slot < 0) return fail("女仆缺少圆石，请补充后重新运行");
                var snapshot = BlockSnapshot.create(maid.level().dimension(), maid.level(), edit.pos());
                var material = inventory.extractItem(slot, 1, false); if (material.isEmpty()) return fail("无法取出圆石");
                if (!maid.level().setBlockAndUpdate(edit.pos(), Blocks.COBBLESTONE.defaultBlockState())) {
                    var remainder = ItemHandlerHelper.insertItemStacked(inventory, material, false); if (!remainder.isEmpty()) maid.spawnAtLocation(remainder); return fail("圆石放置失败");
                }
                // Forge placement events observe the proposed block, then cancellation restores the original snapshot.
                var placement = new BlockEvent.EntityPlaceEvent(snapshot, maid.level().getBlockState(edit.pos().below()), maid);
                if (MinecraftForge.EVENT_BUS.post(placement)) {
                    snapshot.restore(true, false);
                    var remainder = ItemHandlerHelper.insertItemStacked(inventory, material, false); if (!remainder.isEmpty()) maid.spawnAtLocation(remainder);
                    return fail("保护模组取消了放置，已恢复方块和圆石");
                }
                maid.swing(InteractionHand.MAIN_HAND);
            }
            completed.add(edit.pos()); editIndex++; nextAction = now + 5; return FlowRunner.WorkResult.running();
        }
        Vec3 standing = Vec3.atBottomCenterOf(step.feet());
        double dx = maid.getX() - standing.x, dz = maid.getZ() - standing.z;
        // A jump passing close to a step is not arrival. Wait for a stable landing at
        // its centre before stopping navigation or allowing the next operation.
        if (maid.onGround() && Math.abs(maid.getY() - standing.y) < .15 && dx * dx + dz * dz < .025) {
            holdPosition(); index++; editIndex = 0; movementSince = 0; return FlowRunner.WorkResult.running();
        }
        if (movementSince == 0) movementSince = now;
        if (now - movementSince > 120) return fail("施工后无法登上第" + (index + 1) + "步：" + step.feet().toShortString() + "；女仆当前位置：" + maid.blockPosition().toShortString() + "；规划：" + planner.describe());
        // Native path following may shortcut a corner across the void. The approved
        // construction path already consists of adjacent cardinal steps: follow those
        // exact centres through normal movement/jump controls, never teleportation.
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.PATH);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (!maid.onGround() && horizontal < .18 && maid.getY() >= standing.y) {
            Vec3 velocity = maid.getDeltaMovement(); maid.setDeltaMovement(velocity.x * .35, velocity.y, velocity.z * .35);
            maid.getMoveControl().setWantedPosition(maid.getX(), maid.getY(), maid.getZ(), 0);
        } else {
            maid.getMoveControl().setWantedPosition(standing.x, standing.y, standing.z, horizontal < .45 ? .3 : .55);
            double rise = standing.y - maid.getY();
            if (maid.onGround() && rise > .4 && rise <= 1.15 && horizontal < 1.8) {
                // TLM's jumping move-control keeps its launch heading. Turn before
                // launching, rather than rotating through a corner during the jump.
                float yaw = (float) (Math.atan2(standing.z - maid.getZ(), standing.x - maid.getX()) * 180 / Math.PI - 90);
                maid.setYRot(yaw); maid.yBodyRot = yaw;
                Vec3 velocity = maid.getDeltaMovement(); maid.setDeltaMovement(0, velocity.y, 0);
                maid.getJumpControl().jump();
            }
        }
        return FlowRunner.WorkResult.running();
    }
}
