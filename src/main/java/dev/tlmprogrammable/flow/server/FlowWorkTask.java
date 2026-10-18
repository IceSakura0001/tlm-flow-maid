package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;

public class FlowWorkTask implements IMaidTask {
    public static final ResourceLocation UID = new ResourceLocation("tlm_programmable", "flow_work");
    @Override public ResourceLocation getUid() { return UID; }
    @Override public ItemStack getIcon() { return new ItemStack(Items.WRITABLE_BOOK); }
    @Override public MutableComponent getName() { return Component.literal("自定义流程"); }
    @Override public SoundEvent getAmbientSound(EntityMaid maid) { return null; }
    @Override public boolean isHidden(EntityMaid maid) { return true; }
    @Override public boolean enableLookAndRandomWalk(EntityMaid maid) { return false; }
    @Override public boolean enableEating(EntityMaid maid) { return false; }
    @Override public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return new ArrayList<>(List.of(Pair.of(1, new Runner())));
    }
    private static class Runner extends Behavior<EntityMaid> {
        Runner() { super(Map.of(), 72000); }
        @Override protected void start(ServerLevel level, EntityMaid maid, long time) { advance(maid); }
        @Override protected void tick(ServerLevel level, EntityMaid maid, long time) { advance(maid); }
        private void advance(EntityMaid maid) {
            maid.getBrain().setMemory(InitEntities.TARGET_POS.get(), new BlockPosTracker(maid.blockPosition()));
            maid.getBrain().eraseMemory(InitEntities.MAID_EDIBLE_BLOCK_ACTION.get());
            FlowRuntime.tick(maid);
        }
        @Override protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time) { return UID.equals(maid.getTask().getUid()); }
        @Override protected void stop(ServerLevel level, EntityMaid maid, long time) {
            maid.getBrain().eraseMemory(InitEntities.TARGET_POS.get());
            if (!UID.equals(maid.getTask().getUid()) && !FlowRuntime.managesNative(maid)) FlowRuntime.cancel(maid);
        }
    }
}
