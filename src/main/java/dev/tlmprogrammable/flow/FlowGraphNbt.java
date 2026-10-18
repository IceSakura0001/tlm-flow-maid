package dev.tlmprogrammable.flow;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Versioned, bounded NBT codec. Unknown future versions are preserved by callers and never reset. */
public final class FlowGraphNbt {
    public static final int VERSION = 1;
    private FlowGraphNbt() { }

    public static CompoundTag write(FlowGraph graph) {
        CompoundTag root = new CompoundTag(); root.putInt("version", VERSION);
        root.putUUID("id", graph.id()); root.putString("name", graph.name());
        ListTag nodes = new ListTag();
        for (FlowGraph.Node node : graph.nodes()) {
            CompoundTag tag = new CompoundTag(); tag.putString("id", node.id());
            tag.putString("kind", node.kind().name()); tag.putInt("x", node.x()); tag.putInt("y", node.y());
            CompoundTag settings = new CompoundTag();
            node.settings().forEach(settings::putString); tag.put("settings", settings); nodes.add(tag);
        }
        ListTag edges = new ListTag();
        for (FlowGraph.Edge edge : graph.edges()) {
            CompoundTag tag = new CompoundTag(); tag.putString("from", edge.from());
            tag.putString("port", edge.port().name()); tag.putString("to", edge.to()); edges.add(tag);
        }
        root.put("nodes", nodes); root.put("edges", edges); return root;
    }

    public static FlowGraph read(CompoundTag root) {
        if (root.getInt("version") != VERSION) throw new IllegalArgumentException("不支持的流程版本");
        if (!root.hasUUID("id") || !root.contains("nodes", 9) || !root.contains("edges", 9))
            throw new IllegalArgumentException("流程数据不完整");
        ListTag nodeTags = root.getList("nodes", 10), edgeTags = root.getList("edges", 10);
        if (nodeTags.size() > FlowGraph.MAX_NODES || edgeTags.size() > FlowGraph.MAX_EDGES)
            throw new IllegalArgumentException("流程数据超过安全容量");
        var nodes = new java.util.ArrayList<FlowGraph.Node>(nodeTags.size());
        for (int i = 0; i < nodeTags.size(); i++) {
            CompoundTag tag = nodeTags.getCompound(i);
            FlowGraph.Kind kind;
            try { kind = FlowGraph.Kind.valueOf(tag.getString("kind")); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("未知模块类型", e); }
            CompoundTag values = tag.getCompound("settings"); Map<String, String> settings = new LinkedHashMap<>();
            for (String key : values.getAllKeys()) settings.put(key, values.getString(key));
            nodes.add(new FlowGraph.Node(tag.getString("id"), kind, tag.getInt("x"), tag.getInt("y"), settings));
        }
        var edges = new java.util.ArrayList<FlowGraph.Edge>(edgeTags.size());
        for (int i = 0; i < edgeTags.size(); i++) {
            CompoundTag tag = edgeTags.getCompound(i);
            try { edges.add(new FlowGraph.Edge(tag.getString("from"), FlowGraph.Port.valueOf(tag.getString("port")), tag.getString("to"))); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("连线数据无效", e); }
        }
        return new FlowGraph(root.getUUID("id"), root.getString("name"), nodes, edges);
    }
}
