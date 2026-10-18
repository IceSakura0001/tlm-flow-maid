package dev.tlmprogrammable.flow;

import java.util.*;

/** Immutable, bounded graph definition used by the editor, serializer and server runner. */
public record FlowGraph(UUID id, String name, List<Node> nodes, List<Edge> edges) {
    public static final int MAX_NODES = 128;
    public static final int MAX_EDGES = 256;
    public static final int MAX_TEXT = 256;

    public enum Kind {
        START("开始", Family.CONTROL),
        MOVE("移动", Family.ACTION), STORE("存入容器", Family.ACTION), TAKE("取出物品", Family.ACTION),
        HARVEST("采集", Family.ACTION), ATTACK("攻击", Family.ACTION), FEED("饲养", Family.ACTION),
        BREED("繁殖", Family.ACTION), TRADE("交易", Family.ACTION), USE("使用", Family.ACTION),
        PATROL("巡逻防御", Family.ACTION), NATIVE_WORK("调用女仆工作", Family.ACTION),
        BRANCH("条件判断", Family.CONDITION), TRIGGER("触发条件", Family.CONDITION), END_CONDITION("结束条件", Family.CONDITION),
        LOOP_START("循环起点", Family.CONDITION), LOOP_END("循环终点", Family.CONDITION),
        END("结束", Family.CONTROL);
        private final String label;
        private final Family family;
        Kind(String label, Family family) { this.label = label; this.family = family; }
        public String label() { return label; }
        public Family family() { return family; }
    }
    public enum Family { CONTROL, ACTION, CONDITION }
    public enum Port { NEXT, YES, NO }
    public record Node(String id, Kind kind, int x, int y, Map<String, String> settings) {
        public Node {
            if (id == null || id.isBlank() || id.length() > 64) throw new IllegalArgumentException("无效节点ID");
            Objects.requireNonNull(kind, "kind");
            settings = Map.copyOf(settings == null ? Map.of() : settings);
            if (settings.size() > 24) throw new IllegalArgumentException("节点参数过多");
            for (var entry : settings.entrySet()) {
                if (entry.getKey().length() > 64 || entry.getValue().length() > MAX_TEXT)
                    throw new IllegalArgumentException("节点参数过长");
            }
        }
        public Node at(int x, int y) { return new Node(id, kind, x, y, settings); }
        public Node configure(String key, String value) {
            Map<String, String> next = new LinkedHashMap<>(settings);
            next.put(key, value);
            return new Node(id, kind, x, y, next);
        }
        public Node withoutSettings(String... keys) {
            Map<String, String> next = new LinkedHashMap<>(settings);
            for (String key : keys) next.remove(key);
            return new Node(id, kind, x, y, next);
        }
        public String setting(String key, String fallback) { return settings.getOrDefault(key, fallback); }
    }
    public record Edge(String from, Port port, String to) {
        public Edge { Objects.requireNonNull(from); Objects.requireNonNull(port); Objects.requireNonNull(to); }
    }

    public FlowGraph {
        Objects.requireNonNull(id, "id");
        name = name == null ? "我的任务" : name.strip();
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("任务名称须为1至64字");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        if (nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) throw new IllegalArgumentException("流程图超过容量限制");
        validate(nodes, edges);
    }

    public static FlowGraph empty(String name) {
        String start = UUID.randomUUID().toString(), end = UUID.randomUUID().toString();
        return new FlowGraph(UUID.randomUUID(), name, List.of(
                new Node(start, Kind.START, 360, 48, Map.of()),
                new Node(end, Kind.END, 360, 360, Map.of())),
                List.of(new Edge(start, Port.NEXT, end)));
    }

    private static void validate(List<Node> nodes, List<Edge> edges) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) if (byId.put(node.id(), node) != null) throw new IllegalArgumentException("节点ID重复");
        long starts = nodes.stream().filter(n -> n.kind() == Kind.START).count();
        long ends = nodes.stream().filter(n -> n.kind() == Kind.END).count();
        if (starts != 1 || ends != 1) throw new IllegalArgumentException("流程必须且只能有一个开始和结束节点");
        Set<String> edgeKeys = new HashSet<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            Node from = byId.get(edge.from()), to = byId.get(edge.to());
            if (from == null || to == null || from.id().equals(to.id())) throw new IllegalArgumentException("连线端点无效");
            if (from.kind() == Kind.END || to.kind() == Kind.START) throw new IllegalArgumentException("开始/结束节点的连线方向无效");
            if ((from.kind().family() == Family.CONDITION) != (edge.port() != Port.NEXT)) throw new IllegalArgumentException("条件节点须用是/否出口，普通节点只能用下一步出口");
            if (!edgeKeys.add(edge.from() + "/" + edge.port() + "/" + edge.to())) throw new IllegalArgumentException("重复连线");
            adjacency.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        }
        Set<String> exits = new HashSet<>();
        for (Edge edge : edges) if (!exits.add(edge.from() + "/" + edge.port()))
            throw new IllegalArgumentException("一个出口只能连接一个目标");
    }

    /** Full validation is applied when saving or starting, never while the player is editing. */
    public void validateExecutable() {
        Map<String, List<Edge>> outgoing = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        }
        for (Node node : nodes) {
            List<Edge> exits = outgoing.getOrDefault(node.id(), List.of());
            if (node.kind().family() == Family.CONDITION) {
                if (exits.size() != 2 || exits.stream().map(Edge::port).distinct().count() != 2)
                    throw new IllegalArgumentException("判断模块的“是”和“否”出口都必须连接");
            } else if (node.kind() != Kind.END && exits.size() != 1) {
                throw new IllegalArgumentException("每个非判断模块都必须且只能连接一个后续模块");
            }
        }
        Node start = nodes.stream().filter(n -> n.kind() == Kind.START).findFirst().orElseThrow();
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(start.id());
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!reachable.add(current)) continue;
            queue.addAll(adjacency.getOrDefault(current, List.of()));
        }
        if (reachable.size() != nodes.size()) throw new IllegalArgumentException("有未从开始节点连通的模块");
        Node end = nodes.stream().filter(n -> n.kind() == Kind.END).findFirst().orElseThrow();
        Map<String, List<String>> reverse = new HashMap<>();
        for (Edge edge : edges) reverse.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
        Set<String> canReachEnd = new HashSet<>();
        ArrayDeque<String> reverseQueue = new ArrayDeque<>(); reverseQueue.add(end.id());
        while (!reverseQueue.isEmpty()) {
            String current = reverseQueue.removeFirst();
            if (canReachEnd.add(current)) reverseQueue.addAll(reverse.getOrDefault(current, List.of()));
        }
        if (!canReachEnd.containsAll(reachable)) throw new IllegalArgumentException("存在无法离开循环并到达结束节点的流程分支");
        // A drawn exit is insufficient if its condition can never select that exit.
        Map<String, Node> indexed = new HashMap<>(); nodes.forEach(node -> indexed.put(node.id(), node));
        Map<String, List<String>> possible = new HashMap<>(), possibleReverse = new HashMap<>();
        for (Edge edge : edges) {
            Node from = indexed.get(edge.from());
            String condition = from.setting("conditionType", from.kind() == Kind.LOOP_END ? "loop_count" : "always");
            if (from.kind().family() == Family.CONDITION && (condition.equals("always") && edge.port() == Port.NO
                    || condition.equals("never") && edge.port() == Port.YES
                    || condition.equals("loop_count") && from.setting("loopCount", "3").equals("1") && edge.port() == Port.YES)) continue;
            possible.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
            possibleReverse.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
        }
        Set<String> actual = new HashSet<>(), exitsPossible = new HashSet<>();
        queue.clear(); queue.add(start.id());
        while (!queue.isEmpty()) { String current = queue.removeFirst(); if (actual.add(current)) queue.addAll(possible.getOrDefault(current, List.of())); }
        queue.add(end.id());
        while (!queue.isEmpty()) { String current = queue.removeFirst(); if (exitsPossible.add(current)) queue.addAll(possibleReverse.getOrDefault(current, List.of())); }
        if (!exitsPossible.containsAll(actual)) throw new IllegalArgumentException("循环出口不可触发：请检查恒真、恒假或循环次数条件");
    }

    public FlowGraph replace(Node node) {
        List<Node> next = new ArrayList<>(nodes);
        int index = indexOf(node.id());
        if (index < 0) throw new IllegalArgumentException("节点不存在");
        next.set(index, node);
        return new FlowGraph(id, name, next, edges);
    }
    public FlowGraph add(Node node) {
        if (nodes.size() >= MAX_NODES) throw new IllegalArgumentException("模块数量已达上限");
        List<Node> next = new ArrayList<>(nodes); next.add(node);
        return new FlowGraph(id, name, next, edges);
    }
    public FlowGraph remove(String nodeId) {
        Node node = nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("节点不存在"));
        if (node.kind() == Kind.START || node.kind() == Kind.END) throw new IllegalArgumentException("开始和结束模块不能删除");
        List<Node> nextNodes = nodes.stream().filter(n -> !n.id().equals(nodeId)).toList();
        List<Edge> nextEdges = edges.stream().filter(e -> !e.from().equals(nodeId) && !e.to().equals(nodeId)).toList();
        return new FlowGraph(id, name, nextNodes, nextEdges);
    }
    public FlowGraph connect(Edge edge) {
        List<Edge> next = new ArrayList<>(edges);
        next.removeIf(e -> e.from().equals(edge.from()) && e.port() == edge.port());
        next.add(edge);
        return new FlowGraph(id, name, nodes, next);
    }
    public FlowGraph disconnect(String from, Port port) {
        List<Edge> next = edges.stream().filter(edge -> !(edge.from().equals(from) && edge.port() == port)).toList();
        return new FlowGraph(id, name, nodes, next);
    }
    public int indexOf(String nodeId) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(nodeId)) return i;
        return -1;
    }
}
