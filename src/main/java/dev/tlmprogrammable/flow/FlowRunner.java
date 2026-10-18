package dev.tlmprogrammable.flow;

import java.util.*;

/** Bounded control-flow interpreter. Conditions can branch and loops can return, but neither can spin forever. */
public final class FlowRunner {
    public static final int MAX_TRANSITIONS_PER_TICK = 64;
    public static final int MAX_TRANSITIONS_PER_RUN = 100_000;
    public static final int MAX_VISITS_PER_NODE = 10_000;

    public enum State { READY, RUNNING, PAUSED, SUCCEEDED, FAILED }
    public enum WorkState { RUNNING, COMPLETE, FAILED }
    public record WorkResult(WorkState state, FlowGraph.Port port, String message) {
        public WorkResult { Objects.requireNonNull(state); message = message == null ? "" : message; }
        public static WorkResult running() { return new WorkResult(WorkState.RUNNING, FlowGraph.Port.NEXT, ""); }
        public static WorkResult done() { return new WorkResult(WorkState.COMPLETE, FlowGraph.Port.NEXT, ""); }
        public static WorkResult branch(boolean yes) {
            return new WorkResult(WorkState.COMPLETE, yes ? FlowGraph.Port.YES : FlowGraph.Port.NO, "");
        }
        public static WorkResult failed(String reason) { return new WorkResult(WorkState.FAILED, FlowGraph.Port.NEXT, reason); }
    }
    @FunctionalInterface public interface NodeWorker { WorkResult tick(FlowGraph.Node node); }

    private final Map<String, FlowGraph.Node> nodes;
    private final UUID graphId;
    private final Map<String, Map<FlowGraph.Port, String>> exits = new HashMap<>();
    private final Map<String, Integer> visits = new HashMap<>();
    private State state = State.READY;
    private String current;
    private String countedNode;
    private int transitions;
    private String reason = "";

    public FlowRunner(FlowGraph graph) {
        graph.validateExecutable();
        graphId = graph.id();
        Map<String, FlowGraph.Node> indexed = new HashMap<>();
        graph.nodes().forEach(node -> indexed.put(node.id(), node));
        nodes = Map.copyOf(indexed);
        graph.edges().forEach(edge -> exits.computeIfAbsent(edge.from(), ignored -> new EnumMap<>(FlowGraph.Port.class))
                .put(edge.port(), edge.to()));
    }

    public void start(FlowGraph graph) {
        if (state == State.RUNNING) throw new IllegalStateException("流程正在运行");
        if (!graphId.equals(graph.id())) throw new IllegalArgumentException("执行器不能切换到另一份流程图");
        graph.validateExecutable();
        current = graph.nodes().stream().filter(n -> n.kind() == FlowGraph.Kind.START).findFirst().orElseThrow().id();
        transitions = 0; visits.clear(); countedNode = null; reason = ""; state = State.RUNNING;
    }
    public void pause() { if (state == State.RUNNING) state = State.PAUSED; }
    public void resume() { if (state == State.PAUSED) state = State.RUNNING; }
    public State state() { return state; }
    public String currentNode() { return current; }
    public int transitions() { return transitions; }
    public String reason() { return reason; }

    /** Runs bounded immediate control flow plus at most one worker call per game tick. */
    public void tick(NodeWorker worker) {
        if (state != State.RUNNING) return;
        for (int budget = 0; budget < MAX_TRANSITIONS_PER_TICK; budget++) {
            if (transitions >= MAX_TRANSITIONS_PER_RUN) { fail("流程超过单次运行步数上限"); return; }
            FlowGraph.Node node = nodes.get(current);
            if (node == null) { fail("运行节点不存在"); return; }
            if (!current.equals(countedNode)) {
                int count = visits.merge(current, 1, Integer::sum);
                if (count > MAX_VISITS_PER_NODE) { fail("循环次数超过安全上限：" + node.kind().label()); return; }
                countedNode = current;
            }
            if (node.kind() == FlowGraph.Kind.END) { state = State.SUCCEEDED; return; }
            WorkResult result;
            try { result = Objects.requireNonNull(worker.tick(node), "节点执行器返回空结果"); }
            catch (RuntimeException e) {
                String detail = e.getMessage();
                fail("节点执行器异常：" + e.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : "：" + detail));
                return;
            }
            if (result.state() == WorkState.RUNNING) return;
            if (result.state() == WorkState.FAILED) { fail(result.message()); return; }
            String next = exits.getOrDefault(current, Map.of()).get(result.port());
            if (next == null) { fail("当前出口没有连接"); return; }
            current = next;
            transitions++;
            countedNode = null;
            if (node.kind().family() == FlowGraph.Family.ACTION) return;
        }
    }
    public net.minecraft.nbt.CompoundTag snapshot() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("state", state.name()); tag.putString("current", current == null ? "" : current);
        tag.putInt("transitions", transitions); tag.putString("reason", reason);
        var counts = new net.minecraft.nbt.CompoundTag(); visits.forEach(counts::putInt); tag.put("visits", counts);
        tag.putString("counted", countedNode == null ? "" : countedNode); return tag;
    }
    public void restore(net.minecraft.nbt.CompoundTag tag) {
        State restored = State.valueOf(tag.getString("state"));
        String node = tag.getString("current"); int steps = tag.getInt("transitions");
        if (!nodes.containsKey(node) || steps < 0 || steps > MAX_TRANSITIONS_PER_RUN)
            throw new IllegalArgumentException("执行存档无效");
        var counts = tag.getCompound("visits"); visits.clear();
        for (String id : counts.getAllKeys()) {
            int count = counts.getInt(id);
            if (!nodes.containsKey(id) || count < 0 || count > MAX_VISITS_PER_NODE)
                throw new IllegalArgumentException("执行访问计数无效");
            visits.put(id, count);
        }
        state = restored; current = node; transitions = steps; reason = tag.getString("reason");
        countedNode = node.equals(tag.getString("counted")) ? node : null;
    }
    private void fail(String message) { reason = message == null ? "任务执行失败" : message; state = State.FAILED; }
}
