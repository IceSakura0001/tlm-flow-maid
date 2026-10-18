package dev.tlmprogrammable.flow.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.*;
import dev.tlmprogrammable.flow.server.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@GameTestHolder("tlm_programmable")
@PrefixGameTestTemplate(false)
public final class RoadExperimentTests {
    private record Fixture(ConnectedTestOwner owner, EntityMaid maid, BlockPos base, BlockPos wall, BlockPos goal) implements AutoCloseable {
        @Override public void close() { FlowRuntime.drop(maid); maid.discard(); owner.close(); }
    }
    private static Fixture corridor(GameTestHelper helper, boolean gap) { return corridor(helper, gap, 0); }
    private static Fixture corridor(GameTestHelper helper, boolean gap, int elevation) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper); EntityMaid maid = new EntityMaid(helper.getLevel());
        BlockPos base = helper.absolutePos(new BlockPos(1, 71 + elevation, 1));
        for (int x = -2; x <= 6; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 7; y++) {
            var state = y == 0 || z != 0 || y >= 3 ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
            helper.getLevel().setBlockAndUpdate(base.offset(x, y, z), state);
        }
        for (int y = 1; y <= 2; y++) { helper.getLevel().setBlockAndUpdate(base.offset(-2, y, 0), Blocks.BEDROCK.defaultBlockState()); helper.getLevel().setBlockAndUpdate(base.offset(6, y, 0), Blocks.BEDROCK.defaultBlockState()); }
        BlockPos wall = base.offset(2, 1, 0), goal = base.offset(4, 1, 0);
        if (gap) { for (int dy = 1; dy <= 9; dy++) { helper.getLevel().setBlockAndUpdate(wall.below(dy), Blocks.AIR.defaultBlockState()); helper.getLevel().setBlockAndUpdate(wall.east().below(dy), Blocks.AIR.defaultBlockState()); } }
        else { helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState()); helper.getLevel().setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState()); }
        maid.setTame(true); maid.setOwnerUUID(owner.player.getUUID()); maid.setInSittingPose(false);
        maid.setPos(base.getX() + .5, base.getY() + 1, base.getZ() + .5); owner.player.setPos(maid.getX(), maid.getY(), maid.getZ());
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 8)); helper.getLevel().addFreshEntity(maid);
        FlowGraph graph = FlowGraph.empty("实验隔离待命"); FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph); FlowRuntime.cancel(maid);
        return new Fixture(owner, maid, base, wall, goal);
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void protectedColumnCoversAllYAndRulesSurviveSerialization(GameTestHelper helper) {
        try (Fixture f = corridor(helper, false)) {
            CompoundTag request = new CompoundTag(); request.putString("action", "area"); request.putString("a", f.wall().getX() + ",0," + f.wall().getZ()); request.putString("b", f.wall().getX() + ",1," + f.wall().getZ());
            RoadRules.get(f.owner().player).change(f.owner().player, request);
            RoadRules reloaded = RoadRules.load(RoadRules.get(f.owner().player).save(new CompoundTag()));
            helper.assertTrue(reloaded.forbids(f.owner().player, f.wall().atY(-60), "minecraft:stone") && reloaded.forbids(f.owner().player, f.wall().atY(300), "minecraft:stone"), "Two X/Z corners must prohibit every vertical height");
            helper.assertTrue(!RoadPlan.breakAllowed(f.maid(), f.owner().player, f.wall()), "Protected area must override a usable tool");
            helper.assertTrue(!f.maid().destroyBlock(f.wall()) && helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE), "The original maid destruction hook must also respect protected areas");
            var plan = new RoadPlan(f.maid(), f.owner().player, f.goal()); for (int i = 0; i < 50 && !plan.tick(); i++) { }
            helper.assertTrue(plan.result().isEmpty(), "Planner may not route through prohibited columns in a sealed corridor"); helper.succeed();
        }
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void blockDenyListAndContainersAreNeverDemolished(GameTestHelper helper) {
        try (Fixture f = corridor(helper, false)) {
            CompoundTag request = new CompoundTag(); request.putString("action", "block"); request.putString("block", "minecraft:stone"); RoadRules.get(f.owner().player).change(f.owner().player, request);
            helper.assertTrue(!RoadPlan.breakAllowed(f.maid(), f.owner().player, f.wall()), "Denied block type must be rejected");
            helper.getLevel().setBlockAndUpdate(f.wall(), Blocks.CHEST.defaultBlockState());
            helper.assertTrue(!RoadPlan.breakAllowed(f.maid(), f.owner().player, f.wall()), "Block entities are never automatically demolished"); helper.succeed();
        }
    }
    @GameTest(template = "empty", timeoutTicks = 70)
    public static void noDestructionBeforeConsentAndRefusalStopsPlan(GameTestHelper helper) {
        Fixture f = corridor(helper, false); RoadRun run = new RoadRun(f.maid(), f.owner().player, f.goal()); AtomicBoolean done = new AtomicBoolean(); int[] waiting = {0};
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                run.tick(); helper.assertTrue(helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE), "No block can change before consent");
                if (run.requestId() != null && ++waiting[0] >= 10) {
                    CompoundTag armed = FlowLibrary.data(f.maid()); armed.putBoolean("armed", true); FlowLibrary.put(f.maid(), armed);
                    RoadRun.answer(f.owner().player, f.maid(), run.requestId(), false);
                    helper.assertTrue(run.tick().state() == FlowRunner.WorkState.FAILED && helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE), "Refusal must stop the exact plan without demolition");
                    done.set(true); run.cancel(); f.close(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); run.cancel(); f.close(); throw error; }
        });
        helper.runAfterDelay(55, () -> { if (!done.get()) { done.set(true); run.cancel(); f.close(); helper.fail("No consent proposal was produced"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 220)
    public static void approvedExactPlanBreaksTwoBlocksAndWalksThrough(GameTestHelper helper) {
        Fixture f = corridor(helper, false); RoadRun run = new RoadRun(f.maid(), f.owner().player, f.goal()); AtomicBoolean done = new AtomicBoolean(), approved = new AtomicBoolean();
        // Direct RoadRun with a real moving brain. Arm only during the owner reply, then leave the ordinary flow idle.
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                var result = run.tick();
                if (!approved.get() && run.requestId() != null) {
                    CompoundTag armed = FlowLibrary.data(f.maid()); armed.putBoolean("armed", true); FlowLibrary.put(f.maid(), armed);
                    RoadRun.answer(f.owner().player, f.maid(), run.requestId(), true); armed.putBoolean("armed", false); FlowLibrary.put(f.maid(), armed); approved.set(true);
                }
                if (result.state() == FlowRunner.WorkState.FAILED) helper.fail(result.message());
                if (result.state() == FlowRunner.WorkState.COMPLETE) {
                    helper.assertTrue(approved.get() && helper.getLevel().getBlockState(f.wall()).isAir() && helper.getLevel().getBlockState(f.wall().above()).isAir(), "One exact approval must cover both planned blocks");
                    done.set(true); run.cancel(); f.close(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); run.cancel(); f.close(); throw error; }
        });
        helper.runAfterDelay(200, () -> { if (!done.get()) { done.set(true); run.cancel(); f.close(); helper.fail("Approved local road did not finish"); } });
    }
    private static void arm(Fixture f, boolean value) { CompoundTag data = FlowLibrary.data(f.maid()); data.putBoolean("armed", value); FlowLibrary.put(f.maid(), data); }
    private static RoadRun proposal(Fixture f) {
        RoadRun run = new RoadRun(f.maid(), f.owner().player, f.goal());
        for (int i = 0; i < 60 && run.requestId() == null; i++) run.tick(); return run;
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void permitCannotBeReplayedOrUsedByAnotherOwner(GameTestHelper helper) {
        try (Fixture f = corridor(helper, false); ConnectedTestOwner stranger = new ConnectedTestOwner(helper)) {
            RoadRun run = proposal(f); helper.assertTrue(run.requestId() != null, "A demolition proposal must exist"); UUID ticket = run.requestId(); arm(f, true);
            boolean rejected = false; try { RoadRun.answer(stranger.player, f.maid(), ticket, true); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected && helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE), "Only the owner may approve");
            RoadRun.answer(f.owner().player, f.maid(), ticket, true);
            rejected = false; try { RoadRun.answer(f.owner().player, f.maid(), ticket, true); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "A decision packet must not be replayable"); run.cancel(); helper.succeed();
        }
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void changedPlannedBlockInvalidatesApprovedDemolition(GameTestHelper helper) {
        try (Fixture f = corridor(helper, false)) {
            RoadRun run = proposal(f); arm(f, true); RoadRun.answer(f.owner().player, f.maid(), run.requestId(), true);
            helper.getLevel().setBlockAndUpdate(f.wall().above(), Blocks.OAK_PLANKS.defaultBlockState());
            helper.assertTrue(run.tick().state() == FlowRunner.WorkState.FAILED && helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE)
                    && helper.getLevel().getBlockState(f.wall().above()).is(Blocks.OAK_PLANKS), "Changed terrain invalidates the entire old proposal before any demolition"); run.cancel(); helper.succeed();
        }
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void newDenyRuleOverridesExistingApproval(GameTestHelper helper) {
        try (Fixture f = corridor(helper, false)) {
            RoadRun run = proposal(f); arm(f, true); RoadRun.answer(f.owner().player, f.maid(), run.requestId(), true);
            CompoundTag rule = new CompoundTag(); rule.putString("action", "block"); rule.putString("block", "minecraft:stone"); RoadRules.get(f.owner().player).change(f.owner().player, rule);
            helper.assertTrue(run.tick().state() == FlowRunner.WorkState.FAILED && helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE), "New blacklist rules must override previous approval immediately"); run.cancel(); helper.succeed();
        }
    }
    @GameTest(template = "empty", timeoutTicks = 220)
    public static void cobblestoneBridgeConsumesInventoryAndRealMaidWalksAcross(GameTestHelper helper) {
        Fixture f = corridor(helper, true); RoadRun run = new RoadRun(f.maid(), f.owner().player, f.goal()); AtomicBoolean done = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                var result = run.tick();
                helper.assertTrue(run.requestId() == null, "A placement-only bridge must not request demolition permission");
                if (result.state() == FlowRunner.WorkState.FAILED) helper.fail(result.message());
                if (result.state() == FlowRunner.WorkState.COMPLETE) {
                    helper.assertTrue(helper.getLevel().getBlockState(f.wall().below()).is(Blocks.COBBLESTONE)
                            && helper.getLevel().getBlockState(f.wall().east().below()).is(Blocks.COBBLESTONE)
                            && f.maid().getMaidInv().getStackInSlot(0).getCount() == 6
                            && f.maid().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(f.goal())) < 1, "Build two supports from real inventory and physically cross the bridge; first=" + helper.getLevel().getBlockState(f.wall().below()) + "; second=" + helper.getLevel().getBlockState(f.wall().east().below()) + "; inventory=" + f.maid().getMaidInv().serializeNBT() + "; pos=" + f.maid().position());
                    done.set(true); run.cancel(); f.close(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); run.cancel(); f.close(); throw error; }
        });
        helper.runAfterDelay(200, () -> { if (!done.get()) { done.set(true); run.cancel(); f.close(); helper.fail("Cobblestone bridge execution did not finish"); } });
    }
    @GameTest(template = "empty", timeoutTicks = 650)
    public static void enabledMoveBuildsOnlyAfterThreeUnsuccessfulNavigationAttempts(GameTestHelper helper) {
        Fixture f = corridor(helper, false); AtomicBoolean done = new AtomicBoolean(), built = new AtomicBoolean(), consent = new AtomicBoolean();
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "三次失败后筑路", List.of(new FlowGraph.Node("start", FlowGraph.Kind.START, 0, 0, Map.of()),
                new FlowGraph.Node("move", FlowGraph.Kind.MOVE, 0, 100, Map.of("targetMode", "坐标（高级）", "target", f.goal().getX() + "," + f.goal().getY() + "," + f.goal().getZ(), "roadEnabled", "true")),
                new FlowGraph.Node("end", FlowGraph.Kind.END, 0, 200, Map.of())), List.of(new FlowGraph.Edge("start", FlowGraph.Port.NEXT, "move"), new FlowGraph.Edge("move", FlowGraph.Port.NEXT, "end")));
        CompoundTag enable = new CompoundTag(); enable.putString("action", "settings"); enable.putBoolean("roadEnabled", true); RoadRules.get(f.owner().player).change(f.owner().player, enable);
        FlowLibrary.save(f.maid(), graph, 1); FlowRuntime.select(f.maid(), graph);
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                CompoundTag library = FlowLibrary.data(f.maid()); CompoundTag worker = library.getCompound("execution").getCompound("worker");
                UUID request = FlowRuntime.pendingRoadRequest(f.maid());
                if (!consent.get() && request != null) {
                    helper.assertTrue(worker.getInt("roadAttempts") >= 3 && helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE), "An approval request must occur only after three unsuccessful attempts and before demolition");
                    RoadRun.answer(f.owner().player, f.maid(), request, true); consent.set(true);
                }
                if (helper.getLevel().getBlockState(f.wall()).isAir()) {
                    helper.assertTrue(consent.get(), "Integrated MOVE must not demolish without the owner approval"); built.set(true);
                }
                if (!library.getBoolean("armed")) {
                    helper.assertTrue(built.get() && library.getString("status").equals("SUCCEEDED"), "A real enabled MOVE must build after failure and complete through the ordinary flow runner: " + library);
                    done.set(true); f.close(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); f.close(); throw error; }
        });
        helper.runAfterDelay(620, () -> { if (!done.get()) { done.set(true); String evidence = FlowLibrary.data(f.maid()).toString(); f.close(); helper.fail("Integrated road movement did not finish: " + evidence); } });
    }

    @GameTest(template = "empty", timeoutTicks = 750)
    public static void takeUsesSharedRoadWithoutMoveNode(GameTestHelper helper) { sharedWorkRoad(helper, FlowGraph.Kind.TAKE); }
    @GameTest(template = "empty", timeoutTicks = 750)
    public static void storeUsesSharedRoadWithoutMoveNode(GameTestHelper helper) { sharedWorkRoad(helper, FlowGraph.Kind.STORE); }
    @GameTest(template = "empty", timeoutTicks = 750)
    public static void harvestUsesSharedRoadWithoutMoveNode(GameTestHelper helper) { sharedWorkRoad(helper, FlowGraph.Kind.HARVEST); }
    private static void sharedWorkRoad(GameTestHelper helper, FlowGraph.Kind kind) {
        Fixture f = corridor(helper, false, 80 + kind.ordinal() * 12);
        BlockPos work = f.base().offset(4, 1, 0);
        helper.getLevel().setBlockAndUpdate(work, (kind == FlowGraph.Kind.HARVEST ? Blocks.EMERALD_BLOCK : Blocks.BARREL).defaultBlockState());
        if (kind != FlowGraph.Kind.HARVEST) {
            var container = (net.minecraft.world.Container) helper.getLevel().getBlockEntity(work);
            if (kind == FlowGraph.Kind.TAKE) container.setItem(0, new ItemStack(Items.APPLE));
            else f.maid().getMaidInv().setStackInSlot(1, new ItemStack(Items.APPLE));
        }
        CompoundTag enable = new CompoundTag(); enable.putString("action", "settings"); enable.putBoolean("roadEnabled", true);
        RoadRules.get(f.owner().player).change(f.owner().player, enable);
        Map<String, String> settings = new HashMap<>();
        settings.put("target", work.getX() + "," + work.getY() + "," + work.getZ());
        settings.put("filter", kind == FlowGraph.Kind.HARVEST ? "minecraft:emerald_block" : "minecraft:apple");
        settings.put("itemCount", "1"); settings.put("finishMode", "count"); settings.put("finishCount", "1");
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "通用工作筑路", List.of(new FlowGraph.Node("start", FlowGraph.Kind.START, 0, 0, Map.of()),
                new FlowGraph.Node("work", kind, 0, 100, settings), new FlowGraph.Node("end", FlowGraph.Kind.END, 0, 200, Map.of())),
                List.of(new FlowGraph.Edge("start", FlowGraph.Port.NEXT, "work"), new FlowGraph.Edge("work", FlowGraph.Port.NEXT, "end")));
        FlowLibrary.save(f.maid(), graph, 1); FlowRuntime.select(f.maid(), graph);
        AtomicBoolean done = new AtomicBoolean(), consent = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                UUID ticket = FlowRuntime.pendingRoadRequest(f.maid());
                if (ticket != null && !consent.get()) {
                    helper.assertTrue(helper.getLevel().getBlockState(f.wall()).is(Blocks.STONE), "No work action may demolish before consent");
                    helper.assertTrue(FlowLibrary.data(f.maid()).getCompound("execution").getCompound("worker").getInt("roadAttempts") >= 3, "Work stance must fail three attempts before road planning");
                    RoadRun.answer(f.owner().player, f.maid(), ticket, true); consent.set(true);
                }
                var library = FlowLibrary.data(f.maid());
                if (!library.getBoolean("armed")) {
                    helper.assertTrue(consent.get() && library.getString("status").equals("SUCCEEDED"), "Shared " + kind + " must finish after the road: " + library);
                    if (kind == FlowGraph.Kind.HARVEST) helper.assertTrue(helper.getLevel().getBlockState(work).isAir(), "Harvest must execute after movement");
                    else {
                        var container = (net.minecraft.world.Container) helper.getLevel().getBlockEntity(work);
                        helper.assertTrue(kind == FlowGraph.Kind.TAKE ? container.isEmpty() : container.getItem(0).is(Items.APPLE), "The actual transfer must execute after movement");
                    }
                    done.set(true); f.close(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); f.close(); throw error; }
        });
        helper.runAfterDelay(720, () -> { if (!done.get()) { done.set(true); String evidence = FlowLibrary.data(f.maid()).toString(); f.close(); helper.fail("Shared " + kind + " timed out: " + evidence); } });
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void globalRoadDefaultAndPersistence(GameTestHelper helper) {
        try (Fixture f = corridor(helper, false)) {
            RoadRules rules = RoadRules.get(f.owner().player);
            helper.assertTrue(!rules.enabled(f.owner().player), "Road construction must be disabled by default");
            CompoundTag enable = new CompoundTag(); enable.putString("action", "settings"); enable.putBoolean("roadEnabled", true); rules.change(f.owner().player, enable);
            helper.assertTrue(RoadRules.load(rules.save(new CompoundTag())).enabled(f.owner().player), "Global owner settings must survive reload independently of task nodes");
            enable.putBoolean("roadEnabled", false); rules.change(f.owner().player, enable);
            helper.assertTrue(!rules.enabled(f.owner().player), "Owner can disable generic roads"); helper.succeed();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 650)
    public static void delegatedWalkTargetUsesRoadAndResumesNativeMode(GameTestHelper helper) {
        Fixture f = corridor(helper, false, 210);
        CompoundTag enable = new CompoundTag(); enable.putString("action", "settings"); enable.putBoolean("roadEnabled", true); RoadRules.get(f.owner().player).change(f.owner().player, enable);
        var idle = com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getIdleTask();
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "原生工作通用移动", List.of(new FlowGraph.Node("start", FlowGraph.Kind.START, 0, 0, Map.of()),
                new FlowGraph.Node("native", FlowGraph.Kind.NATIVE_WORK, 0, 100, Map.of("nativeTask", idle.getUid().toString(), "finishDuration", "60")),
                new FlowGraph.Node("end", FlowGraph.Kind.END, 0, 200, Map.of())), List.of(new FlowGraph.Edge("start", FlowGraph.Port.NEXT, "native"), new FlowGraph.Edge("native", FlowGraph.Port.NEXT, "end")));
        FlowLibrary.save(f.maid(), graph, 1); FlowRuntime.select(f.maid(), graph);
        AtomicBoolean done = new AtomicBoolean(), consent = new AtomicBoolean(), suspended = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                UUID ticket = FlowRuntime.pendingRoadRequest(f.maid());
                if (ticket != null && !consent.get()) { RoadRun.answer(f.owner().player, f.maid(), ticket, true); consent.set(true); }
                if (consent.get() && f.maid().getTask().getUid().equals(FlowWorkTask.UID)) suspended.set(true);
                if (f.maid().getTask().getUid().equals(idle.getUid())) {
                    if (!consent.get()) f.maid().getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                            new net.minecraft.world.entity.ai.memory.WalkTarget(net.minecraft.world.phys.Vec3.atBottomCenterOf(f.goal()), 1f, 0));
                    if (consent.get() && suspended.get() && f.maid().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(f.goal())) < 1) {
                        helper.assertTrue(helper.getLevel().getBlockState(f.wall()).isAir(), "Native movement must use the approved road");
                        done.set(true); f.close(); helper.succeed();
                    }
                }
            } catch (RuntimeException | Error error) { done.set(true); f.close(); throw error; }
        });
        helper.runAfterDelay(620, () -> { if (!done.get()) { done.set(true); String evidence = FlowLibrary.data(f.maid()).toString(); f.close(); helper.fail("Delegated movement did not resume: " + evidence); } });
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void floatingOreColumnBuildsStairsThenMinesWithRealMovement(GameTestHelper helper) {
        ConnectedTestOwner owner = new ConnectedTestOwner(helper);
        EntityMaid maid = new EntityMaid(helper.getLevel());
        BlockPos floor = helper.absolutePos(new BlockPos(1, 170, 1));
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
            helper.getLevel().setBlockAndUpdate(floor.offset(x, 0, z), Blocks.BEDROCK.defaultBlockState());
            // The GameTest world persists between server launches; clear old scaffolding.
            for (int y = 1; y <= 16; y++) helper.getLevel().setBlockAndUpdate(floor.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        for (int y = 7; y <= 10; y++) helper.getLevel().setBlockAndUpdate(floor.above(y), Blocks.EMERALD_ORE.defaultBlockState());
        maid.setTame(true); maid.setOwnerUUID(owner.player.getUUID()); maid.setInSittingPose(false);
        maid.setPos(floor.getX() + 3.5, floor.getY() + 1, floor.getZ() + .5); owner.player.setPos(maid.getX(), maid.getY(), maid.getZ());
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 64)); helper.getLevel().addFreshEntity(maid);
        CompoundTag enable = new CompoundTag(); enable.putString("action", "settings"); enable.putBoolean("roadEnabled", true); RoadRules.get(owner.player).change(owner.player, enable);
        // The player selects the ground work centre, not the inaccessible ore's coordinates.
        BlockPos target = floor.above();
        FlowGraph graph = new FlowGraph(UUID.randomUUID(), "悬空矿柱", List.of(new FlowGraph.Node("start", FlowGraph.Kind.START, 0, 0, Map.of()),
                new FlowGraph.Node("mine", FlowGraph.Kind.HARVEST, 0, 100, Map.of("target", target.getX() + "," + target.getY() + "," + target.getZ(), "filter", "minecraft:emerald_ore", "finishMode", "count", "finishCount", "4", "radius", "1")),
                new FlowGraph.Node("end", FlowGraph.Kind.END, 0, 200, Map.of())), List.of(new FlowGraph.Edge("start", FlowGraph.Port.NEXT, "mine"), new FlowGraph.Edge("mine", FlowGraph.Port.NEXT, "end")));
        FlowLibrary.save(maid, graph, 0); FlowRuntime.select(maid, graph);
        AtomicBoolean done = new AtomicBoolean(); double[] highest = {maid.getY()};
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                highest[0] = Math.max(highest[0], maid.getY());
                helper.assertTrue(FlowRuntime.pendingRoadRequest(maid) == null, "Open-air scaffolding should not need demolition or remove ore as a road obstacle");
                CompoundTag library = FlowLibrary.data(maid);
                if (!library.getBoolean("armed")) {
                    helper.assertTrue(library.getString("status").equals("SUCCEEDED"), "Floating ore harvesting must finish: " + library);
                    for (int y = 7; y <= 10; y++) helper.assertTrue(helper.getLevel().getBlockState(floor.above(y)).isAir(), "All suspended ores must be mined");
                    helper.assertTrue(highest[0] > floor.getY() + 3 && maid.getMaidInv().getStackInSlot(0).getCount() < 64, "Maid must physically climb built stairs and consume real cobblestone");
                    done.set(true); FlowRuntime.drop(maid); maid.discard(); owner.close(); helper.succeed();
                }
            } catch (RuntimeException | Error error) { done.set(true); FlowRuntime.drop(maid); maid.discard(); owner.close(); throw error; }
        });
        helper.runAfterDelay(970, () -> { if (!done.get()) { done.set(true); String evidence = FlowLibrary.data(maid).toString() + " position=" + maid.position(); FlowRuntime.drop(maid); maid.discard(); owner.close(); helper.fail("Floating mining timed out: " + evidence); } });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void edgeGuardLimitsRealMovementAndRetainsSupport(GameTestHelper helper) {
        try (Fixture f = corridor(helper, true, 235)) {
            var maid = f.maid(); maid.setNoAi(true);
            BlockPos feet = f.base().above();
            maid.setPos(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            CompoundTag library = FlowLibrary.data(maid); library.putBoolean("armed", true); FlowLibrary.put(maid, library);
            maid.setOnGround(true); double initial = maid.getX();
            for (int n = 0; n < 50; n++) {
                maid.setOnGround(true); double before = maid.getX();
                maid.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(.9, 0, 0));
                helper.assertTrue(maid.getX() - before <= .161, "Actual Entity.move must cap flow movement even if AI requests excessive speed");
            }
            helper.assertTrue(maid.getX() > initial + .5 && maid.getX() < f.wall().getX() + .14,
                    "Maid should advance to the cliff, then brake before losing support"); helper.succeed();
        }
    }
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void edgeGuardAllowsJumpAndOneBlockDescentAndLeavesInactiveMaidAlone(GameTestHelper helper) {
        try (Fixture f = corridor(helper, true, 250)) {
            var maid = f.maid(); maid.setNoAi(true);
            maid.setPos(f.wall().getX() - .03, f.wall().getY(), f.wall().getZ() + .5); maid.setOnGround(true);
            CompoundTag library = FlowLibrary.data(maid); library.putBoolean("armed", true); FlowLibrary.put(maid, library);
            var falling = new net.minecraft.world.phys.Vec3(.4, -.08, 0);
            var safe = FlowMovementSafety.limit(maid, net.minecraft.world.entity.MoverType.SELF, falling);
            helper.assertTrue(safe.x <= .08001 && safe.y == falling.y, "Cliff braking must preserve gravity");
            helper.getLevel().setBlockAndUpdate(f.wall().below(2), Blocks.COBBLESTONE.defaultBlockState());
            var down = FlowMovementSafety.limit(maid, net.minecraft.world.entity.MoverType.SELF, falling);
            helper.assertTrue(down.x > 0, "Normal one-block descent must remain possible");
            maid.setOnGround(false);
            var jump = FlowMovementSafety.limit(maid, net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(.3, .42, 0));
            helper.assertTrue(jump.x > 0 && jump.y == .42, "Jumping keeps upward velocity and horizontal movement");
            library.putBoolean("armed", false); FlowLibrary.put(maid, library);
            helper.assertTrue(FlowMovementSafety.limit(maid, net.minecraft.world.entity.MoverType.SELF, falling).equals(falling), "Inactive or ordinary maid movement must not be changed");
            helper.assertTrue(FlowMovementSafety.limit(maid, net.minecraft.world.entity.MoverType.PISTON, falling).equals(falling), "External piston movement must not be intercepted"); helper.succeed();
        }
    }

}
