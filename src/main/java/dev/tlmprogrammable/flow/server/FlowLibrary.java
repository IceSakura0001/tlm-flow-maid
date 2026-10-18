package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.*;
import net.minecraft.nbt.*;
import java.io.*;
import java.util.*;

/** Server-owned library stored with the maid, independently from client drafts. */
public final class FlowLibrary {
    public static final int MAX_TASKS = 32, MAX_GRAPH_BYTES = 49152, MAX_LIBRARY_BYTES = 524288;
    public static final String KEY = "tlm_flow_library";
    private FlowLibrary() { }
    public static void registerTransforms() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(FlowLibrary::toItem);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(FlowLibrary::toMaid);
    }
    private static void toItem(com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToItem event) {
        if (has(event.getMaid())) event.getData().put("tlmFlowLibrary", portable(data(event.getMaid())));
    }
    private static void toMaid(com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToMaid event) {
        CompoundTag source = event.getData();
        CompoundTag saved = source.contains("tlmFlowLibrary", 10) ? source.getCompound("tlmFlowLibrary")
                : source.getCompound("ForgeData").getCompound(KEY);
        if (!saved.isEmpty()) put(event.getMaid(), portable(saved));
    }
    public static CompoundTag portable(CompoundTag source) {
        CompoundTag copy = source.copy();
        for (String key : List.of("execution", "delegate", "editorUntil", "loopCounters")) copy.remove(key);
        copy.putBoolean("armed", false); copy.putString("status", "已恢复任务库，请重新选择任务"); return copy;
    }
    public static CompoundTag data(EntityMaid maid) { return maid.getPersistentData().getCompound(KEY); }
    public static boolean has(EntityMaid maid) { return maid.getPersistentData().contains(KEY, 10); }
    public static void put(EntityMaid maid, CompoundTag data) { maid.getPersistentData().put(KEY, data); }
    public static List<FlowGraph> graphs(CompoundTag data) {
        ListTag list = data.getList("tasks", 10);
        if (list.size() > MAX_TASKS) throw new IllegalArgumentException("任务库超出容量，已保留原始数据");
        List<FlowGraph> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            FlowGraph graph = FlowGraphNbt.read(list.getCompound(i));
            if (!ids.add(graph.id())) throw new IllegalArgumentException("任务 ID 重复，已保留原始数据");
            result.add(graph);
        }
        return result;
    }
    public static FlowGraph find(EntityMaid maid, UUID id) {
        return graphs(data(maid)).stream().filter(graph -> graph.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("任务不存在或已删除"));
    }
    public static void save(EntityMaid maid, FlowGraph graph, int expectedRevision) {
        CompoundTag data = data(maid).copy();
        requireRevision(data, expectedRevision);
        FlowRuntime.validate(graph);
        if (size(FlowGraphNbt.write(graph)) > MAX_GRAPH_BYTES) throw new IllegalArgumentException("单个任务过大");
        List<FlowGraph> graphs = new ArrayList<>(graphs(data));
        graphs.removeIf(saved -> saved.id().equals(graph.id()));
        if (graphs.size() >= MAX_TASKS) throw new IllegalArgumentException("每位女仆最多保存 32 个任务");
        graphs.add(graph);
        writeGraphs(data, graphs);
        if (size(data) > MAX_LIBRARY_BYTES) throw new IllegalArgumentException("任务库已满，请先删除不用的任务");
        if (data.hasUUID("selected") && data.getUUID("selected").equals(graph.id())) {
            data.putBoolean("armed", false); data.remove("execution"); FlowRuntime.drop(maid);
            String delegated = data.getString("delegate"); data.remove("delegate");
            if (maid.getTask().getUid().toString().equals(delegated))
                maid.setTask(com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getIdleTask());
        }
        put(maid, data);
    }
    public static void delete(EntityMaid maid, UUID id, int expectedRevision) {
        CompoundTag data = data(maid).copy(); requireRevision(data, expectedRevision);
        List<FlowGraph> graphs = new ArrayList<>(graphs(data));
        if (!graphs.removeIf(graph -> graph.id().equals(id))) throw new IllegalArgumentException("任务已删除");
        writeGraphs(data, graphs);
        if (data.hasUUID("selected") && data.getUUID("selected").equals(id)) {
            data.remove("selected"); data.remove("execution"); data.putBoolean("armed", false);
            String delegated = data.getString("delegate"); data.remove("delegate");
            FlowRuntime.drop(maid);
            if (FlowWorkTask.UID.equals(maid.getTask().getUid()) || maid.getTask().getUid().toString().equals(delegated))
                maid.setTask(com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getIdleTask());
        }
        put(maid, data);
    }
    private static void writeGraphs(CompoundTag data, List<FlowGraph> graphs) {
        ListTag list = new ListTag(); graphs.forEach(graph -> list.add(FlowGraphNbt.write(graph)));
        data.put("tasks", list); data.putInt("revision", data.getInt("revision") + 1);
    }
    private static void requireRevision(CompoundTag data, int revision) {
        if (data.getInt("revision") != revision) throw new IllegalArgumentException("任务库已更新，请重新打开后再保存");
    }
    private static int size(CompoundTag data) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); NbtIo.write(data, new DataOutputStream(bytes));
            return bytes.size();
        } catch (IOException exception) { throw new IllegalArgumentException("无法序列化任务", exception); }
    }
    public static CompoundTag view(EntityMaid maid) {
        CompoundTag stored = data(maid), result = new CompoundTag();
        result.put("tasks", stored.getList("tasks", 10).copy());
        result.putInt("revision", stored.getInt("revision"));
        if (stored.hasUUID("selected")) result.putUUID("selected", stored.getUUID("selected"));
        result.putString("status", stored.getString("status"));
        return result;
    }
}
