package dev.tlmprogrammable.flow.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import dev.tlmprogrammable.flow.*;
import dev.tlmprogrammable.flow.server.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@GameTestHolder("tlm_programmable")
@PrefixGameTestTemplate(false)
public final class FlowGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void graphCodecAndResumePreserveTheNextNode(GameTestHelper helper) {
        FlowGraph graph = transferGraph(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        helper.assertTrue(graph.equals(FlowGraphNbt.read(FlowGraphNbt.write(graph))), "Graph NBT round trip");
        FlowRunner runner = new FlowRunner(graph); runner.start(graph);
        runner.tick(node -> FlowRunner.WorkResult.done());
        helper.assertTrue(runner.currentNode().equals("store"), "A completed TAKE must advance exactly once");
        FlowRunner restored = new FlowRunner(graph); restored.restore(runner.snapshot());
        List<String> seen = new ArrayList<>(); restored.tick(node -> { seen.add(node.id()); return FlowRunner.WorkResult.done(); });
        helper.assertTrue(seen.equals(List.of("store")), "Reload must resume STORE without taking again");
        restored.tick(node -> FlowRunner.WorkResult.done());
        helper.assertTrue(restored.state() == FlowRunner.State.SUCCEEDED, "Restored flow must terminate"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void unconditionalSpinIsBoundedAndInvalidCyclesAreRejected(GameTestHelper helper) {
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "loop", List.of(
                node("start", FlowGraph.Kind.START, Map.of()), node("a", FlowGraph.Kind.LOOP_START, Map.of()),
                node("b", FlowGraph.Kind.LOOP_END, Map.of()), node("end", FlowGraph.Kind.END, Map.of())), List.of(
                edge("start", FlowGraph.Port.NEXT, "a"), edge("a", FlowGraph.Port.YES, "b"), edge("a", FlowGraph.Port.NO, "end"),
                edge("b", FlowGraph.Port.YES, "a"), edge("b", FlowGraph.Port.NO, "end")));
        FlowRunner runner = new FlowRunner(graph); runner.start(graph); runner.tick(node -> node.kind() == FlowGraph.Kind.START
                ? FlowRunner.WorkResult.done() : FlowRunner.WorkResult.branch(true));
        helper.assertTrue(runner.state() == FlowRunner.State.RUNNING && runner.transitions() == FlowRunner.MAX_TRANSITIONS_PER_TICK,
                "A control cycle must yield after the tick budget");
        for (int i = 0; i < 500 && runner.state() == FlowRunner.State.RUNNING; i++) runner.tick(node -> FlowRunner.WorkResult.branch(true));
        helper.assertTrue(runner.state() == FlowRunner.State.FAILED, "A nonexiting runtime condition must hit a safety bound"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void libraryPersistsNamesChecksRevisionAndDeletesSelectedTask(GameTestHelper helper) {
        EntityMaid maid = new EntityMaid(helper.getLevel());
        FlowGraph first = FlowGraph.empty("每天收纳"), second = FlowGraph.empty("到主人面前");
        FlowLibrary.save(maid, first, 0); FlowLibrary.save(maid, second, 1);
        boolean rejected = false;
        try { FlowLibrary.save(maid, first, 0); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected && FlowLibrary.graphs(FlowLibrary.data(maid)).size() == 2, "Stale writes must preserve both tasks");
        CompoundTag saved = maid.saveWithoutId(new CompoundTag()); EntityMaid restored = new EntityMaid(helper.getLevel()); restored.load(saved);
        helper.assertTrue(FlowLibrary.find(restored, first.id()).name().equals("每天收纳"), "Task library must survive complete maid NBT reload");
        FlowRuntime.select(restored, first); FlowLibrary.delete(restored, first.id(), 2);
        helper.assertTrue(!FlowLibrary.data(restored).getBoolean("armed") && restored.getTask().getUid().equals(TaskManager.getIdleTask().getUid()),
                "Deleting the active task must stop it and select idle");
        helper.assertTrue(FlowLibrary.find(restored, second.id()).id().equals(second.id()), "Other task must remain");
        FlowRuntime.select(restored, second);
        var ordinary = TaskManager.getTaskIndex().stream().filter(task -> !task.getUid().equals(FlowWorkTask.UID)
                && !task.getUid().equals(TaskManager.getIdleTask().getUid())).findFirst().orElseThrow();
        restored.setTask(ordinary); FlowLibrary.delete(restored, second.id(), 3);
        helper.assertTrue(restored.getTask().getUid().equals(ordinary.getUid()), "Deleting an inactive flow must preserve the later ordinary task");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 70)
    public static void selectedTaskWaitsForMenuCloseThenTransfersAndSurvivesReload(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper);
        EntityMaid[] maid = {new EntityMaid(helper.getLevel())}; AtomicBoolean done = new AtomicBoolean(), reloaded = new AtomicBoolean();
        BlockPos from = helper.absolutePos(new BlockPos(0, 1, 1)), to = helper.absolutePos(new BlockPos(2, 1, 1));
        try {
            helper.getLevel().setBlockAndUpdate(from, Blocks.CHEST.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(to, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) helper.getLevel().getBlockEntity(from)).setItem(0, new ItemStack(Items.STONE, 64));
            maid[0].setTame(true); maid[0].setOwnerUUID(owner.player.getUUID()); maid[0].setNoAi(true); maid[0].setNoGravity(true);
            BlockPos middle = helper.absolutePos(new BlockPos(1, 1, 1)); maid[0].setPos(middle.getX() + .5, middle.getY(), middle.getZ() + .5);
            owner.player.setPos(maid[0].getX(), maid[0].getY(), maid[0].getZ());
            FlowGraph graph = transferGraph(from, to); FlowLibrary.save(maid[0], graph, 0);
            maid[0].guiOpening = true; FlowRuntime.select(maid[0], graph); FlowRuntime.tick(maid[0]);
            helper.assertTrue(((ChestBlockEntity) helper.getLevel().getBlockEntity(from)).getItem(0).getCount() == 64,
                    "Selecting while the menu is open must not execute TAKE");
        } catch (RuntimeException | Error exception) { owner.close(); throw exception; }
        helper.runAfterDelay(3, () -> maid[0].guiOpening = false);
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                FlowRuntime.tick(maid[0]);
                CompoundTag data = FlowLibrary.data(maid[0]);
                if (!reloaded.get() && data.getCompound("execution").getCompound("runner").getString("current").equals("store")) {
                    CompoundTag saved = maid[0].saveWithoutId(new CompoundTag()); FlowRuntime.drop(maid[0]);
                    EntityMaid next = new EntityMaid(helper.getLevel()); next.load(saved); next.setNoAi(true); next.setNoGravity(true);
                    maid[0] = next; reloaded.set(true);
                }
                if (!data.getBoolean("armed")) {
                    helper.assertTrue(data.getString("status").equals("SUCCEEDED"), "Transfer must complete successfully: " + data.getString("status"));
                    helper.assertTrue(reloaded.get(), "Test must reload between TAKE and STORE");
                    helper.assertTrue(((ChestBlockEntity) helper.getLevel().getBlockEntity(from)).getItem(0).isEmpty(), "Source must be empty");
                    helper.assertTrue(((ChestBlockEntity) helper.getLevel().getBlockEntity(to)).getItem(0).getCount() == 64, "Destination must contain exactly 64 items");
                    done.set(true); owner.close(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); owner.close(); throw exception; }
        });
        helper.runAfterDelay(60, () -> { if (!done.get()) { done.set(true); owner.close(); helper.fail("Transfer did not finish"); } });
    }
    private static FlowGraph transferGraph(BlockPos from, BlockPos to) {
        return new FlowGraph(UUID.randomUUID(), "取出再存入", List.of(node("start", FlowGraph.Kind.START, Map.of()),
                node("take", FlowGraph.Kind.TAKE, Map.of("target", target(from), "filter", "minecraft:stone", "itemCount", "64")),
                node("store", FlowGraph.Kind.STORE, Map.of("target", target(to), "filter", "minecraft:stone")), node("end", FlowGraph.Kind.END, Map.of())),
                List.of(edge("start", FlowGraph.Port.NEXT, "take"), edge("take", FlowGraph.Port.NEXT, "store"), edge("store", FlowGraph.Port.NEXT, "end")));
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realMaidBrainStartsSelectedTaskAfterClosingMenu(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper);
        EntityMaid maid = new EntityMaid(helper.getLevel()); AtomicBoolean finished = new AtomicBoolean();
        try {
            BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
            for (int x = -1; x <= 3; x++) for (int z = -1; z <= 6; z++)
                helper.getLevel().setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            owner.player.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5); owner.player.setYRot(0); owner.player.setXRot(0);
            maid.setTame(true); maid.setOwnerUUID(owner.player.getUUID()); maid.setInSittingPose(false); maid.setNoGravity(true);
            maid.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + 2.5);
            helper.getLevel().addFreshEntity(maid);
            FlowGraph graph = new FlowGraph(UUID.randomUUID(), "主人面前", List.of(node("start", FlowGraph.Kind.START, Map.of()),
                    node("move", FlowGraph.Kind.MOVE, Map.of("targetMode", "主人所在位置")), node("end", FlowGraph.Kind.END, Map.of())),
                    List.of(edge("start", FlowGraph.Port.NEXT, "move"), edge("move", FlowGraph.Port.NEXT, "end")));
            FlowLibrary.save(maid, graph, 0); maid.guiOpening = true; FlowRuntime.select(maid, graph);
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(!FlowLibrary.data(maid).contains("execution"), "Real AI must remain dormant while its menu is open");
                maid.guiOpening = false;
            });
        } catch (RuntimeException | Error exception) { maid.discard(); owner.close(); throw exception; }
        helper.onEachTick(() -> {
            if (finished.get()) return;
            if (FlowLibrary.data(maid).getString("status").equals("SUCCEEDED")) {
                finished.set(true); maid.discard(); owner.close(); helper.succeed();
            }
        });
        helper.runAfterDelay(80, () -> {
            if (!finished.get()) {
                String status = FlowLibrary.data(maid).getString("status");
                finished.set(true); maid.discard(); owner.close(); helper.fail("Real maid brain did not run after menu close: " + status);
            }
        });
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void finishRulesConvertUnitsAndRejectInvalidLimits(GameTestHelper helper) {
        FlowGraph.Node action = node("work", FlowGraph.Kind.ATTACK, Map.of("finishMode", "duration", "finishDuration", "1.5"));
        helper.assertTrue(FlowFinishRule.durationTicks(action) == 30, "Seconds support decimal values");
        helper.assertTrue(FlowFinishRule.durationTicks(action.configure("finishUnit", "minutes")) == 1800, "Minute conversion");
        helper.assertTrue(FlowFinishRule.durationTicks(action.configure("finishUnit", "days")) == 36000, "Game day conversion");
        for (String invalid : List.of("0", "-1", "NaN", "Infinity", "999999999", "1e99999999")) {
            boolean rejected = false;
            try { FlowFinishRule.durationTicks(action.configure("finishDuration", invalid)); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Invalid time must be rejected: " + invalid);
        }
        boolean rejected = false;
        try { FlowFinishRule.requiredCount(action.configure("finishCount", "1.5")); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Fractional target counts must be rejected"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void patrolDurationPausesInGuiAndSurvivesReload(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid[] maid = {testMaid(helper, owner)};
        AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0}; long[] paused = {0};
        FlowGraph graph = workGraph(FlowGraph.Kind.PATROL, Map.of("filter", "minecraft:ender_dragon", "finishMode", "duration", "finishDuration", "1"));
        FlowLibrary.save(maid[0], graph, 0); FlowRuntime.select(maid[0], graph);
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0];
                if (tick == 4) { paused[0] = worker(maid[0]).getLong("workedTicks"); maid[0].guiOpening = true; }
                FlowRuntime.tick(maid[0]);
                if (tick >= 4 && tick < 9) helper.assertTrue(worker(maid[0]).getLong("workedTicks") == paused[0], "Open menu must pause work time");
                if (tick == 9) maid[0].guiOpening = false;
                if (tick == 12) maid[0] = reload(helper, maid[0]);
                if (!FlowLibrary.data(maid[0]).getBoolean("armed")) {
                    helper.assertTrue(tick >= 25 && worker(maid[0]).getLong("workedTicks") == 20, "Patrol must work 20 active ticks across pause/reload");
                    helper.assertTrue(FlowLibrary.data(maid[0]).getString("status").equals("SUCCEEDED"), "Patrol duration must advance to end");
                    done.set(true); FlowRuntime.drop(maid[0]); owner.close(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); FlowRuntime.drop(maid[0]); owner.close(); throw exception; }
        });
        helper.runAfterDelay(65, () -> { if (!done.get()) { done.set(true); FlowRuntime.drop(maid[0]); owner.close(); helper.fail("Timed patrol did not finish"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 50)
    public static void killCountExcludesOtherDamageAndSurvivesReload(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid[] maid = {testMaid(helper, owner)};
        var first = new net.minecraft.world.entity.animal.Cow(net.minecraft.world.entity.EntityType.COW, helper.getLevel());
        var second = new net.minecraft.world.entity.animal.Cow(net.minecraft.world.entity.EntityType.COW, helper.getLevel());
        var unrelated = new net.minecraft.world.entity.animal.Cow(net.minecraft.world.entity.EntityType.COW, helper.getLevel());
        for (var cow : List.of(first, second, unrelated)) { cow.setNoAi(true); cow.setNoGravity(true); cow.setPos(maid[0].getX() + 10, maid[0].getY(), maid[0].getZ()); helper.getLevel().addFreshEntity(cow); }
        FlowGraph graph = workGraph(FlowGraph.Kind.ATTACK, Map.of("filter", "minecraft:cow", "radius", "1", "finishMode", "count", "finishCount", "2"));
        FlowLibrary.save(maid[0], graph, 0); FlowRuntime.select(maid[0], graph);
        AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0};
        Runnable cleanup = () -> { FlowRuntime.drop(maid[0]); first.discard(); second.discard(); unrelated.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0]; FlowRuntime.tick(maid[0]);
                if (tick == 2) {
                    unrelated.hurt(unrelated.damageSources().genericKill(), 1000);
                    helper.assertTrue(worker(maid[0]).getInt("finishedTargets") == 0, "Other damage must not count as maid kills");
                    first.hurt(first.damageSources().mobAttack(maid[0]), 1000);
                    helper.assertTrue(worker(maid[0]).getInt("finishedTargets") == 1, "A maid kill must count exactly once");
                    maid[0] = reload(helper, maid[0]);
                }
                if (tick == 4) {
                    helper.assertTrue(worker(maid[0]).getInt("finishedTargets") == 1 && FlowLibrary.data(maid[0]).getBoolean("armed"), "Partial count must survive reload");
                    second.hurt(second.damageSources().mobAttack(maid[0]), 1000);
                }
                if (!FlowLibrary.data(maid[0]).getBoolean("armed")) {
                    helper.assertTrue(worker(maid[0]).getInt("finishedTargets") == 2, "Kill target count must be reached before exit");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); cleanup.run(); throw exception; }
        });
        helper.runAfterDelay(35, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Kill count did not finish"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 70)
    public static void harvestCountsOnlySuccessfullyDestroyedBlocks(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        BlockPos center = maid.blockPosition(), second = center.east();
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
        helper.getLevel().setBlockAndUpdate(center, Blocks.DIAMOND_BLOCK.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(second, Blocks.DIAMOND_BLOCK.defaultBlockState());
        FlowGraph graph = workGraph(FlowGraph.Kind.HARVEST, Map.of("filter", "minecraft:diamond_block", "target", target(center), "radius", "1", "finishMode", "count", "finishCount", "2"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                FlowRuntime.tick(maid);
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(worker(maid).getInt("finishedTargets") == 2 && helper.getLevel().getBlockState(center).isAir()
                            && helper.getLevel().getBlockState(second).isAir(), "Both actual block destructions must count");
                    done.set(true); FlowRuntime.drop(maid); owner.close(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); FlowRuntime.drop(maid); owner.close(); throw exception; }
        });
        helper.runAfterDelay(55, () -> { if (!done.get()) { done.set(true); FlowRuntime.drop(maid); owner.close(); helper.fail("Harvest count did not finish"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 50)
    public static void lootCountsNewActualPickupsAndExcludesStartingInventory(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid[] maid = {testMaid(helper, owner)};
        net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(maid[0].getAvailableInv(false), new ItemStack(Items.AMETHYST_SHARD, 8), false);
        FlowGraph graph = workGraph(FlowGraph.Kind.PATROL, Map.of("filter", "minecraft:ender_dragon", "finishMode", "loot", "finishItem", "minecraft:amethyst_shard", "finishCount", "5"));
        FlowLibrary.save(maid[0], graph, 0); FlowRuntime.select(maid[0], graph);
        List<net.minecraft.world.entity.item.ItemEntity> drops = new ArrayList<>(); AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0};
        Runnable cleanup = () -> { FlowRuntime.drop(maid[0]); drops.forEach(net.minecraft.world.entity.Entity::discard); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0]; FlowRuntime.tick(maid[0]);
                if (tick == 2 || tick == 5) {
                    helper.assertTrue(FlowLibrary.data(maid[0]).getBoolean("armed"), "Existing inventory and partial loot must not finish the node");
                    if (tick == 5) { helper.assertTrue(worker(maid[0]).getInt("finishedLoot") == 3, "Exactly three newly picked items must be persisted"); maid[0] = reload(helper, maid[0]); }
                    var item = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), maid[0].getX(), maid[0].getY(), maid[0].getZ(), new ItemStack(Items.AMETHYST_SHARD, tick == 2 ? 3 : 2));
                    item.setNoPickUpDelay(); helper.getLevel().addFreshEntity(item); drops.add(item);
                }
                if (!FlowLibrary.data(maid[0]).getBoolean("armed")) {
                    helper.assertTrue(worker(maid[0]).getInt("finishedLoot") == 5, "Only five actual new pickups should count, even when TLM post stack is empty");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); cleanup.run(); throw exception; }
        });
        helper.runAfterDelay(35, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Loot count did not finish"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void quietRuleWaitsRatherThanFinishingOnFirstEmptyScan(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        FlowGraph graph = workGraph(FlowGraph.Kind.ATTACK, Map.of("filter", "minecraft:ender_dragon", "finishMode", "quiet", "finishDuration", "0.2"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0};
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                FlowRuntime.tick(maid); int tick = ++ticks[0];
                if (tick == 2) helper.assertTrue(FlowLibrary.data(maid).getBoolean("armed"), "Quiet rule must not finish at first no-target tick");
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(tick >= 5 && worker(maid).getLong("quietTicks") == 4, "Quiet interval must elapse before continuing");
                    done.set(true); FlowRuntime.drop(maid); owner.close(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); FlowRuntime.drop(maid); owner.close(); throw exception; }
        });
        helper.runAfterDelay(20, () -> { if (!done.get()) { done.set(true); FlowRuntime.drop(maid); owner.close(); helper.fail("Quiet rule did not finish"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realPatrolBrainDefeatsHostileAndAdvancesAtKillGoal(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        var hostile = new net.minecraft.world.entity.monster.Husk(net.minecraft.world.entity.EntityType.HUSK, helper.getLevel());
        BlockPos center = maid.blockPosition();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            helper.getLevel().setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        hostile.setNoAi(true); hostile.setNoGravity(true); hostile.setHealth(1); hostile.setPos(maid.getX() + 1, maid.getY(), maid.getZ());
        helper.getLevel().addFreshEntity(hostile); maid.setNoAi(false); maid.setInSittingPose(false); helper.getLevel().addFreshEntity(maid);
        FlowGraph graph = workGraph(FlowGraph.Kind.PATROL, Map.of("finishMode", "count", "finishCount", "1", "radius", "1"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); maid.discard(); hostile.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            if (!FlowLibrary.data(maid).getBoolean("armed")) {
                try {
                    helper.assertTrue(FlowLibrary.data(maid).getString("status").equals("SUCCEEDED") && !hostile.isAlive()
                            && worker(maid).getInt("finishedTargets") == 1, "Real patrol AI must kill the hostile and reach the goal");
                    done.set(true); cleanup.run(); helper.succeed();
                } catch (RuntimeException | Error exception) { done.set(true); cleanup.run(); throw exception; }
            }
        });
        helper.runAfterDelay(80, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Real patrol AI did not defeat the nearby hostile"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void manualGraphAddKeepsOnlyOriginalWireAndDisconnectDeletesIt(GameTestHelper helper) {
        FlowGraph graph = FlowGraph.empty("手动连线"); List<FlowGraph.Edge> original = graph.edges();
        graph = graph.add(node("extra", FlowGraph.Kind.MOVE, Map.of()));
        helper.assertTrue(graph.edges().equals(original), "Adding a module must never create or replace wires");
        FlowGraph.Edge wire = original.get(0); graph = graph.disconnect(wire.from(), wire.port());
        helper.assertTrue(graph.edges().isEmpty(), "The initial START to END wire must be deletable");
        graph = graph.connect(edge(wire.from(), FlowGraph.Port.NEXT, "extra")).connect(edge("extra", FlowGraph.Port.NEXT, wire.to()));
        graph.validateExecutable(); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nativeWorkRejectsRecursiveAndUnknownModesAndMeetsInventoryGoal(GameTestHelper helper) {
        for (String id : List.of(FlowWorkTask.UID.toString(), "missing:work")) {
            boolean rejected = false;
            try { FlowRuntime.validate(workGraph(FlowGraph.Kind.NATIVE_WORK, Map.of("nativeTask", id))); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Recursive or absent native work must be rejected");
        }
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        try {
            net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(false), new ItemStack(Items.APPLE, 3), false);
            FlowGraph graph = workGraph(FlowGraph.Kind.NATIVE_WORK, Map.of("nativeTask", TaskManager.getIdleTask().getUid().toString(),
                    "finishMode", "inventory", "finishItem", "minecraft:apple", "finishCount", "3"));
            FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); FlowRuntime.tick(maid);
            helper.assertTrue(FlowLibrary.data(maid).getCompound("execution").getCompound("runner").getString("current").equals("end")
                    && !FlowLibrary.data(maid).contains("delegate"), "An already satisfied inventory goal must exit without leaving native work armed");
            FlowRuntime.drop(maid); helper.succeed();
        } finally { owner.close(); }
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeModeActuallyActivatesPausesAndReturnsToFlow(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid[] maid = {testMaid(helper, owner)};
        FlowGraph graph = workGraph(FlowGraph.Kind.NATIVE_WORK, Map.of("nativeTask", TaskManager.getIdleTask().getUid().toString(), "finishDuration", "1"));
        FlowLibrary.save(maid[0], graph, 0); FlowRuntime.select(maid[0], graph); maid[0].setNoAi(false); maid[0].setInSittingPose(false);
        helper.getLevel().addFreshEntity(maid[0]); AtomicBoolean done = new AtomicBoolean(), activated = new AtomicBoolean(), resumed = new AtomicBoolean();
        int[] ticks = {0}; long[] paused = {0};
        Runnable cleanup = () -> { FlowRuntime.drop(maid[0]); maid[0].discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0];
                if (maid[0].getTask().getUid().equals(TaskManager.getIdleTask().getUid()) && FlowRuntime.managesNative(maid[0])) activated.set(true);
                if (tick == 7) { helper.assertTrue(activated.get(), "The real native task UID must activate"); paused[0] = worker(maid[0]).getLong("workedTicks"); maid[0].guiOpening = true; }
                if (tick > 7 && tick < 12) helper.assertTrue(worker(maid[0]).getLong("workedTicks") == paused[0], "Native time must pause in its menu");
                if (tick == 12) maid[0].guiOpening = false;
                if (tick == 15) {
                    CompoundTag saved = maid[0].saveWithoutId(new CompoundTag()); FlowRuntime.drop(maid[0]); maid[0].discard();
                    EntityMaid restored = new EntityMaid(helper.getLevel()); restored.load(saved); restored.setNoAi(false); restored.setNoGravity(true);
                    maid[0] = restored; helper.getLevel().addFreshEntity(restored); resumed.set(true);
                }
                if (!FlowLibrary.data(maid[0]).getBoolean("armed")) {
                    helper.assertTrue(activated.get() && resumed.get() && FlowLibrary.data(maid[0]).getString("status").equals("SUCCEEDED")
                            && maid[0].getTask().getUid().equals(FlowWorkTask.UID) && !FlowLibrary.data(maid[0]).contains("delegate"), "Native work must resume after reload, finish, and restore flow mode");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); cleanup.run(); throw exception; }
        });
        helper.runAfterDelay(80, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Native mode did not return to flow"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void nativeAttackBrainKillsTargetAndGoalReturnsToFlow(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        var hostile = new net.minecraft.world.entity.monster.Husk(net.minecraft.world.entity.EntityType.HUSK, helper.getLevel());
        BlockPos center = maid.blockPosition();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) helper.getLevel().setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        hostile.setNoAi(true); hostile.setNoGravity(true); hostile.setHealth(1); hostile.setPos(maid.getX() + 1, maid.getY(), maid.getZ());
        helper.getLevel().addFreshEntity(hostile); maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        maid.setNoAi(false); maid.setInSittingPose(false); helper.getLevel().addFreshEntity(maid);
        FlowGraph graph = workGraph(FlowGraph.Kind.NATIVE_WORK, Map.of("nativeTask", "touhou_little_maid:attack", "finishMode", "count", "filter", "minecraft:husk", "finishCount", "1"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean(), nativeActive = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); maid.discard(); hostile.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                if (maid.getTask().getUid().toString().equals("touhou_little_maid:attack")) nativeActive.set(true);
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(nativeActive.get() && !hostile.isAlive() && worker(maid).getInt("finishedTargets") == 1
                            && FlowLibrary.data(maid).getString("status").equals("SUCCEEDED") && maid.getTask().getUid().equals(FlowWorkTask.UID),
                            "Actual native melee AI must defeat the target, satisfy its goal and restore flow");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error exception) { done.set(true); cleanup.run(); throw exception; }
        });
        helper.runAfterDelay(135, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Actual native attack did not satisfy its kill goal"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 70)
    public static void singleSwordTakeEquipAttackStoreAndReturnCompletes(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        BlockPos source = maid.blockPosition().east(), destination = maid.blockPosition().west();
        helper.getLevel().setBlockAndUpdate(source, Blocks.CHEST.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(destination, Blocks.BARREL.defaultBlockState());
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(source);
        ItemStack sword = new ItemStack(Items.NETHERITE_SWORD); sword.setDamageValue(17); sword.setHoverName(net.minecraft.network.chat.Component.literal("测试之剑"));
        chest.setItem(0, sword);
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        owner.player.setPos(maid.getX(), maid.getY(), maid.getZ() - 2); owner.player.setYRot(0); owner.player.setXRot(0);
        var hostile = new net.minecraft.world.entity.monster.Husk(net.minecraft.world.entity.EntityType.HUSK, helper.getLevel());
        hostile.setNoAi(true); hostile.setNoGravity(true); hostile.setHealth(1); hostile.setPos(maid.getX(), maid.getY(), maid.getZ() + 1);
        helper.getLevel().addFreshEntity(hostile);
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "取剑战斗归还", List.of(node("start", FlowGraph.Kind.START, Map.of()),
                node("take", FlowGraph.Kind.TAKE, Map.of("target", target(source), "filter", "minecraft:netherite_sword")),
                node("fight", FlowGraph.Kind.ATTACK, Map.of("filter", "minecraft:husk", "radius", "1", "finishMode", "count", "finishCount", "1")),
                node("store", FlowGraph.Kind.STORE, Map.of("target", target(destination), "filter", "minecraft:netherite_sword")),
                node("move", FlowGraph.Kind.MOVE, Map.of("targetMode", "主人所在位置")), node("end", FlowGraph.Kind.END, Map.of())),
                List.of(edge("start", FlowGraph.Port.NEXT, "take"), edge("take", FlowGraph.Port.NEXT, "fight"), edge("fight", FlowGraph.Port.NEXT, "store"),
                        edge("store", FlowGraph.Port.NEXT, "move"), edge("move", FlowGraph.Port.NEXT, "end")));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean(), equipped = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); hostile.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                FlowRuntime.tick(maid);
                if (maid.getMainHandItem().is(Items.NETHERITE_SWORD)) equipped.set(true);
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    var inventory = helper.getLevel().getBlockEntity(destination).getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
                    ItemStack returned = inventory.getStackInSlot(0);
                    helper.assertTrue(equipped.get() && !hostile.isAlive() && chest.getItem(0).isEmpty() && returned.is(Items.NETHERITE_SWORD)
                            && returned.getHoverName().getString().equals("测试之剑") && returned.getDamageValue() >= 17
                            && maid.getMainHandItem().isEmpty() && FlowLibrary.data(maid).getString("status").equals("SUCCEEDED"),
                            "A single sword must equip, kill, return from main hand and finish without waiting for 64 swords");
                    helper.assertTrue(maid.getMaidInv().getStackInSlot(0).is(Items.IRON_AXE), "Original hand item must be preserved in backpack");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(55, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Sword flow failed to progress: " + FlowLibrary.data(maid)); } });
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void failedItemPoliciesSkipOrWaitAndPreserveProgress(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper);
        List<EntityMaid> maids = new ArrayList<>();
        try {
            for (String policy : FlowItemRule.POLICIES) {
                EntityMaid maid = testMaid(helper, owner); maids.add(maid);
                BlockPos pos = maid.blockPosition().east(); helper.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
                ((ChestBlockEntity) helper.getLevel().getBlockEntity(pos)).clearContent();
                FlowGraph graph = workGraph(FlowGraph.Kind.TAKE, Map.of("filter", "minecraft:apple", "target", target(pos), "itemFailure", policy));
                FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); FlowRuntime.tick(maid);
                String current = FlowLibrary.data(maid).getCompound("execution").getCompound("runner").getString("current");
                helper.assertTrue(current.equals(policy.equals("skip") ? "end" : "work"), "Failure policy must explicitly skip or remain on the node: " + policy);
                if (!policy.equals("skip")) helper.assertTrue(worker(maid).getLong("retry") > helper.getLevel().getGameTime(), "Waiting must have a retry cooldown");
            }
            helper.succeed();
        } finally { maids.forEach(FlowRuntime::drop); owner.close(); }
    }
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void partialTakeReloadWaitsThenCompletesWithoutDuplicating(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid[] maid = {testMaid(helper, owner)};
        BlockPos pos = maid[0].blockPosition().east(); helper.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(pos); chest.setItem(0, new ItemStack(Items.APPLE));
        FlowGraph graph = workGraph(FlowGraph.Kind.TAKE, Map.of("filter", "minecraft:apple", "target", target(pos), "itemCount", "2"));
        FlowLibrary.save(maid[0], graph, 0); FlowRuntime.select(maid[0], graph);
        AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0};
        Runnable cleanup = () -> { FlowRuntime.drop(maid[0]); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0]; FlowRuntime.tick(maid[0]);
                if (tick == 3) { helper.assertTrue(worker(maid[0]).getInt("taken") == 1, "Partial count must persist"); maid[0] = reload(helper, maid[0]); chest.setItem(0, new ItemStack(Items.APPLE)); }
                if (!FlowLibrary.data(maid[0]).getBoolean("armed")) {
                    helper.assertTrue(maid[0].getMaidInv().getStackInSlot(0).getCount() == 2 && chest.getItem(0).isEmpty()
                            && FlowLibrary.data(maid[0]).getString("status").equals("SUCCEEDED"), "Retry after reload must transfer only the missing item; bag=" + maid[0].getMaidInv().serializeNBT() + "; chest=" + chest.getItem(0) + "; state=" + FlowLibrary.data(maid[0]));
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(135, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Partial take did not recover"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void fullBackpackDoesNotLoseExistingOrNewWeapon(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        try {
            var bag = maid.getAvailableBackpackInv();
            for (int slot = 0; slot < bag.getSlots(); slot++) bag.insertItem(slot, new ItemStack(Items.STONE, 64), false);
            maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.NETHERITE_SWORD));
            maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_AXE));
            FlowEquipment.equipAcquired(maid, "minecraft:netherite_sword");
            helper.assertTrue(maid.getMainHandItem().is(Items.DIAMOND_AXE) && maid.getMaidInv().getStackInSlot(0).is(Items.NETHERITE_SWORD), "When an occupied hand cannot be stowed, preserve both weapons");
            helper.succeed();
        } finally { owner.close(); }
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void filmRestorePreservesLibraryButDisarmsExecution(GameTestHelper helper) {
        EntityMaid maid = new EntityMaid(helper.getLevel()); FlowGraph graph = FlowGraph.empty("死亡后保留");
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph);
        ItemStack film = com.github.tartaricacid.touhoulittlemaid.item.ItemFilm.maidToFilm(maid);
        var data = com.github.tartaricacid.touhoulittlemaid.item.AbstractStoreMaidItem.getMaidData(film);
        EntityMaid restored = new EntityMaid(helper.getLevel());
        // The same event + readAdditionalSaveData order used by ItemFilm.filmToMaid.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToMaid(restored, film, data));
        restored.readAdditionalSaveData(data);
        helper.assertTrue(FlowLibrary.find(restored, graph.id()).name().equals(graph.name()) && !FlowLibrary.data(restored).getBoolean("armed")
                && !FlowLibrary.data(restored).contains("execution"), "Film restore must retain definitions without executing the pre-death action");
        FlowRuntime.drop(maid); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void auditEveryPaletteKindAndRejectUntriggerableLoopExit(GameTestHelper helper) {
        for (FlowGraph.Kind kind : FlowGraph.Kind.values()) {
            if (kind.family() != FlowGraph.Family.ACTION) continue;
            Map<String, String> settings = switch (kind) {
                case TAKE, STORE -> Map.of("filter", "minecraft:stone", "target", "0,64,0");
                case ATTACK -> Map.of("filter", "minecraft:zombie");
                case HARVEST -> Map.of("filter", "minecraft:wheat");
                case NATIVE_WORK -> Map.of("nativeTask", TaskManager.getIdleTask().getUid().toString());
                case FEED, BREED -> Map.of("animalType", "minecraft:cow", "filter", "minecraft:wheat");
                default -> Map.of();
            };
            boolean supported = !List.of(FlowGraph.Kind.TRADE, FlowGraph.Kind.USE).contains(kind);
            boolean accepted = true; try { FlowRuntime.validate(workGraph(kind, settings)); } catch (IllegalArgumentException error) { accepted = false; }
            helper.assertTrue(accepted == supported, "Every palette action must validate or explicitly report unsupported: " + kind);
        }
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "假出口", List.of(node("start", FlowGraph.Kind.START, Map.of()),
                node("a", FlowGraph.Kind.TRIGGER, Map.of("conditionType", "always")), node("b", FlowGraph.Kind.MOVE, Map.of()), node("end", FlowGraph.Kind.END, Map.of())),
                List.of(edge("start", FlowGraph.Port.NEXT, "a"), edge("a", FlowGraph.Port.YES, "b"), edge("a", FlowGraph.Port.NO, "end"), edge("b", FlowGraph.Port.NEXT, "a")));
        boolean rejected = false; try { FlowRuntime.validate(graph); } catch (IllegalArgumentException error) { rejected = true; }
        helper.assertTrue(rejected, "A connected exit that a constant condition can never trigger must be rejected"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void attackCannotHitThroughWallAndSwingsAfterWallRemoved(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        var hostile = new net.minecraft.world.entity.monster.Husk(net.minecraft.world.entity.EntityType.HUSK, helper.getLevel());
        hostile.setNoAi(true); hostile.setNoGravity(true); hostile.setPos(maid.getX() + 2, maid.getY(), maid.getZ()); helper.getLevel().addFreshEntity(hostile);
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD));
        BlockPos wall = maid.blockPosition().east(); helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState()); helper.getLevel().setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
        FlowGraph graph = workGraph(FlowGraph.Kind.ATTACK, Map.of("filter", "minecraft:husk", "finishMode", "count", "finishCount", "1"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0};
        Runnable cleanup = () -> { FlowRuntime.drop(maid); hostile.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0]; FlowRuntime.tick(maid);
                if (tick <= 8) helper.assertTrue(hostile.getHealth() == hostile.getMaxHealth(), "A wall must prevent every melee hit");
                if (tick == 8) { helper.getLevel().removeBlock(wall, false); helper.getLevel().removeBlock(wall.above(), false); hostile.setPos(maid.getX() + 1, maid.getY(), maid.getZ());
                }
                if (tick > 8 && hostile.getHealth() < hostile.getMaxHealth()) {
                    helper.assertTrue(maid.swinging, "Successful attack must broadcast a swing animation"); done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(145, () -> { if (!done.get()) { done.set(true); cleanup.run(); helper.fail("Attack did not recover when obstruction was removed"); } });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void realNavigationDescendsStepsAndDoesNotReturnToOwner(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = new EntityMaid(helper.getLevel());
        BlockPos base = helper.absolutePos(new BlockPos(1, 21, 1));
        for (int x = -1; x <= 15; x++) for (int z = -1; z <= 1; z++) {
            for (int y = 1; y <= 6; y++) helper.getLevel().setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
            int height = x <= 2 ? 2 : x <= 4 ? 1 : 0;
            helper.getLevel().setBlockAndUpdate(base.offset(x, height, z), Blocks.STONE.defaultBlockState());
        }
        maid.setTame(true); maid.setOwnerUUID(owner.player.getUUID()); maid.setInSittingPose(false);
        maid.setPos(base.getX() + .5, base.getY() + 3, base.getZ() + .5);
        owner.player.setPos(maid.getX(), maid.getY(), maid.getZ());
        BlockPos goal = base.offset(14, 1, 0);
        FlowGraph graph = workGraph(FlowGraph.Kind.MOVE, Map.of("targetMode", "坐标（高级）", "target", target(goal)));
        helper.getLevel().addFreshEntity(maid); FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph);
        AtomicBoolean done = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); maid.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(FlowLibrary.data(maid).getString("status").equals("SUCCEEDED")
                            && maid.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(goal)) < 1,
                            "Real brain must descend two steps and reach a goal beyond the owner-follow radius: " + FlowLibrary.data(maid));
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(215, () -> { if (!done.get()) { done.set(true); String evidence = maid.position() + " " + FlowLibrary.data(maid); cleanup.run(); helper.fail("Real navigation did not finish: " + evidence); } });
    }
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void harvestWalksToAnAdjacentWorkPositionAndShowsBreaking(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = new EntityMaid(helper.getLevel());
        BlockPos base = helper.absolutePos(new BlockPos(1, 31, 1));
        for (int x = -1; x <= 8; x++) for (int z = -2; z <= 2; z++) {
            for (int y = 1; y <= 5; y++) helper.getLevel().setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(base.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos block = base.offset(7, 1, 0); helper.getLevel().setBlockAndUpdate(block, Blocks.OAK_LOG.defaultBlockState());
        maid.setTame(true); maid.setOwnerUUID(owner.player.getUUID()); maid.setInSittingPose(false);
        maid.setPos(base.getX() + .5, base.getY() + 1, base.getZ() + .5); owner.player.setPos(maid.getX(), maid.getY(), maid.getZ());
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        FlowGraph graph = workGraph(FlowGraph.Kind.HARVEST, Map.of("target", target(block), "filter", "minecraft:oak_log", "finishMode", "count", "finishCount", "1", "radius", "1"));
        helper.getLevel().addFreshEntity(maid); FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph);
        AtomicBoolean done = new AtomicBoolean(), animation = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); maid.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                if (worker(maid).getInt("breakTicks") > 0 && maid.swinging) animation.set(true);
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(animation.get() && helper.getLevel().getBlockState(block).isAir()
                            && !maid.blockPosition().equals(block) && maid.getMainHandItem().getDamageValue() > 0
                            && FlowLibrary.data(maid).getString("status").equals("SUCCEEDED"), "Harvest must stand nearby, animate, destroy through maid hooks and consume tool durability");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(155, () -> { if (!done.get()) { done.set(true); String evidence = maid.position() + " " + FlowLibrary.data(maid); cleanup.run(); helper.fail("Harvest work position did not finish: " + evidence); } });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void feedHasAnimalFoodAndSuccessfulFeedingGoal(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        var calf = new net.minecraft.world.entity.animal.Cow(net.minecraft.world.entity.EntityType.COW, helper.getLevel());
        calf.setAge(-24000); calf.setNoAi(true); calf.setNoGravity(true); calf.setPos(maid.getX() + 1, maid.getY(), maid.getZ()); helper.getLevel().addFreshEntity(calf);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.WHEAT, 3));
        FlowGraph graph = workGraph(FlowGraph.Kind.FEED, Map.of("animalType", "minecraft:cow", "filter", "minecraft:wheat", "finishMode", "count", "finishCount", "1"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); calf.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                FlowRuntime.tick(maid);
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(calf.getAge() > -23000 && !calf.isInLove() && maid.getMaidInv().getStackInSlot(0).getCount() == 2
                            && worker(maid).getInt("finishedTargets") == 1 && FlowLibrary.data(maid).getString("status").equals("SUCCEEDED"),
                            "Feeding must target the selected baby, consume exactly one matching food and count the real interaction");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(30, () -> { if (!done.get()) { done.set(true); String evidence = FlowLibrary.data(maid).toString(); cleanup.run(); helper.fail("Feeding did not finish: " + evidence); } });
    }
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void breedGoalCountsActualBabyRatherThanFedParents(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        var a = new net.minecraft.world.entity.animal.Cow(net.minecraft.world.entity.EntityType.COW, helper.getLevel());
        var b = new net.minecraft.world.entity.animal.Cow(net.minecraft.world.entity.EntityType.COW, helper.getLevel());
        BlockPos center = maid.blockPosition();
        for (int x = -2; x <= 3; x++) for (int z = -2; z <= 3; z++) helper.getLevel().setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        a.setPos(maid.getX() + 1, maid.getY(), maid.getZ()); b.setPos(maid.getX(), maid.getY(), maid.getZ() + 1);
        a.setNoGravity(true); b.setNoGravity(true); a.setNoAi(true); b.setNoAi(true); helper.getLevel().addFreshEntity(a); helper.getLevel().addFreshEntity(b);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.WHEAT, 8));
        FlowGraph graph = workGraph(FlowGraph.Kind.BREED, Map.of("animalType", "minecraft:cow", "filter", "minecraft:wheat", "finishMode", "count", "finishCount", "1"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0};
        Runnable cleanup = () -> { FlowRuntime.drop(maid); a.discard(); b.discard();
            helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, new net.minecraft.world.phys.AABB(center).inflate(5), net.minecraft.world.entity.animal.Cow::isBaby).forEach(net.minecraft.world.entity.Entity::discard); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0]; FlowRuntime.tick(maid);
                if (a.isInLove() && b.isInLove()) { a.setNoAi(false); b.setNoAi(false); }
                if (tick == 5) helper.assertTrue(worker(maid).getInt("finishedTargets") == 0 && FlowLibrary.data(maid).getBoolean("armed"), "Feeding the first parent must not count as a birth");
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(worker(maid).getInt("finishedTargets") == 1 && a.getAge() > 0 && b.getAge() > 0
                            && !helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, new net.minecraft.world.phys.AABB(center).inflate(5), net.minecraft.world.entity.animal.Cow::isBaby).isEmpty()
                            && FlowLibrary.data(maid).getString("status").equals("SUCCEEDED"), "Only a real birth from the fed parents may satisfy the breeding goal");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(155, () -> { if (!done.get()) { done.set(true); String evidence = FlowLibrary.data(maid).toString(); cleanup.run(); helper.fail("Breeding did not finish: " + evidence); } });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void sealedHarvestTargetIsDeferredAndReachableTargetIsWorked(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        BlockPos base = helper.absolutePos(new BlockPos(1, 41, 1));
        for (int x = -1; x <= 8; x++) for (int z = -3; z <= 3; z++) {
            helper.getLevel().setBlockAndUpdate(base.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            for (int y = 1; y <= 5; y++) helper.getLevel().setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        BlockPos sealed = base.offset(5, 1, 0), accessible = base.offset(2, 1, 0);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            helper.getLevel().setBlockAndUpdate(sealed.offset(x, y, z), Blocks.BEDROCK.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(sealed, Blocks.OAK_LOG.defaultBlockState()); helper.getLevel().setBlockAndUpdate(accessible, Blocks.OAK_LOG.defaultBlockState());
        maid.setPos(base.getX() + .5, base.getY() + 1, base.getZ() + .5); owner.player.setPos(maid.getX(), maid.getY(), maid.getZ());
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        FlowGraph graph = workGraph(FlowGraph.Kind.HARVEST, Map.of("target", target(sealed), "filter", "minecraft:oak_log", "radius", "1", "finishMode", "count", "finishCount", "1"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                FlowRuntime.tick(maid);
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(worker(maid).getLongArray("blockedBlocks").length == 1 && helper.getLevel().getBlockState(sealed).is(Blocks.OAK_LOG)
                            && helper.getLevel().getBlockState(accessible).isAir() && FlowLibrary.data(maid).getString("status").equals("SUCCEEDED"),
                            "An unreachable first target must be deferred without preventing work on a reachable target");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(65, () -> { if (!done.get()) { done.set(true); String evidence = worker(maid).toString(); cleanup.run(); helper.fail("Deferring target did not allow progress: " + evidence); } });
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void blockedChestLidAndEmptyStoreRespectFailurePolicies(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        try {
            BlockPos pos = maid.blockPosition().east(); helper.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(pos.above(), Blocks.STONE.defaultBlockState());
            var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(pos); chest.setItem(0, new ItemStack(Items.APPLE));
            FlowGraph graph = workGraph(FlowGraph.Kind.TAKE, Map.of("target", target(pos), "filter", "minecraft:apple"));
            FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); FlowRuntime.tick(maid);
            helper.assertTrue(chest.getItem(0).getCount() == 1 && worker(maid).getInt("taken") == 0 && worker(maid).getLong("retry") > helper.getLevel().getGameTime(),
                    "A blocked lid must stop extraction and schedule a recoverable wait");
            FlowRuntime.cancel(maid);
            FlowGraph store = workGraph(FlowGraph.Kind.STORE, Map.of("target", target(pos), "filter", "minecraft:stone", "itemFailure", "skip"));
            FlowLibrary.save(maid, store, 1); FlowRuntime.select(maid, store); FlowRuntime.tick(maid);
            helper.assertTrue(FlowLibrary.data(maid).getCompound("execution").getCompound("runner").getString("current").equals("end"), "An empty STORE must obey explicit skip policy");
            helper.succeed();
        } finally { FlowRuntime.drop(maid); owner.close(); }
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void diagramGroupMoveLayoutsAndPositionUndoPreserveTaskLogic(GameTestHelper helper) {
        FlowGraph graph = diagramGraph();
        var moved = FlowDiagram.move(graph, Set.of("start", "branch"), -36, 24);
        helper.assertTrue(moved.nodes().get(0).x() == graph.nodes().get(0).x() - 36 && moved.nodes().get(1).y() == graph.nodes().get(1).y() + 24
                && moved.nodes().get(2).equals(graph.nodes().get(2)) && moved.edges().equals(graph.edges()), "Group movement must preserve offsets, unselected cards and all wires");
        Map<String, FlowDiagram.Point> before = new HashMap<>(); graph.nodes().forEach(node -> before.put(node.id(), new FlowDiagram.Point(node.x(), node.y())));
        for (FlowDiagram.Layout layout : FlowDiagram.Layout.values()) {
            FlowGraph arranged = FlowDiagram.arrange(graph, layout);
            helper.assertTrue(arranged.equals(FlowDiagram.arrange(graph, layout)) && arranged.edges().equals(graph.edges()), "Layout must be deterministic and retain every branch/loop: " + layout);
            for (int a = 0; a < arranged.nodes().size(); a++) for (int b = a + 1; b < arranged.nodes().size(); b++) {
                var first = arranged.nodes().get(a); var second = arranged.nodes().get(b);
                helper.assertTrue(first.x() + FlowDiagram.WIDTH <= second.x() || second.x() + FlowDiagram.WIDTH <= first.x()
                        || first.y() + FlowDiagram.HEIGHT <= second.y() || second.y() + FlowDiagram.HEIGHT <= first.y(), "No cards may overlap after arranging");
            }
            var edited = arranged.replace(arranged.nodes().get(1).configure("conditionType", "night"));
            var restored = FlowDiagram.restorePositions(edited, before);
            helper.assertTrue(restored.nodes().get(1).x() == graph.nodes().get(1).x() && restored.nodes().get(1).setting("conditionType", "").equals("night"), "Position undo must keep later parameter edits");
        }
        var all = graph.nodes().stream().map(FlowGraph.Node::id).collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(FlowDiagram.move(graph, all, 48, -24).nodes().stream().allMatch(node -> node.x() == graph.nodes().get(graph.indexOf(node.id())).x() + 48), "Select-all move must include START and END");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void arrowRoutingAvoidsCardsAndSupportsLoopBackEdges(GameTestHelper helper) {
        FlowGraph graph = diagramGraph();
        for (FlowGraph.Edge edge : graph.edges()) {
            var route = FlowDiagram.route(graph, edge, true);
            helper.assertTrue(route.size() >= 2, "Every separated edge including the back edge must find a safe route: " + edge);
            for (int index = 1; index < route.size(); index++) {
                var a = route.get(index - 1); var b = route.get(index);
                helper.assertTrue(a.x() == b.x() || a.y() == b.y(), "Routes must use orthogonal segments");
                helper.assertTrue(!FlowDiagram.intersects(graph, a, b, index == 1 ? edge.from() : null,
                        index == route.size() - 1 ? edge.to() : null), "Wire segments must avoid all unrelated card bodies");
            }
            helper.assertTrue(FlowDiagram.route(graph, edge, false).size() == 4, "Disabling avoidance must restore the simple routing option");
        }
        helper.succeed();
    }
    private static FlowGraph diagramGraph() {
        return new FlowGraph(UUID.randomUUID(), "分支循环图", List.of(new FlowGraph.Node("start", FlowGraph.Kind.START, 300, 80, Map.of()),
                new FlowGraph.Node("branch", FlowGraph.Kind.BRANCH, 300, 200, Map.of("conditionType", "day")),
                new FlowGraph.Node("yes", FlowGraph.Kind.MOVE, 300, 320, Map.of()), new FlowGraph.Node("no", FlowGraph.Kind.MOVE, 560, 320, Map.of()),
                new FlowGraph.Node("loop", FlowGraph.Kind.LOOP_END, 300, 440, Map.of("conditionType", "loop_count", "loopCount", "3")),
                new FlowGraph.Node("end", FlowGraph.Kind.END, 300, 560, Map.of())), List.of(edge("start", FlowGraph.Port.NEXT, "branch"),
                edge("branch", FlowGraph.Port.YES, "yes"), edge("branch", FlowGraph.Port.NO, "no"), edge("yes", FlowGraph.Port.NEXT, "loop"),
                edge("no", FlowGraph.Port.NEXT, "loop"), edge("loop", FlowGraph.Port.YES, "branch"), edge("loop", FlowGraph.Port.NO, "end")));
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void oneShotBranchChoosesOneExitWithoutWaitingOrRechecking(GameTestHelper helper) {
        FlowGraph graph = diagramGraph(); FlowRuntime.validate(graph);
        FlowRunner runner = new FlowRunner(graph); runner.start(graph); int[] checks = {0};
        runner.tick(node -> node.kind() == FlowGraph.Kind.START ? FlowRunner.WorkResult.done() : node.kind() == FlowGraph.Kind.BRANCH
                ? (checks[0]++ == 0 ? FlowRunner.WorkResult.branch(false) : FlowRunner.WorkResult.branch(true)) : FlowRunner.WorkResult.running());
        helper.assertTrue(checks[0] == 1 && runner.currentNode().equals("no"), "A branch must evaluate once and immediately select its false exit");
        runner.tick(node -> FlowRunner.WorkResult.running());
        helper.assertTrue(checks[0] == 1 && runner.currentNode().equals("no"), "Waiting in the next action must not reevaluate the previous condition");
        helper.assertTrue(FlowGraphNbt.read(FlowGraphNbt.write(graph)).equals(graph), "The new branch kind must survive save/load"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 210)
    public static void amplifiedReachCannotHitUntilMaidChasesMovingTarget(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = new EntityMaid(helper.getLevel());
        BlockPos base = helper.absolutePos(new BlockPos(1, 51, 1));
        for (int x = -1; x <= 13; x++) for (int z = -2; z <= 2; z++) {
            helper.getLevel().setBlockAndUpdate(base.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            for (int y = 1; y <= 5; y++) helper.getLevel().setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        maid.setTame(true); maid.setOwnerUUID(owner.player.getUUID()); maid.setInSittingPose(false);
        maid.setPos(base.getX() + .5, base.getY() + 1, base.getZ() + .5); owner.player.setPos(maid.getX(), maid.getY(), maid.getZ());
        maid.getAttribute(net.minecraftforge.common.ForgeMod.ENTITY_REACH.get()).setBaseValue(128);
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD));
        var hostile = new net.minecraft.world.entity.monster.Husk(net.minecraft.world.entity.EntityType.HUSK, helper.getLevel());
        hostile.setNoAi(true); hostile.setNoGravity(true); hostile.setHealth(1); hostile.setPos(maid.getX() + 8, maid.getY(), maid.getZ());
        helper.getLevel().addFreshEntity(hostile); helper.getLevel().addFreshEntity(maid);
        double initialX = maid.getX();
        FlowGraph graph = workGraph(FlowGraph.Kind.ATTACK, Map.of("targetMode", "指定具体目标", "attackTarget", hostile.getUUID().toString(), "radius", "1", "finishMode", "count", "finishCount", "1"));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean(); int[] ticks = {0};
        Runnable cleanup = () -> { FlowRuntime.drop(maid); maid.discard(); hostile.discard(); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                int tick = ++ticks[0];
                if (tick == 5) hostile.setPos(hostile.getX() + 2, hostile.getY(), hostile.getZ());
                if (maid.distanceToSqr(hostile) > 2.25 * 2.25) helper.assertTrue(hostile.isAlive(), "Even 128-block reach must not damage a distant target");
                if (!hostile.isAlive()) {
                    helper.assertTrue(maid.getX() > initialX + 5 && maid.distanceToSqr(hostile) <= 2.25 * 2.25 && maid.hasLineOfSight(hostile), "The maid must physically pursue and approach the moving target before landing melee damage");
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(190, () -> { if (!done.get()) { done.set(true); String evidence = maid.position() + " " + worker(maid); cleanup.run(); helper.fail("Melee pursuit did not finish: " + evidence); } });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void itemTypeMatchingIgnoresDurabilityNameAndEnchantmentsWithoutChangingStacks(GameTestHelper helper) {
        for (var item : List.of(Items.NETHERITE_HOE, Items.NETHERITE_SWORD, Items.BOW, Items.DIAMOND_PICKAXE, Items.SHIELD)) {
            String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item).toString();
            for (int variant = 0; variant < 6; variant++) {
                ItemStack stack = new ItemStack(item);
                if (variant == 1 || variant == 4) stack.setDamageValue(37);
                if (variant == 5) stack.setDamageValue(stack.getMaxDamage() - 1);
                if (variant == 2 || variant == 4) stack.setHoverName(net.minecraft.network.chat.Component.literal("我的自定义工具"));
                if (variant == 3 || variant == 4) stack.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
                var before = stack.save(new CompoundTag());
                helper.assertTrue(FlowItems.matches(stack, id) && stack.save(new CompoundTag()).equals(before), "Type matching must accept every metadata variant without mutating the actual item");
                helper.assertTrue(!FlowItems.matches(stack, "minecraft:stone") && !FlowItems.matches(ItemStack.EMPTY, id), "Different item types and empty stacks must never match");
            }
        }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 50)
    public static void barrelTakeEquipAndStoreAllHoeVariantsPreservesEveryTag(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        BlockPos from = maid.blockPosition().east(), to = maid.blockPosition().west();
        helper.getLevel().setBlockAndUpdate(from, Blocks.BARREL.defaultBlockState()); helper.getLevel().setBlockAndUpdate(to, Blocks.BARREL.defaultBlockState());
        var source = (net.minecraft.world.level.block.entity.BarrelBlockEntity) helper.getLevel().getBlockEntity(from);
        var destination = (net.minecraft.world.level.block.entity.BarrelBlockEntity) helper.getLevel().getBlockEntity(to);
        List<ItemStack> expected = new ArrayList<>();
        for (int variant = 0; variant < 6; variant++) {
            ItemStack stack = new ItemStack(Items.NETHERITE_HOE);
            if (variant == 0 || variant == 4) stack.setDamageValue(37);
            if (variant == 5) stack.setDamageValue(stack.getMaxDamage() - 1);
            if (variant == 1 || variant == 4) stack.setHoverName(net.minecraft.network.chat.Component.literal("命名的下界合金锄"));
            if (variant == 2 || variant == 4) stack.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
            stack.getOrCreateTag().putInt("tlm_test_variant", variant); expected.add(stack.copy()); source.setItem(variant, stack.copy());
        }
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "各种耐久命名附魔取存", List.of(node("start", FlowGraph.Kind.START, Map.of()),
                node("take", FlowGraph.Kind.TAKE, Map.of("target", target(from), "filter", "minecraft:netherite_hoe", "itemCount", "6")),
                node("store", FlowGraph.Kind.STORE, Map.of("target", target(to), "filter", "minecraft:netherite_hoe")), node("end", FlowGraph.Kind.END, Map.of())),
                List.of(edge("start", FlowGraph.Port.NEXT, "take"), edge("take", FlowGraph.Port.NEXT, "store"), edge("store", FlowGraph.Port.NEXT, "end")));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); AtomicBoolean done = new AtomicBoolean(), equipped = new AtomicBoolean();
        Runnable cleanup = () -> { FlowRuntime.drop(maid); owner.close(); };
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                FlowRuntime.tick(maid);
                if (maid.getMainHandItem().is(Items.NETHERITE_HOE)) {
                    helper.assertTrue(expected.stream().anyMatch(original -> ItemStack.isSameItemSameTags(original, maid.getMainHandItem())), "Equipped hoe must keep the selected original metadata"); equipped.set(true);
                }
                if (!FlowLibrary.data(maid).getBoolean("armed")) {
                    helper.assertTrue(equipped.get() && source.isEmpty() && maid.getMainHandItem().isEmpty()
                            && FlowLibrary.data(maid).getString("status").equals("SUCCEEDED"), "All six variants must transfer and finish");
                    for (ItemStack wanted : expected) {
                        int matches = 0;
                        for (int slot = 0; slot < destination.getContainerSize(); slot++) if (ItemStack.isSameItemSameTags(wanted, destination.getItem(slot))) matches += destination.getItem(slot).getCount();
                        helper.assertTrue(matches == 1, "Every durability/name/enchantment/custom tag must remain exactly unchanged, with no duplication");
                    }
                    done.set(true); cleanup.run(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); cleanup.run(); throw error; }
        });
        helper.runAfterDelay(35, () -> { if (!done.get()) { done.set(true); String evidence = FlowLibrary.data(maid).toString(); cleanup.run(); helper.fail("Variant transfer did not finish: " + evidence); } });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void itemSelectionPrefersFewEnchantmentsThenDurabilityAndRandomizesNearTies(GameTestHelper helper) {
        var inventory = new net.minecraftforge.items.ItemStackHandler(6);
        for (int slot = 0; slot < 6; slot++) inventory.setStackInSlot(slot, new ItemStack(Items.NETHERITE_HOE));
        inventory.getStackInSlot(0).setDamageValue(1900);
        inventory.getStackInSlot(1).setDamageValue(100);
        inventory.getStackInSlot(2).setDamageValue(140);
        inventory.getStackInSlot(3).enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
        inventory.getStackInSlot(4).setDamageValue(1500);
        inventory.getStackInSlot(5).setDamageValue(110); inventory.getStackInSlot(5).setHoverName(net.minecraft.network.chat.Component.literal("自定义命名"));
        Set<Integer> seen = new HashSet<>(); var random = net.minecraft.util.RandomSource.create(123456);
        CompoundTag before = inventory.serializeNBT();
        for (int attempt = 0; attempt < 120; attempt++) {
            var choices = FlowItemChoice.order(inventory, "minecraft:netherite_hoe", random); int first = choices.get(0); seen.add(first);
            helper.assertTrue(Set.of(1, 2, 5).contains(first) && choices.get(5) == 3, "Prefer fewest enchantments, then only the highest durability tolerance band");
            helper.assertTrue(choices.size() == 6 && new HashSet<>(choices).size() == 6, "Ranking must neither duplicate nor lose slots");
        }
        helper.assertTrue(seen.equals(Set.of(1, 2, 5)) && inventory.serializeNBT().equals(before), "Near ties must be randomized without changing any item data"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 35)
    public static void damagedNamedEnchantedToolCanGoDirectlyToEmptyHandWithFullBackpack(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = testMaid(helper, owner);
        var backpack = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < backpack.getSlots(); slot++) backpack.insertItem(slot, new ItemStack(Items.STONE, 64), false);
        CompoundTag bagBefore = maid.getMaidInv().serializeNBT();
        BlockPos pos = maid.blockPosition().east(); helper.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) helper.getLevel().getBlockEntity(pos);
        ItemStack tool = new ItemStack(Items.NETHERITE_HOE); tool.setDamageValue(tool.getMaxDamage() - 1);
        tool.setHoverName(net.minecraft.network.chat.Component.literal("濒临损坏的命名锄")); tool.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
        ItemStack expected = tool.copy(); barrel.setItem(0, tool);
        FlowGraph graph = workGraph(FlowGraph.Kind.TAKE, Map.of("target", target(pos), "filter", "minecraft:netherite_hoe"));
        try {
            FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); FlowRuntime.tick(maid);
            helper.assertTrue(barrel.isEmpty() && ItemStack.isSameItemSameTags(expected, maid.getMainHandItem())
                    && maid.getMaidInv().serializeNBT().equals(bagBefore)
                    && FlowLibrary.data(maid).getCompound("execution").getCompound("runner").getString("current").equals("end"),
                    "Even a nearly broken named enchanted tool must acquire into an empty main hand without needing backpack space"); helper.succeed();
        } finally { FlowRuntime.drop(maid); owner.close(); }
    }

    private static FlowGraph workGraph(FlowGraph.Kind kind, Map<String, String> settings) {
        return new FlowGraph(UUID.randomUUID(), "工作结束测试", List.of(node("start", FlowGraph.Kind.START, Map.of()), node("work", kind, settings), node("end", FlowGraph.Kind.END, Map.of())),
                List.of(edge("start", FlowGraph.Port.NEXT, "work"), edge("work", FlowGraph.Port.NEXT, "end")));
    }
    private static EntityMaid testMaid(GameTestHelper helper, ConnectedTestOwner owner) {
        EntityMaid maid = new EntityMaid(helper.getLevel()); maid.setTame(true); maid.setOwnerUUID(owner.player.getUUID()); maid.setNoAi(true); maid.setNoGravity(true);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1)); maid.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        owner.player.setPos(maid.getX(), maid.getY(), maid.getZ()); return maid;
    }
    private static EntityMaid reload(GameTestHelper helper, EntityMaid old) {
        CompoundTag saved = old.saveWithoutId(new CompoundTag()); FlowRuntime.drop(old);
        EntityMaid restored = new EntityMaid(helper.getLevel()); restored.load(saved); restored.setNoAi(true); restored.setNoGravity(true); return restored;
    }
    private static CompoundTag worker(EntityMaid maid) { return FlowLibrary.data(maid).getCompound("execution").getCompound("worker"); }
    private static String target(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    private static FlowGraph.Node node(String id, FlowGraph.Kind kind, Map<String, String> settings) { return new FlowGraph.Node(id, kind, 0, 0, settings); }
    private static FlowGraph.Edge edge(String from, FlowGraph.Port port, String to) { return new FlowGraph.Edge(from, port, to); }
}
