package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import dev.tlmprogrammable.flow.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.items.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Server-only bounded execution. No loaded-chunk forcing, sleeps or client world mutations. */
public final class FlowRuntime {
    private static final Map<EntityMaid, Session> SESSIONS = new WeakHashMap<>();
    private record PickupBefore(String item, int count) { }
    private static final Map<EntityMaid, PickupBefore> PICKUPS = new WeakHashMap<>();
    private static final Set<FlowGraph.Kind> SUPPORTED = EnumSet.of(FlowGraph.Kind.START, FlowGraph.Kind.END,
            FlowGraph.Kind.MOVE, FlowGraph.Kind.STORE, FlowGraph.Kind.TAKE, FlowGraph.Kind.ATTACK, FlowGraph.Kind.HARVEST, FlowGraph.Kind.PATROL, FlowGraph.Kind.NATIVE_WORK, FlowGraph.Kind.FEED, FlowGraph.Kind.BREED,
            FlowGraph.Kind.BRANCH, FlowGraph.Kind.TRIGGER, FlowGraph.Kind.END_CONDITION, FlowGraph.Kind.LOOP_START, FlowGraph.Kind.LOOP_END);
    private record OpenedContainer(BaseContainerBlockEntity container, ServerPlayer owner, long until) { }
    private static final Map<EntityMaid, OpenedContainer> OPENED = new WeakHashMap<>();
    private FlowRuntime() { }
    public static UUID pendingRoadRequest(EntityMaid maid) {
        Session session = SESSIONS.get(maid); return session == null || session.road == null ? null : session.road.requestId();
    }
    public static boolean ownsMovement(EntityMaid maid) {
        Session session = SESSIONS.get(maid);
        return FlowWorkTask.UID.equals(maid.getTask().getUid()) && FlowLibrary.has(maid) || session != null && session.road != null;
    }
    private static void closeContainer(EntityMaid maid) {
        OpenedContainer open = OPENED.remove(maid);
        if (open != null && !open.container().isRemoved()) open.container().stopOpen(open.owner());
    }
    public static void register() {
        FlowLibrary.registerTransforms(); RoadRules.register();
        MinecraftForge.EVENT_BUS.addListener(FlowRuntime::living);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, FlowRuntime::killed);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, FlowRuntime::born);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, FlowRuntime::pickupBefore);
        MinecraftForge.EVENT_BUS.addListener(FlowRuntime::pickupAfter);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> { for (EntityMaid maid : new ArrayList<>(OPENED.keySet())) closeContainer(maid); SESSIONS.clear(); PICKUPS.clear(); RoadRun.clear(); });
    }
    private static Session active(EntityMaid maid) {
        if (maid.level().isClientSide || maid.guiOpening || (!FlowWorkTask.UID.equals(maid.getTask().getUid()) && !managesNative(maid))) return null;
        CompoundTag library = FlowLibrary.data(maid);
        if (!library.getBoolean("armed") || library.getLong("editorUntil") > maid.level().getGameTime()) return null;
        Session session = SESSIONS.get(maid);
        return session != null && session.nodeId.equals(session.runner.currentNode()) ? session : null;
    }
    private static void born(net.minecraftforge.event.entity.living.BabyEntitySpawnEvent event) {
        if (event.getChild() == null) return;
        for (var entry : new ArrayList<>(SESSIONS.entrySet())) {
            EntityMaid maid = entry.getKey(); Session session = active(maid);
            if (session == null || session.current() == null || session.current().kind() != FlowGraph.Kind.BREED) continue;
            var parents = session.nodeData.getCompound("bredParents");
            if (!parents.contains(event.getParentA().getUUID().toString()) || !parents.contains(event.getParentB().getUUID().toString())) continue;
            parents.remove(event.getParentA().getUUID().toString()); parents.remove(event.getParentB().getUUID().toString());
            session.nodeData.put("bredParents", parents);
            session.nodeData.putInt("finishedTargets", session.nodeData.getInt("finishedTargets") + 1); session.persist(maid);
        }
    }
    private static void killed(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityMaid victim) { closeContainer(victim); drop(victim); }
        if (!(event.getSource().getEntity() instanceof EntityMaid maid)) return;
        Session session = active(maid); if (session == null) return;
        FlowGraph.Node node = session.current();
        if (node == null || (node.kind() != FlowGraph.Kind.ATTACK && node.kind() != FlowGraph.Kind.PATROL && node.kind() != FlowGraph.Kind.NATIVE_WORK)) return;
        if (session.matchesEntity(maid, node, event.getEntity()) && (node.kind() == FlowGraph.Kind.NATIVE_WORK || session.area(maid, node).contains(event.getEntity().position()))) {
            session.nodeData.putInt("finishedTargets", session.nodeData.getInt("finishedTargets") + 1);
            session.persist(maid);
        }
    }
    private static void pickupBefore(MaidPickupEvent.ItemResultPre event) {
        if (event.isSimulate()) return;
        EntityMaid maid = event.getMaid(); Session session = active(maid);
        if (session == null || session.current() == null || !FlowFinishRule.applies(session.current().kind())) return;
        String id = ForgeRegistries.ITEMS.getKey(event.getEntityItem().getItem().getItem()).toString();
        PICKUPS.put(maid, new PickupBefore(id, count(maid.getAvailableInv(false), id)));
    }
    private static void pickupAfter(MaidPickupEvent.ItemResultPost event) {
        EntityMaid maid = event.getMaid(); PickupBefore before = PICKUPS.remove(maid); Session session = active(maid);
        if (before == null || session == null || session.current() == null) return;
        FlowGraph.Node node = session.current();
        if (!FlowFinishRule.applies(node.kind()) || !FlowFinishRule.mode(node).equals("loot")
                || !before.item().equals(node.setting("finishItem", ""))) return;
        // TLM 1.5.3's post stack can become AIR on a complete insertion. Measure the actual inventory delta instead.
        int added = Math.max(0, count(maid.getAvailableInv(false), before.item()) - before.count());
        session.nodeData.putInt("finishedLoot", session.nodeData.getInt("finishedLoot") + added); session.persist(maid);
    }
    private static void living(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid) || maid.level().isClientSide) return;
        OpenedContainer open = OPENED.get(maid);
        if (open != null && (maid.level().getGameTime() >= open.until() || maid.isDeadOrDying())) closeContainer(maid);
        if (!FlowLibrary.has(maid)) return;
        CompoundTag data = FlowLibrary.data(maid);
        long now = maid.level().getGameTime();
        boolean allowed = !maid.guiOpening && data.getLong("editorUntil") <= now
                && maid.getOwner() instanceof ServerPlayer owner && owner.level() == maid.level();
        String delegated = data.getString("delegate");
        if (data.getBoolean("armed") && !delegated.isEmpty()) {
            boolean flowMode = FlowWorkTask.UID.equals(maid.getTask().getUid());
            if (!flowMode && !maid.getTask().getUid().toString().equals(delegated)) { cancel(maid); return; }
            if (!allowed) {
                if (!flowMode && !maid.guiOpening) maid.setTask(TaskManager.findTask(FlowWorkTask.UID).orElseThrow());
                return;
            }
            Session running = SESSIONS.get(maid);
            if (running != null && running.road != null) {
                // LivingTick is outside the brain behavior iteration. Suspend the native work
                // mode here so its work/follow AI cannot overwrite the approved construction path.
                if (!flowMode) maid.setTask(TaskManager.findTask(FlowWorkTask.UID).orElseThrow());
                tick(maid); return;
            }
            if (flowMode) {
                try { maid.setTask(NativeWorkTasks.find(delegated)); }
                catch (RuntimeException exception) { cancel(maid); if (maid.getOwner() instanceof ServerPlayer owner) owner.sendSystemMessage(Component.literal("原生工作已停止：" + exception.getMessage())); }
                return;
            }
            maid.getBrain().setActiveActivityIfPossible(Activity.WORK);
            tick(maid); return;
        }
        if (data.getLong("editorUntil") > now) {
            // The owner is editing this maid's flow. Idle random walking can move the maid beyond the
            // 16 block management range, which used to expire the editor lease and spam the chat.
            // Freezing the maid for the duration of the lease keeps the editing session stable.
            if (maid.getNavigation().isInProgress()) maid.getNavigation().stop();
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        if (!FlowWorkTask.UID.equals(maid.getTask().getUid())) {
            if (SESSIONS.containsKey(maid) || data.getBoolean("armed")) cancel(maid);
            return;
        }
        if (data.getBoolean("armed") && !maid.guiOpening && data.getLong("editorUntil") <= now)
            maid.getBrain().setActiveActivityIfPossible(Activity.WORK);
    }
    public static boolean managesNative(EntityMaid maid) {
        if (!FlowLibrary.has(maid)) return false;
        CompoundTag data = FlowLibrary.data(maid);
        return data.getBoolean("armed") && !data.getString("delegate").isEmpty()
                && (FlowWorkTask.UID.equals(maid.getTask().getUid()) || maid.getTask().getUid().toString().equals(data.getString("delegate")));
    }
    public static void validate(FlowGraph graph) {
        graph.validateExecutable();
        for (FlowGraph.Node node : graph.nodes()) {
            if (!SUPPORTED.contains(node.kind())) throw new IllegalArgumentException(node.kind().label() + "模块尚未接入执行器，请先移除后再运行");
            FlowFinishRule.validate(node);
            FlowItemRule.validate(node);
            if (node.kind() == FlowGraph.Kind.FEED || node.kind() == FlowGraph.Kind.BREED) {
                ResourceLocation animal = ResourceLocation.tryParse(node.setting("animalType", ""));
                ResourceLocation food = ResourceLocation.tryParse(node.setting("filter", ""));
                if (animal == null || !ForgeRegistries.ENTITY_TYPES.containsKey(animal)) throw new IllegalArgumentException("饲养/繁殖：请选择动物类型");
                if (food == null || !ForgeRegistries.ITEMS.containsKey(food) || food.toString().equals("minecraft:air")) throw new IllegalArgumentException("饲养/繁殖：请选择食物");
                if (node.settings().containsKey("targetX") || !node.setting("target", "").isBlank()) position(node);
            }
            if (node.kind().family() == FlowGraph.Family.CONDITION) {
                String condition = node.setting("conditionType", node.kind() == FlowGraph.Kind.LOOP_END ? "loop_count" : "always");
                if (!List.of("always", "never", "day", "night", "owner_near", "has_item", "loop_count").contains(condition))
                    throw new IllegalArgumentException("未知判断条件");
                if (condition.equals("loop_count")) {
                    try { int count = Integer.parseInt(node.setting("loopCount", "3")); if (count < 1 || count > 1000) throw new NumberFormatException(); }
                    catch (NumberFormatException error) { throw new IllegalArgumentException("循环次数须为1～1000的整数"); }
                }
                if (condition.equals("has_item")) {
                    ResourceLocation item = ResourceLocation.tryParse(node.setting("filter", ""));
                    if (item == null || !ForgeRegistries.ITEMS.containsKey(item) || item.toString().equals("minecraft:air"))
                        throw new IllegalArgumentException("背包物品判断必须指定有效物品");
                }
            }
            if (node.kind() == FlowGraph.Kind.NATIVE_WORK) {
                NativeWorkTasks.find(node.setting("nativeTask", ""));
                if (FlowFinishRule.mode(node).equals("count")) {
                    ResourceLocation id = ResourceLocation.tryParse(node.setting("filter", ""));
                    if (id == null || !ForgeRegistries.ENTITY_TYPES.containsKey(id)) throw new IllegalArgumentException("原生工作击杀目标：请选择实体类型");
                }
            }
            if (FlowFinishRule.applies(node.kind()) && (FlowFinishRule.mode(node).equals("loot") || FlowFinishRule.mode(node).equals("inventory"))) {
                ResourceLocation item = ResourceLocation.tryParse(node.setting("finishItem", ""));
                if (item == null || !ForgeRegistries.ITEMS.containsKey(item) || item.equals(new ResourceLocation("minecraft:air")))
                    throw new IllegalArgumentException("结束规则：请选择要新拾取的物品");
            }
            if (node.kind() == FlowGraph.Kind.PATROL && !node.setting("filter", "").isBlank()) {
                ResourceLocation entity = ResourceLocation.tryParse(node.setting("filter", ""));
                if (entity == null || !ForgeRegistries.ENTITY_TYPES.containsKey(entity))
                    throw new IllegalArgumentException("巡逻：请指定有效实体类型，或留空防御敌对生物");
            }
            if ((node.kind() == FlowGraph.Kind.HARVEST || node.kind() == FlowGraph.Kind.PATROL)
                    && (node.settings().containsKey("targetX") || !node.setting("target", "").isBlank())) position(node);
            if (node.kind() == FlowGraph.Kind.STORE || node.kind() == FlowGraph.Kind.TAKE || node.kind() == FlowGraph.Kind.ATTACK
                    && !node.setting("targetMode", "指定实体类型").equals("指定具体目标") || node.kind() == FlowGraph.Kind.HARVEST) {
                String filter = node.setting("filter", "");
                if (ResourceLocation.tryParse(filter) == null && !filter.equals("#minecraft:logs"))
                    throw new IllegalArgumentException(node.kind().label() + "：请从联想列表选择目标，或填写有效 ID");
                if (!filter.equals("#minecraft:logs")) {
                    ResourceLocation id = new ResourceLocation(filter);
                    boolean registered = switch (node.kind()) {
                        case STORE, TAKE -> ForgeRegistries.ITEMS.containsKey(id) && !filter.equals("minecraft:air");
                        case ATTACK -> ForgeRegistries.ENTITY_TYPES.containsKey(id);
                        case HARVEST -> ForgeRegistries.BLOCKS.containsKey(id) && !filter.equals("minecraft:air");
                        default -> false;
                    };
                    if (!registered) throw new IllegalArgumentException(node.kind().label() + "：指定的物品或目标不存在");
                }
            }
            if (node.kind() == FlowGraph.Kind.MOVE) {
                String mode = node.setting("targetMode", "主人所在位置");
                if (mode.equals("寻找目标") || mode.equals("搜索目标")) {
                    String filter = node.setting("filter", "");
                    if (!filter.equals("#minecraft:logs") && ResourceLocation.tryParse(filter) == null)
                        throw new IllegalArgumentException("移动：请先从联想列表选择搜索目标");
                    if (!filter.equals("#minecraft:logs")) {
                        ResourceLocation id = new ResourceLocation(filter);
                        boolean found = node.setting("filterKind", "block").equals("entity")
                                ? ForgeRegistries.ENTITY_TYPES.containsKey(id) : ForgeRegistries.BLOCKS.containsKey(id);
                        if (!found || filter.equals("minecraft:air")) throw new IllegalArgumentException("移动：搜索目标不存在");
                    }
                } else if (!mode.equals("主人所在位置") && !mode.equals("主人面前")) position(node);
            }
            if (node.kind() == FlowGraph.Kind.STORE || node.kind() == FlowGraph.Kind.TAKE) position(node);
            if (node.kind() == FlowGraph.Kind.ATTACK && node.setting("targetMode", "指定实体类型").equals("指定具体目标")) {
                try { UUID.fromString(node.setting("attackTarget", "")); }
                catch (IllegalArgumentException exception) { throw new IllegalArgumentException("攻击：请先用准星指定具体目标"); }
            }
        }
    }
    public static void select(EntityMaid maid, FlowGraph graph) {
        validate(graph);
        var task = TaskManager.findTask(FlowWorkTask.UID).orElseThrow(() -> new IllegalArgumentException("自定义工作模式未注册"));
        if (!task.isEnable(maid) || MinecraftForge.EVENT_BUS.post(
                new com.github.tartaricacid.touhoulittlemaid.api.event.MaidTaskEnableEvent(task, maid)))
            throw new IllegalArgumentException("当前状态不允许切换到自定义工作模式");
        drop(maid);
        CompoundTag data = FlowLibrary.data(maid);
        data.putUUID("selected", graph.id()); data.putBoolean("armed", true); data.remove("execution"); data.remove("loopCounters"); data.remove("delegate");
        data.putString("status", "等待关闭女仆界面"); FlowLibrary.put(maid, data);
        maid.setTask(task);
    }
    public static void drop(EntityMaid maid) {
        Session old = SESSIONS.remove(maid); if (old != null) { old.clearBreaking(maid); if (old.road != null) old.road.cancel(); } PICKUPS.remove(maid); maid.getNavigation().stop(); maid.setTarget(null);
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }
    public static void cancel(EntityMaid maid) {
        drop(maid); CompoundTag data = FlowLibrary.data(maid); data.putBoolean("armed", false);
        String delegate = data.getString("delegate"); data.remove("delegate");
        data.remove("execution"); data.putString("status", "已取消"); FlowLibrary.put(maid, data);
        if (maid.getTask().getUid().toString().equals(delegate)) maid.setTask(TaskManager.findTask(FlowWorkTask.UID).orElseThrow());
    }
    public static void tick(EntityMaid maid) {
        CompoundTag data = FlowLibrary.data(maid); long now = maid.level().getGameTime();
        if (!data.getBoolean("armed") || maid.guiOpening || data.getLong("editorUntil") > now) return;
        if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.level() != maid.level()) return;
        if (!maid.canBrainMoving()) {
            maid.getNavigation().stop(); maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET); return;
        }
        try {
            Session session = SESSIONS.get(maid);
            if (session == null) {
                FlowGraph graph = FlowLibrary.find(maid, data.getUUID("selected")); validate(graph);
                session = new Session(graph); SESSIONS.put(maid, session);
                if (data.contains("execution", 10)) session.restore(data.getCompound("execution"));
            }
            if (session.lastTick == now) return;
            session.lastTick = now;
            Session running = session;
            session.runner.tick(node -> running.work(maid, owner, node));
            data.put("execution", session.snapshot());
            String delegate = data.getString("delegate");
            boolean returnToFlow = !delegate.isEmpty() && (session.current() == null || session.current().kind() != FlowGraph.Kind.NATIVE_WORK
                    || !session.nodeId.equals(session.runner.currentNode()) || session.runner.state() != FlowRunner.State.RUNNING);
            if (returnToFlow) data.remove("delegate");
            data.putString("status", session.runner.state() == FlowRunner.State.RUNNING && !session.nodeData.getString("waitReason").isEmpty()
                    ? "等待：" + session.nodeData.getString("waitReason") : session.runner.state().name());
            if (session.runner.state() == FlowRunner.State.SUCCEEDED || session.runner.state() == FlowRunner.State.FAILED) {
                data.putBoolean("armed", false); maid.getNavigation().stop();
                maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                owner.sendSystemMessage(Component.literal("女仆 · " + session.graph.name() + "：" +
                        (session.runner.state() == FlowRunner.State.SUCCEEDED ? "任务完成" : session.runner.reason())));
                SESSIONS.remove(maid);
            }
            FlowLibrary.put(maid, data);
            if (returnToFlow && maid.getTask().getUid().toString().equals(delegate))
                maid.setTask(TaskManager.findTask(FlowWorkTask.UID).orElseThrow());
        } catch (RuntimeException exception) {
            data.putBoolean("armed", false); data.putString("status", "运行数据无效：" + exception.getMessage());
            String delegate = data.getString("delegate"); data.remove("execution"); data.remove("delegate");
            FlowLibrary.put(maid, data); drop(maid);
            if (maid.getTask().getUid().toString().equals(delegate)) maid.setTask(TaskManager.findTask(FlowWorkTask.UID).orElseThrow());
            owner.sendSystemMessage(Component.literal("女仆流程已停止：" + exception.getMessage()));
        }
    }
    private static final class Session {
        final FlowGraph graph;
        final FlowRunner runner;
        CompoundTag nodeData = new CompoundTag();
        String nodeId = "";
        long lastTick = -1;
        RoadRun road;
        Session(FlowGraph graph) { this.graph = graph; runner = new FlowRunner(graph); runner.start(graph); }
        FlowGraph.Node current() { return graph.nodes().stream().filter(n -> n.id().equals(nodeId)).findFirst().orElse(null); }
        void persist(EntityMaid maid) {
            CompoundTag library = FlowLibrary.data(maid); library.put("execution", snapshot()); FlowLibrary.put(maid, library);
        }
        CompoundTag snapshot() {
            CompoundTag result = new CompoundTag(); result.put("runner", runner.snapshot());
            result.putString("node", nodeId); result.put("worker", nodeData.copy()); return result;
        }
        void restore(CompoundTag snapshot) {
            runner.restore(snapshot.getCompound("runner")); nodeId = snapshot.getString("node"); nodeData = snapshot.getCompound("worker").copy();
        }
        FlowRunner.WorkResult work(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            if (!node.id().equals(nodeId)) { if (road != null) road.cancel(); road = null; nodeId = node.id(); nodeData = new CompoundTag(); }
            long now = maid.level().getGameTime();
            if (nodeData.getLong("retry") > now) return FlowRunner.WorkResult.running();
            if (FlowFinishRule.applies(node.kind())) {
                nodeData.putLong("workedTicks", nodeData.getLong("workedTicks") + 1);
                if (!FlowFinishRule.mode(node).equals("quiet") && finished(node)) return finish(maid);
            }
            if (road != null) {
                if (!RoadRules.get(owner).enabled(owner)) { road.cancel(); road = null; return FlowRunner.WorkResult.failed("主人已关闭通用筑路，当前通路已停止"); }
                FlowRunner.WorkResult progress = road.tick();
                if (progress.state() != FlowRunner.WorkState.RUNNING) {
                    road.cancel(); road = null; nodeData.remove("progressTime"); nodeData.remove("walkDestination"); nodeData.remove("stand");
                    nodeData.remove("roadAttempts"); nodeData.remove("nativeRoadTarget");
                    if (progress.state() == FlowRunner.WorkState.FAILED) return progress;
                }
                return FlowRunner.WorkResult.running();
            }
            nodeData.remove("waitReason");
            FlowRunner.WorkResult result = switch (node.kind()) {
                case START -> FlowRunner.WorkResult.done();
                case MOVE -> move(maid, owner, node);
                case STORE, TAKE -> transfer(maid, owner, node);
                case ATTACK, PATROL -> attack(maid, owner, node);
                case HARVEST -> harvest(maid, owner, node);
                case FEED, BREED -> feed(maid, owner, node);
                case NATIVE_WORK -> nativeWork(maid, owner, node);
                case BRANCH, TRIGGER, END_CONDITION, LOOP_START, LOOP_END -> condition(maid, node);
                default -> FlowRunner.WorkResult.failed("此动作尚未实现：" + node.kind().label());
            };
            if (FlowFinishRule.applies(node.kind()) && FlowFinishRule.mode(node).equals("quiet")
                    && result.state() == FlowRunner.WorkState.RUNNING && finished(node)) return finish(maid);
            if (result.state() == FlowRunner.WorkState.COMPLETE && node.kind().family() == FlowGraph.Family.ACTION) nodeId = "";
            return result;
        }
        private FlowRunner.WorkResult nativeWork(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            var nativeTask = NativeWorkTasks.find(node.setting("nativeTask", ""));
            if (!nodeData.getBoolean("nativeStarted")) {
                if (!NativeWorkTasks.enabled(maid, nativeTask)) return FlowRunner.WorkResult.failed("原生工作无法启用，请检查工具、装备和母模组工作条件：" + nativeTask.getUid());
                nodeData.putBoolean("nativeStarted", true);
                CompoundTag library = FlowLibrary.data(maid); library.putString("delegate", nativeTask.getUid().toString()); FlowLibrary.put(maid, library);
            }
            if (FlowFinishRule.mode(node).equals("inventory") && count(maid.getAvailableInv(false), node.setting("finishItem", "")) >= FlowFinishRule.requiredCount(node))
                return finish(maid);
            // Observe native work's actual destination, without replacing its successful movement or action AI.
            var walk = maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
            long now = maid.level().getGameTime();
            if (RoadRules.get(owner).enabled(owner) && walk.isPresent() && now >= nodeData.getLong("nativeProbe")) {
                nodeData.putLong("nativeProbe", now + 20);
                Vec3 target = walk.get().getTarget().currentPosition();
                long key = BlockPos.containing(target).asLong();
                if (!nodeData.contains("nativeRoadTarget") || key != nodeData.getLong("nativeRoadTarget")) {
                    nodeData.putLong("nativeRoadTarget", key); nodeData.remove("roadAttempts");
                }
                if (maid.position().distanceToSqr(target) > 4 && !canAdvance(maid, maid.getNavigation().createPath(BlockPos.containing(target), 0), target))
                    return roadAfterFailure(maid, owner, BlockPos.containing(target));
                nodeData.remove("roadAttempts");
            }
            return FlowRunner.WorkResult.running();
        }
        private boolean finished(FlowGraph.Node node) {
            return switch (FlowFinishRule.mode(node)) {
                case "duration" -> nodeData.getLong("workedTicks") >= FlowFinishRule.durationTicks(node);
                case "count" -> nodeData.getInt("finishedTargets") >= FlowFinishRule.requiredCount(node);
                case "loot" -> nodeData.getInt("finishedLoot") >= FlowFinishRule.requiredCount(node);
                case "quiet" -> nodeData.getLong("quietTicks") >= FlowFinishRule.durationTicks(node);
                default -> false;
            };
        }
        private FlowRunner.WorkResult finish(EntityMaid maid) {
            if (road != null) { road.cancel(); road = null; }
            clearBreaking(maid);
            maid.setTarget(null); stopWalking(maid);
            nodeId = ""; // An action linked back to itself must begin with fresh counters on the next visit.
            return FlowRunner.WorkResult.done();
        }
        private BlockPos center(EntityMaid maid, FlowGraph.Node node) {
            if (!nodeData.contains("workCenter")) {
                BlockPos pos = node.settings().containsKey("targetX") || !node.setting("target", "").isBlank()
                        ? position(node) : maid.blockPosition();
                nodeData.putLong("workCenter", pos.asLong());
            }
            return BlockPos.of(nodeData.getLong("workCenter"));
        }
        private AABB area(EntityMaid maid, FlowGraph.Node node) {
            int radius = integer(node.setting("radius", "2"), 2, 1, node.kind() == FlowGraph.Kind.HARVEST ? 4 : 8) * 16;
            return new AABB(center(maid, node)).inflate(radius, 4, radius);
        }
        private void presence(boolean hasTarget) {
            nodeData.putLong("quietTicks", hasTarget ? 0 : nodeData.getLong("quietTicks") + 1);
        }
        private FlowRunner.WorkResult condition(EntityMaid maid, FlowGraph.Node node) {
            String type = node.setting("conditionType", node.kind() == FlowGraph.Kind.LOOP_END ? "loop_count" : "always");
            boolean result = switch (type) {
                case "always" -> true;
                case "never" -> false;
                case "day" -> maid.level().isDay();
                case "night" -> !maid.level().isDay();
                case "owner_near" -> maid.getOwner() != null && maid.distanceToSqr(maid.getOwner()) <= 64;
                case "has_item" -> count(maid.getAvailableInv(true), node.setting("filter", "")) > 0;
                case "loop_count" -> {
                    CompoundTag library = FlowLibrary.data(maid), counters = library.getCompound("loopCounters");
                    int current = counters.getInt(node.id()) + 1;
                    int limit = integer(node.setting("loopCount", "3"), 3, 1, 1000);
                    counters.putInt(node.id(), current < limit ? current : 0); library.put("loopCounters", counters);
                    FlowLibrary.put(maid, library); yield current < limit;
                }
                default -> false;
            };
            return FlowRunner.WorkResult.branch(result);
        }
        private FlowRunner.WorkResult move(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            String mode = node.setting("targetMode", "主人所在位置");
            if (mode.equals("主人所在位置") || mode.equals("主人面前")) {
                Vec3 desired = inFront(owner);
                return approach(maid, owner, desired, true);
            }
            if (mode.equals("寻找目标") || mode.equals("搜索目标")) {
                if (!nodeData.contains("goal")) {
                    if (node.setting("filterKind", "block").equals("entity")) {
                        LivingEntity target = entity(maid, owner, node, false);
                        if (target == null) return searchFailed(maid, owner, "主人，我没找到指定目标");
                        nodeData.putLong("goal", target.blockPosition().asLong());
                    } else {
                        BlockPos found = scan(maid, node, maid.blockPosition());
                        if (found == null) return nodeData.getBoolean("searchDone")
                                ? searchFailed(maid, owner, "主人，我没找到指定目标") : FlowRunner.WorkResult.running();
                        nodeData.putLong("goal", found.above().asLong());
                    }
                }
                return approach(maid, owner, Vec3.atBottomCenterOf(BlockPos.of(nodeData.getLong("goal"))), false);
            }
            BlockPos target = position(node);
            return approach(maid, owner, Vec3.atBottomCenterOf(target), false);
        }
        /** Keeps waiting for a searchable target without teleporting the maid or spamming the chat. */
        private FlowRunner.WorkResult searchFailed(EntityMaid maid, ServerPlayer owner, String message) {
            notify(maid, owner, message);
            nodeData.putLong("retry", maid.level().getGameTime() + 100);
            for (String key : List.of("goal", "ring", "index", "layer", "center", "searchDone")) nodeData.remove(key);
            return FlowRunner.WorkResult.running();
        }
        private FlowRunner.WorkResult approach(EntityMaid maid, ServerPlayer owner, Vec3 target, boolean ownerGoal) {
            boolean buildRoad = RoadRules.get(owner).enabled(owner);
            BlockPos targetBlock = BlockPos.containing(target);
            if (!maid.level().isInWorldBounds(targetBlock) || !maid.level().hasChunkAt(targetBlock)
                    || !maid.level().getWorldBorder().isWithinBounds(targetBlock))
                return FlowRunner.WorkResult.failed("目标未加载、超出世界边界或不在世界范围内");
            long now = maid.level().getGameTime(); double distance = maid.position().distanceToSqr(target);
            long destination = targetBlock.asLong();
            if (!nodeData.contains("walkDestination") || nodeData.getLong("walkDestination") != destination) {
                nodeData.putLong("walkDestination", destination); nodeData.putLong("progressTime", now);
                nodeData.putDouble("lastDistance", distance); nodeData.putInt("stuck", 0); nodeData.remove("roadAttempts");
                maid.getNavigation().stop(); maid.getBrain().eraseMemory(MemoryModuleType.PATH);
                maid.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            }
            if (distance < (ownerGoal ? .36 : .64)) {
                stopWalking(maid);
                if (ownerGoal) {
                    maid.getLookControl().setLookAt(owner, 180, 180);
                    maid.setYRot((float) (Math.atan2(owner.getZ() - maid.getZ(), owner.getX() - maid.getX()) * 180 / Math.PI - 90));
                }
                return FlowRunner.WorkResult.done();
            }
            if (distance < nodeData.getDouble("lastDistance") - .25) {
                nodeData.putDouble("lastDistance", distance); nodeData.putLong("progressTime", now);
            }
            if (now >= nodeData.getLong("nextPath")) {
                nodeData.putLong("nextPath", now + 20);
                var path = maid.getNavigation().createPath(targetBlock, 0);
                if (canAdvance(maid, path, target)) {
                    maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 1.0f, 0));
                    maid.getNavigation().moveTo(path, 1.0);
                } else {
                    if (buildRoad) {
                        return roadAfterFailure(maid, owner, targetBlock);
                    }
                    if (ownerGoal && teleportFront(maid, owner)) return FlowRunner.WorkResult.done();
                }
            }
            if (now - nodeData.getLong("progressTime") > 160) {
                stopWalking(maid);
                if (buildRoad) {
                    nodeData.putLong("progressTime", now); nodeData.putLong("nextPath", now);
                    return roadAfterFailure(maid, owner, targetBlock);
                }
                if (ownerGoal && teleportFront(maid, owner)) return FlowRunner.WorkResult.done();
                return FlowRunner.WorkResult.failed("找不到可达的工作位置，或移动已连续8秒无进展");
            }
            return FlowRunner.WorkResult.running();
        }
        /** Shared fallback for every flow action and delegated native WALK_TARGET. */
        private FlowRunner.WorkResult roadAfterFailure(EntityMaid maid, ServerPlayer owner, BlockPos target) {
            int attempts = nodeData.getInt("roadAttempts") + 1; nodeData.putInt("roadAttempts", attempts);
            if (attempts >= 3) {
                stopWalking(maid);
                // Never freeze a construction search around a transient jumping/falling position.
                if (maid.onGround()) road = new RoadRun(maid, owner, target);
            }
            return FlowRunner.WorkResult.running();
        }
        private boolean canAdvance(EntityMaid maid, net.minecraft.world.level.pathfinder.Path path, Vec3 target) {
            return path != null && (path.canReach() || path.getNodeCount() > 1 && path.getEndNode() != null
                    && Vec3.atBottomCenterOf(path.getEndNode().asBlockPos()).distanceToSqr(target) + 1 < maid.position().distanceToSqr(target));
        }
        private void stopWalking(EntityMaid maid) {
            maid.getNavigation().stop(); maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            maid.getBrain().eraseMemory(MemoryModuleType.PATH);
            maid.setXxa(0); maid.setZza(0); maid.setSpeed(0);
            maid.getMoveControl().setWantedPosition(maid.getX(), maid.getY(), maid.getZ(), 0);
            maid.setDeltaMovement(0, maid.getDeltaMovement().y, 0);
        }
        /** Reachability includes eye-height visibility; standing inside the work block is never required. */
        private boolean sees(EntityMaid maid, Vec3 feet, Vec3 target, BlockPos workBlock) {
            var hit = maid.level().clip(new net.minecraft.world.level.ClipContext(feet.add(0, maid.getEyeHeight(), 0), target,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, maid));
            return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || workBlock != null && hit.getBlockPos().equals(workBlock);
        }
        private boolean inWorkReach(EntityMaid maid, Vec3 target, BlockPos workBlock, double reach) {
            return maid.getEyePosition().distanceToSqr(target) <= reach * reach && sees(maid, maid.position(), target, workBlock);
        }
        private FlowRunner.WorkResult workPosition(EntityMaid maid, ServerPlayer owner, Vec3 target, BlockPos workBlock, double reach) {
            if (inWorkReach(maid, target, workBlock, reach)) { stopWalking(maid); return FlowRunner.WorkResult.done(); }
            long key = BlockPos.containing(target).asLong();
            if (!nodeData.contains("workTarget") || nodeData.getLong("workTarget") != key) {
                nodeData.putLong("workTarget", key); nodeData.remove("stand"); nodeData.remove("walkDestination"); nodeData.remove("nextStand"); nodeData.remove("candidateCursor"); nodeData.remove("standFailures"); nodeData.remove("badStand");
            }
            if (nodeData.contains("stand")) {
                FlowRunner.WorkResult walk = approach(maid, owner, Vec3.atBottomCenterOf(BlockPos.of(nodeData.getLong("stand"))), false);
                if (walk.state() == FlowRunner.WorkState.FAILED || walk.state() == FlowRunner.WorkState.COMPLETE) {
                    nodeData.putLong("badStand", nodeData.getLong("stand")); nodeData.remove("stand");
                    nodeData.putInt("standFailures", nodeData.getInt("standFailures") + 1);
                    if (nodeData.getInt("standFailures") >= 3) return FlowRunner.WorkResult.failed("已经尝试多个工作位置，仍无法接近目标");
                } else return walk;
            }
            if (maid.level().getGameTime() < nodeData.getLong("nextStand")) return FlowRunner.WorkResult.running();
            nodeData.putLong("nextStand", maid.level().getGameTime() + 20);
            List<BlockPos> candidates = new ArrayList<>(), buildable = new ArrayList<>();
            BlockPos origin = BlockPos.containing(target);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -3; dy <= 2; dy++) {
                BlockPos feet = origin.offset(dx, dy, dz);
                if (nodeData.contains("badStand") && nodeData.getLong("badStand") == feet.asLong()) continue;
                if (!maid.level().hasChunkAt(feet) || !maid.level().getWorldBorder().isWithinBounds(feet)
                        || !maid.level().isInWorldBounds(feet) || !maid.level().hasChunkAt(feet.below())
                        || !maid.level().getFluidState(feet).isEmpty()) continue;
                Vec3 point = Vec3.atBottomCenterOf(feet);
                if (point.add(0, maid.getEyeHeight(), 0).distanceToSqr(target) > reach * reach
                        || !maid.level().noCollision(maid, maid.getBoundingBox().move(point.subtract(maid.position())))
                        || !sees(maid, point, target, workBlock)) continue;
                if (maid.level().getBlockState(feet.below()).isFaceSturdy(maid.level(), feet.below(), Direction.UP)) candidates.add(feet);
                else if (maid.level().getBlockState(feet.below()).isAir()
                        && !new AABB(feet.below()).intersects(maid.getBoundingBox())
                        && Math.abs(feet.getY() - maid.blockPosition().getY()) <= 6
                        && RoadPlan.inspect(maid, owner, feet) != null) buildable.add(feet);
            }
            candidates.sort(Comparator.comparingDouble(pos -> maid.distanceToSqr(Vec3.atBottomCenterOf(pos))));
            int cursor = Math.min(nodeData.getInt("candidateCursor"), candidates.size());
            int limit = Math.min(candidates.size(), cursor + 12);
            for (; cursor < limit; cursor++) {
                BlockPos feet = candidates.get(cursor);
                var path = maid.getNavigation().createPath(feet, 0);
                if (!canAdvance(maid, path, Vec3.atBottomCenterOf(feet))) continue;
                nodeData.putInt("candidateCursor", 0);
                nodeData.putLong("stand", feet.asLong()); nodeData.remove("walkDestination");
                FlowRunner.WorkResult walking = approach(maid, owner, Vec3.atBottomCenterOf(feet), false);
                return walking.state() == FlowRunner.WorkState.FAILED ? walking : FlowRunner.WorkResult.running();
            }
            nodeData.putInt("candidateCursor", cursor);
            if (cursor < candidates.size()) return FlowRunner.WorkResult.running();
            if (RoadRules.get(owner).enabled(owner) && (!candidates.isEmpty() || !buildable.isEmpty())) {
                // A safe work stance exists, but ordinary navigation cannot get there. Re-test all
                // candidates on three separate passes before asking the shared road planner.
                nodeData.putInt("candidateCursor", 0);
                // Floating work targets need a *future* stance supported by construction, not
                // a pre-existing floor. Prefer a low stance to minimize scaffolding and risk.
                buildable.sort(Comparator.comparingDouble(pos -> maid.distanceToSqr(Vec3.atBottomCenterOf(pos))));
                return roadAfterFailure(maid, owner, candidates.isEmpty() ? buildable.get(0) : candidates.get(0));
            }
            return FlowRunner.WorkResult.failed("目标附近没有可达且无遮挡的站立位置");
        }
        private FlowRunner.WorkResult transfer(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            boolean store = node.kind() == FlowGraph.Kind.STORE;
            String itemId = node.setting("filter", "");
            IItemHandler backpack = store ? maid.getAvailableInv(true) : maid.getAvailableBackpackInv();
            int requested = FlowItemRule.quantity(node);
            int completed = nodeData.getInt("taken");
            if (requested > 0 && completed >= requested) {
                if (!store) FlowEquipment.equipAcquired(maid, itemId);
                return FlowRunner.WorkResult.done();
            }
            if (store && count(backpack, itemId) == 0) {
                if (requested == 0 && completed > 0) return FlowRunner.WorkResult.done();
                return itemFailure(maid, owner, node, "主人，我没有要存放的 " + itemName(itemId));
            }
            BlockPos pos = position(node);
            if (!maid.level().hasChunkAt(pos) || !maid.level().mayInteract(owner, pos))
                return itemFailure(maid, owner, node, "主人，容器没有加载或我没有权限");
            var block = maid.level().getBlockEntity(pos);
            if (block instanceof BaseContainerBlockEntity container && !container.canOpen(owner))
                return itemFailure(maid, owner, node, "主人，这个容器打不开");
            var chestState = maid.level().getBlockState(pos);
            if (chestState.getBlock() instanceof ChestBlock && chestState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(chestState));
                if (!maid.level().hasChunkAt(otherPos) || !maid.level().mayInteract(owner, otherPos)
                        || !(maid.level().getBlockEntity(otherPos) instanceof BaseContainerBlockEntity other) || !other.canOpen(owner))
                    return itemFailure(maid, owner, node, "主人，这个大箱子的另一半打不开");
            }
            if (chestState.getBlock() instanceof ChestBlock chest && ChestBlock.getContainer(chest, chestState, maid.level(), pos, false) == null)
                return itemFailure(maid, owner, node, "主人，箱盖被挡住了，打不开箱子");
            IItemHandler inventory = null;
            if (block != null) for (Direction side : Direction.values()) {
                inventory = block.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
                if (inventory != null) break;
            }
            if (inventory == null) return itemFailure(maid, owner, node, "主人，这里不是可用的物品容器");
            FlowRunner.WorkResult reach = workPosition(maid, owner, Vec3.atCenterOf(pos), pos, 3.0);
            if (reach.state() == FlowRunner.WorkState.FAILED) return itemFailure(maid, owner, node, "主人，我到不了这个容器的交互位置");
            if (reach.state() != FlowRunner.WorkState.COMPLETE) return FlowRunner.WorkResult.running();
            IItemHandler from = store ? backpack : inventory, to = store ? inventory : backpack;
            int remaining = Math.min(64, requested == 0 ? 64 : requested - completed);
            int moved = 0;
            List<Integer> slots = store ? java.util.stream.IntStream.range(0, Math.min(from.getSlots(), 256)).boxed().toList()
                    : FlowItemChoice.order(from, itemId, maid.getRandom());
            for (int slot : slots) {
                if (moved >= remaining) break;
                if (!store && !nodeData.getBoolean("equippedByTake") && FlowItems.matches(from.getStackInSlot(slot), itemId)
                        && FlowEquipment.takeOneIntoHand(maid, from, slot)) {
                    moved++; nodeData.putBoolean("equippedByTake", true);
                    if (moved >= remaining) break;
                }
                ItemStack preview = from.extractItem(slot, remaining - moved, true);
                if (!FlowItems.matches(preview, itemId)) continue;
                ItemStack simulated = ItemHandlerHelper.insertItemStacked(to, preview.copy(), true);
                int accepted = preview.getCount() - simulated.getCount(); if (accepted <= 0) continue;
                ItemStack extracted = from.extractItem(slot, accepted, false);
                ItemStack rejected = ItemHandlerHelper.insertItemStacked(to, extracted.copy(), false);
                moved += extracted.getCount() - rejected.getCount();
                if (!rejected.isEmpty()) {
                    ItemStack rollback = from.insertItem(slot, rejected, false);
                    if (!rollback.isEmpty()) rollback = ItemHandlerHelper.insertItemStacked(from, rollback, false);
                    if (!rollback.isEmpty()) maid.spawnAtLocation(rollback);
                }
            }
            nodeData.putInt("taken", completed + moved);
            if (moved > 0) {
                maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (block instanceof BaseContainerBlockEntity container) {
                    closeContainer(maid); container.startOpen(owner);
                    OPENED.put(maid, new OpenedContainer(container, owner, maid.level().getGameTime() + 10));
                }
            }
            if (requested > 0 && nodeData.getInt("taken") >= requested || store && requested == 0 && count(backpack, itemId) == 0) {
                if (!store) FlowEquipment.equipAcquired(maid, itemId);
                return FlowRunner.WorkResult.done();
            }
            if (moved == 0) {
                int matching = count(from, itemId);
                String reason = matching == 0 ? "来源中没有这种物品；已完成 " + nodeData.getInt("taken") + "/" + (requested == 0 ? "全部" : requested)
                        : "来源有 " + matching + " 件匹配物品，但目标物品栏已满或拒绝放入";
                return itemFailure(maid, owner, node, "主人，" + itemName(itemId) + " 暂时无法" + (store ? "存入：" : "取出：") + reason);
            }
            return FlowRunner.WorkResult.running();
        }
        private FlowRunner.WorkResult itemFailure(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node, String message) {
            stopWalking(maid);
            for (String key : List.of("stand", "workTarget", "walkDestination", "candidateCursor", "badStand", "standFailures", "nextPath", "nextStand")) nodeData.remove(key);
            String policy = FlowItemRule.policy(node);
            nodeData.putString("waitReason", message);
            if (policy.equals("skip")) return FlowRunner.WorkResult.done();
            if (policy.equals("help")) notify(maid, owner, message);
            nodeData.putLong("retry", maid.level().getGameTime() + 100);
            return FlowRunner.WorkResult.running();
        }
        /** Explicit close combat; TLM's affinity/reach bonus must not turn this node into ranged damage. */
        private boolean closeMelee(EntityMaid maid, LivingEntity target) {
            return maid.distanceToSqr(target) <= 2.25 * 2.25 && Math.abs(maid.getY() - target.getY()) <= 1.5;
        }
        private FlowRunner.WorkResult attack(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            LivingEntity target = entity(maid, owner, node, true);
            presence(target != null);
            FlowRunner.WorkResult loot = collectLoot(maid, owner, node);
            if (loot != null) return loot;
            if (target == null) {
                maid.setTarget(null);
                if (nodeData.getLong("blockedUntil") > maid.level().getGameTime())
                    return warn(maid, owner, "主人，范围内还有被阻挡的目标，等待通路恢复", 40);
                if (FlowFinishRule.mode(node).equals("clear")) return finish(maid);
                if (node.kind() == FlowGraph.Kind.PATROL) return patrol(maid, owner, node);
                maid.getNavigation().stop(); maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                return FlowRunner.WorkResult.running();
            }
            maid.setTarget(target); maid.getLookControl().setLookAt(target, 60, 60);
            if (!closeMelee(maid, target) || !maid.hasLineOfSight(target)) {
                FlowRunner.WorkResult reached = workPosition(maid, owner, target.position().add(0, target.getBbHeight() * .5, 0), null, 2.0);
                if (reached.state() == FlowRunner.WorkState.FAILED) {
                    nodeData.remove("workTarget"); nodeData.remove("stand");
                    nodeData.putString("blockedEntity", target.getUUID().toString());
                    nodeData.putLong("blockedUntil", maid.level().getGameTime() + 100);
                    return warn(maid, owner, "主人，这个攻击目标被阻挡，我会寻找其他目标并稍后重试", 20);
                }
                return FlowRunner.WorkResult.running();
            }
            stopWalking(maid);
            if (maid.level().getGameTime() >= nodeData.getLong("nextAttack")) {
                maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND); maid.doHurtTarget(target);
                double speed = maid.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
                nodeData.putLong("nextAttack", maid.level().getGameTime() + Math.max(1, (long) (20 / Math.max(.1, speed))));
            }
            return FlowRunner.WorkResult.running();
        }
        private FlowRunner.WorkResult patrol(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            BlockPos center = center(maid, node);
            int distance = Math.min(12, integer(node.setting("radius", "2"), 2, 1, 8) * 16);
            int leg = nodeData.getInt("patrolLeg") % 4;
            BlockPos point = center.offset(leg == 0 || leg == 3 ? distance : -distance, 0, leg < 2 ? distance : -distance);
            if (maid.distanceToSqr(Vec3.atBottomCenterOf(point)) <= 4) {
                nodeData.putInt("patrolLeg", (leg + 1) % 4); nodeData.remove("progressTime"); nodeData.putInt("stuck", 0);
                return FlowRunner.WorkResult.running();
            }
            FlowRunner.WorkResult result = approach(maid, owner, Vec3.atBottomCenterOf(point), false);
            if (result.state() == FlowRunner.WorkState.FAILED) {
                // One unreachable patrol corner must not stop the whole defensive task.
                nodeData.putInt("patrolLeg", (leg + 1) % 4);
                nodeData.putInt("stuck", 0); nodeData.remove("progressTime");
                notify(maid, owner, "这个方向过不去，换下一个巡逻点");
                return FlowRunner.WorkResult.running();
            }
            return FlowRunner.WorkResult.running();
        }
        private FlowRunner.WorkResult collectLoot(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            if (!FlowFinishRule.mode(node).equals("loot")) return null;
            String wanted = node.setting("finishItem", "");
            ItemEntity nearest = maid.level().getEntitiesOfClass(ItemEntity.class, area(maid, node),
                    item -> item.isAlive() && !item.hasPickUpDelay() && FlowItems.matches(item.getItem(), wanted))
                    .stream().min(Comparator.comparingDouble(maid::distanceToSqr)).orElse(null);
            if (nearest == null) return null;
            if (maid.distanceToSqr(nearest) < 4) {
                if (!maid.pickupItem(nearest, false))
                    return itemFailure(maid, owner, node, "主人，背包装不下要收集的物品了");
                return FlowRunner.WorkResult.running();
            }
            FlowRunner.WorkResult walk = approach(maid, owner, nearest.position(), false);
            return walk.state() == FlowRunner.WorkState.FAILED ? walk : FlowRunner.WorkResult.running();
        }
        private boolean matchesEntity(EntityMaid maid, FlowGraph.Node node, LivingEntity entity) {
            if (entity == maid || entity == maid.getOwner() || entity instanceof EntityMaid
                    || entity instanceof net.minecraft.world.entity.player.Player || entity.isAlliedTo(maid)) return false;
            if (entity instanceof TamableAnimal tame && Objects.equals(tame.getOwnerUUID(), maid.getOwnerUUID())) return false;
            if (node.kind() == FlowGraph.Kind.ATTACK && node.setting("targetMode", "指定实体类型").equals("指定具体目标"))
                return entity.getUUID().toString().equals(node.setting("attackTarget", ""));
            String filter = node.setting("filter", "");
            if (node.kind() == FlowGraph.Kind.PATROL && filter.isBlank()) return entity instanceof Enemy;
            return filter.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString());
        }
        private LivingEntity entity(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node, boolean attacking) {
            int radius = integer(node.setting("radius", "2"), 2, 1, 8) * 16;
            AABB bounds = attacking ? area(maid, node) : maid.getBoundingBox().inflate(radius, 4, radius);
            return maid.level().getEntitiesOfClass(LivingEntity.class, bounds,
                    entity -> entity.isAlive() && matchesEntity(maid, node, entity)
                            && !(nodeData.getLong("blockedUntil") > maid.level().getGameTime()
                            && entity.getUUID().toString().equals(nodeData.getString("blockedEntity"))))
                    .stream().min(Comparator.comparingDouble(maid::distanceToSqr)).orElse(null);
        }
        private FlowRunner.WorkResult feed(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            boolean breeding = node.kind() == FlowGraph.Kind.BREED;
            if (nodeData.getLong("nextFeed") > maid.level().getGameTime()) return FlowRunner.WorkResult.running();
            var animals = maid.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, area(maid, node), animal ->
                    animal.isAlive() && node.setting("animalType", "").equals(ForgeRegistries.ENTITY_TYPES.getKey(animal.getType()).toString())
                    && (!(animal instanceof TamableAnimal tame) || !tame.isTame() || Objects.equals(tame.getOwnerUUID(), maid.getOwnerUUID())));
            var parents = nodeData.getCompound("bredParents");
            // Prune stale parents; love can expire, parents can die, and no stalled pairing is counted as a birth.
            for (String id : new ArrayList<>(parents.getAllKeys())) {
                var parent = animals.stream().filter(animal -> animal.getUUID().toString().equals(id)).findFirst().orElse(null);
                if (parent == null || !parent.isInLove()) parents.remove(id);
            }
            nodeData.put("bredParents", parents);
            var candidates = animals.stream().filter(animal -> breeding ? animal.getAge() == 0 && animal.canFallInLove() : animal.isBaby())
                    .sorted(Comparator.comparingDouble(maid::distanceToSqr)).toList();
            presence(!candidates.isEmpty() || !parents.isEmpty());
            if (candidates.isEmpty()) {
                stopWalking(maid);
                if (FlowFinishRule.mode(node).equals("clear") && parents.isEmpty()) return finish(maid);
                return FlowRunner.WorkResult.running();
            }
            var animal = candidates.get(0);
            if (breeding && candidates.size() < 2 && parents.isEmpty())
                return itemFailure(maid, owner, node, "主人，范围内没有能配对繁殖的两只成年动物");
            String foodId = node.setting("filter", ""); var inventory = maid.getAvailableInv(true);
            int slot = -1;
            for (int i = 0; i < inventory.getSlots(); i++) {
                ItemStack food = inventory.getStackInSlot(i);
                if (FlowItems.matches(food, foodId) && animal.isFood(food)) { slot = i; break; }
            }
            if (slot < 0) return itemFailure(maid, owner, node, "主人，缺少适合目标动物的 " + itemName(foodId));
            FlowRunner.WorkResult reach = workPosition(maid, owner, animal.position().add(0, animal.getBbHeight() * .5, 0), null, 2);
            if (reach.state() == FlowRunner.WorkState.FAILED) return itemFailure(maid, owner, node, "主人，我到不了要喂养的动物身边");
            if (reach.state() != FlowRunner.WorkState.COMPLETE) return FlowRunner.WorkResult.running();
            var actor = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.server.level.ServerLevel) maid.level(), owner.getGameProfile());
            actor.setPos(maid.getX(), maid.getY(), maid.getZ());
            if (MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract(actor, net.minecraft.world.InteractionHand.MAIN_HAND, animal)))
                return itemFailure(maid, owner, node, "主人，这里不允许喂养这个动物");
            ItemStack food = inventory.extractItem(slot, 1, false);
            if (food.isEmpty()) return itemFailure(maid, owner, node, "主人，食物暂时无法取出");
            ItemStack previousHand = actor.getMainHandItem().copy();
            actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, food);
            boolean consumed;
            try {
                animal.mobInteract(actor, net.minecraft.world.InteractionHand.MAIN_HAND);
                consumed = actor.getMainHandItem().isEmpty();
            } finally {
                ItemStack remainder = actor.getMainHandItem().copy();
                actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, previousHand);
                if (!remainder.isEmpty()) {
                    ItemStack rejected = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inventory, remainder, false);
                    if (!rejected.isEmpty()) maid.spawnAtLocation(rejected);
                }
            }
            if (!consumed) return itemFailure(maid, owner, node, "主人，这种动物现在不接受该食物");
            maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND); maid.getLookControl().setLookAt(animal, 60, 60);
            if (breeding) { parents.putBoolean(animal.getUUID().toString(), true); nodeData.put("bredParents", parents); }
            else nodeData.putInt("finishedTargets", nodeData.getInt("finishedTargets") + 1);
            nodeData.putLong("nextFeed", maid.level().getGameTime() + 20);
            return FlowRunner.WorkResult.running();
        }
        private FlowRunner.WorkResult harvest(EntityMaid maid, ServerPlayer owner, FlowGraph.Node node) {
            FlowRunner.WorkResult loot = collectLoot(maid, owner, node);
            if (loot != null) return loot;
            if (!net.minecraftforge.event.ForgeEventFactory.getMobGriefingEvent(maid.level(), maid))
                return FlowRunner.WorkResult.failed("这里不允许女仆采集方块（gamerule mobGriefing 已关闭）");
            BlockPos center = center(maid, node);
            if (nodeData.getBoolean("areaEmpty")) presence(false);
            if (nodeData.getLong("scanAfter") > maid.level().getGameTime()) return FlowRunner.WorkResult.running();
            BlockPos target;
            if (nodeData.contains("goal")) target = BlockPos.of(nodeData.getLong("goal"));
            else {
                target = scan(maid, node, center);
                if (target == null) {
                    if (nodeData.getBoolean("searchDone")) {
                        if (nodeData.getLongArray("blockedBlocks").length > 0) {
                            clearScan(); nodeData.remove("blockedBlocks");
                            return itemFailure(maid, owner, node, "主人，剩下的目标无法到达或不允许采集，请帮我恢复通路");
                        }
                        if (FlowFinishRule.mode(node).equals("clear")) return finish(maid);
                        nodeData.putBoolean("areaEmpty", true);
                        nodeData.putLong("scanAfter", maid.level().getGameTime() + 20);
                        for (String key : List.of("ring", "index", "layer", "searchDone")) nodeData.remove(key);
                    }
                    return FlowRunner.WorkResult.running();
                }
                nodeData.putBoolean("areaEmpty", false); presence(true);
                nodeData.putLong("goal", target.asLong());
            }
            if (!maid.level().hasChunkAt(target)) { nodeData.remove("goal"); return FlowRunner.WorkResult.running(); }
            var state = maid.level().getBlockState(target);
            String filter = node.setting("filter", "");
            boolean matches = filter.equals("#minecraft:logs") ? state.is(BlockTags.LOGS)
                    : filter.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
            if (!matches || state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                clearBreaking(maid); nodeData.remove("goal"); return FlowRunner.WorkResult.running();
            }
            FlowRunner.WorkResult reach = workPosition(maid, owner, Vec3.atCenterOf(target), target, 3.0);
            if (reach.state() == FlowRunner.WorkState.FAILED) {
                deferBlock(maid, target); return FlowRunner.WorkResult.running();
            }
            if (reach.state() != FlowRunner.WorkState.COMPLETE) { clearBreaking(maid); return FlowRunner.WorkResult.running(); }
            if (!maid.level().mayInteract(owner, target) || state.getDestroySpeed(maid.level(), target) < 0
                    || !maid.canDestroyBlock(target)) {
                deferBlock(maid, target); return FlowRunner.WorkResult.running();
            }
            maid.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
            if (!nodeData.contains("breaking")) {
                nodeData.putLong("breaking", target.asLong()); nodeData.putInt("breakTicks", 0);
                nodeData.putInt("breakState", net.minecraft.world.level.block.Block.getId(state));
            }
            if (nodeData.getInt("breakState") != net.minecraft.world.level.block.Block.getId(state)) {
                clearBreaking(maid); return FlowRunner.WorkResult.running();
            }
            int ticks = nodeData.getInt("breakTicks") + 1; nodeData.putInt("breakTicks", ticks);
            float speed = Math.max(1, maid.getMainHandItem().getDestroySpeed(state));
            int required = Math.max(5, (int) Math.ceil(state.getDestroySpeed(maid.level(), target) * 30 / speed));
            if (ticks % 5 == 1) maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            maid.level().destroyBlockProgress(maid.getId(), target, Math.min(9, ticks * 10 / required));
            if (ticks < required) return FlowRunner.WorkResult.running();
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(maid.level(), target, state, owner))) {
                deferBlock(maid, target); return FlowRunner.WorkResult.running();
            }
            // Use TLM's real resource insertion and destruction hooks, with the actual held tool.
            int before = count(maid.getAvailableInv(false), node.setting("finishItem", ""));
            var blockEntity = maid.level().getBlockEntity(target);
            ItemStack tool = maid.getMainHandItem();
            if (maid.destroyBlock(maid.level(), target, false, maid)) {
                maid.dropResourcesToMaidInv(state, maid.level(), target, blockEntity, maid, tool);
                if (!tool.isEmpty() && tool.isDamageableItem()) tool.hurtAndBreak(1, maid, entity -> entity.broadcastBreakEvent(net.minecraft.world.InteractionHand.MAIN_HAND));
                nodeData.putInt("finishedTargets", nodeData.getInt("finishedTargets") + 1);
                if (FlowFinishRule.mode(node).equals("loot")) nodeData.putInt("finishedLoot", nodeData.getInt("finishedLoot")
                        + Math.max(0, count(maid.getAvailableInv(false), node.setting("finishItem", "")) - before));
            } else deferBlock(maid, target);
            clearBreaking(maid); nodeData.remove("goal"); return FlowRunner.WorkResult.running();
        }
        private void clearBreaking(EntityMaid maid) {
            if (nodeData.contains("breaking")) maid.level().destroyBlockProgress(maid.getId(), BlockPos.of(nodeData.getLong("breaking")), -1);
            nodeData.remove("breaking"); nodeData.remove("breakTicks"); nodeData.remove("breakState");
        }
        private void clearScan() {
            for (String key : List.of("goal", "ring", "index", "layer", "searchDone", "areaEmpty", "stand", "workTarget")) nodeData.remove(key);
        }
        private void deferBlock(EntityMaid maid, BlockPos target) {
            clearBreaking(maid); stopWalking(maid);
            long[] blocked = nodeData.getLongArray("blockedBlocks");
            if (blocked.length < 512 && java.util.Arrays.stream(blocked).noneMatch(value -> value == target.asLong())) {
                long[] next = java.util.Arrays.copyOf(blocked, blocked.length + 1); next[blocked.length] = target.asLong(); nodeData.putLongArray("blockedBlocks", next);
            }
            nodeData.remove("goal"); nodeData.remove("stand"); nodeData.remove("workTarget");
        }
        /** At most 128 loaded probes per tick. Harvest includes high/low targets within 16 Y blocks. */
        private BlockPos scan(EntityMaid maid, FlowGraph.Node node, BlockPos center) {
            if (!nodeData.contains("center")) nodeData.putLong("center", center.asLong());
            center = BlockPos.of(nodeData.getLong("center"));
            int maxRadius = integer(node.setting("radius", "2"), 2, 1, node.kind() == FlowGraph.Kind.HARVEST ? 4 : 8) * 16;
            int vertical = node.kind() == FlowGraph.Kind.HARVEST ? 16 : 4;
            String filter = node.setting("filter", "");
            Set<Long> blocked = new HashSet<>(); for (long pos : nodeData.getLongArray("blockedBlocks")) blocked.add(pos);
            int ring = nodeData.getInt("ring"), index = nodeData.getInt("index"), layer = nodeData.getInt("layer");
            for (int budget = 0; budget < 128 && ring <= maxRadius; budget++) {
                int x = 0, z = 0;
                if (ring > 0) {
                    int edge = index / (ring * 2), offset = index % (ring * 2);
                    switch (edge) {
                        case 0 -> { x = -ring + offset; z = -ring; }
                        case 1 -> { x = ring; z = -ring + offset; }
                        case 2 -> { x = ring - offset; z = ring; }
                        default -> { x = -ring; z = ring - offset; }
                    }
                }
                BlockPos pos = center.offset(x, layer - vertical, z);
                if (++layer >= vertical * 2 + 1) { layer = 0; if (++index >= Math.max(1, ring * 8)) { index = 0; ring++; } }
                nodeData.putInt("ring", ring); nodeData.putInt("index", index); nodeData.putInt("layer", layer);
                if (!maid.level().isInWorldBounds(pos) || !maid.level().hasChunkAt(pos)) continue;
                if (blocked.contains(pos.asLong())) continue;
                var state = maid.level().getBlockState(pos);
                boolean matches = filter.equals("#minecraft:logs") ? state.is(BlockTags.LOGS)
                        : filter.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
                if (matches && !(state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state))) return pos;
            }
            if (ring > maxRadius) nodeData.putBoolean("searchDone", true);
            return null;
        }
        /** Throttled feedback. Warnings use the action bar so a blocked node cannot flood the chat. */
        private void notify(EntityMaid maid, ServerPlayer owner, String message) {
            long now = maid.level().getGameTime();
            if (!nodeData.contains("lastHelp") || now - nodeData.getLong("lastHelp") >= 200) {
                owner.sendSystemMessage(Component.literal("女仆：" + message));
                nodeData.putLong("lastHelp", now);
            }
        }
        /** Warn, keep the maid where she is and wait a little before trying again. */
        private FlowRunner.WorkResult warn(EntityMaid maid, ServerPlayer owner, String message, int retryTicks) {
            stopWalking(maid); nodeData.putString("waitReason", message);
            notify(maid, owner, message);
            nodeData.putLong("retry", maid.level().getGameTime() + Math.max(20, retryTicks));
            return FlowRunner.WorkResult.running();
        }
    }
    private static int count(IItemHandler inventory, String id) {
        int count = 0;
        for (int i = 0; i < Math.min(inventory.getSlots(), 256); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (FlowItems.matches(stack, id)) count += stack.getCount();
        }
        return count;
    }
    private static String itemName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null ? id : new ItemStack(item).getHoverName().getString();
    }
    private static BlockPos position(FlowGraph.Node node) {
        try {
            if (node.settings().containsKey("targetX")) return new BlockPos(Integer.parseInt(node.setting("targetX", "")),
                    Integer.parseInt(node.setting("targetY", "")), Integer.parseInt(node.setting("targetZ", "")));
            String[] parts = node.setting("target", "").split(",");
            if (parts.length != 3) throw new IllegalArgumentException();
            return new BlockPos(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()), Integer.parseInt(parts[2].strip()));
        } catch (RuntimeException exception) { throw new IllegalArgumentException(node.kind().label() + "：尚未指定有效位置"); }
    }
    private static int integer(String text, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(text))); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static Vec3 inFront(ServerPlayer owner) {
        Vec3 view = new Vec3(owner.getLookAngle().x, 0, owner.getLookAngle().z);
        if (view.lengthSqr() < .001) view = new Vec3(0, 0, 1);
        return owner.position().add(view.normalize().scale(2));
    }
    private static boolean teleportFront(EntityMaid maid, ServerPlayer owner) {
        Vec3 front = inFront(owner);
        for (int offset : new int[]{0, 1, -1, 2, -2}) {
            Vec3 target = front.add(0, offset, 0); BlockPos feet = BlockPos.containing(target);
            if (!maid.level().hasChunkAt(feet) || !maid.level().getWorldBorder().isWithinBounds(feet)
                    || !maid.level().getBlockState(feet.below()).isFaceSturdy(maid.level(), feet.below(), Direction.UP)
                    || !maid.level().getFluidState(feet).isEmpty()) continue;
            if (!maid.level().noCollision(maid, maid.getBoundingBox().move(target.subtract(maid.position())))) continue;
            maid.teleportTo(target.x, target.y, target.z); maid.getNavigation().stop();
            maid.getLookControl().setLookAt(owner, 180, 180); return true;
        }
        return false;
    }
}
