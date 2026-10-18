package dev.tlmprogrammable.flow;

import java.util.List;

/** Item actions share explicit quantities and recoverable failure policies. */
public final class FlowItemRule {
    public static final List<String> POLICIES = List.of("help", "skip", "wait");
    private FlowItemRule() { }
    public static boolean applies(FlowGraph.Kind kind) {
        return kind == FlowGraph.Kind.TAKE || kind == FlowGraph.Kind.STORE || kind == FlowGraph.Kind.HARVEST
                || kind == FlowGraph.Kind.FEED || kind == FlowGraph.Kind.BREED || kind == FlowGraph.Kind.TRADE || kind == FlowGraph.Kind.USE;
    }
    public static boolean applies(FlowGraph.Node node) {
        return applies(node.kind()) || FlowFinishRule.applies(node.kind())
                && (FlowFinishRule.mode(node).equals("loot") || FlowFinishRule.mode(node).equals("inventory"));
    }
    public static String policy(FlowGraph.Node node) { return node.setting("itemFailure", "help"); }
    public static String label(String policy) {
        return switch (policy) { case "skip" -> "物品不足：跳过此步"; case "wait" -> "物品不足：原地等待"; default -> "物品不足：原地等待并求助"; };
    }
    public static int quantity(FlowGraph.Node node) {
        try {
            int count = Integer.parseInt(node.setting("itemCount", node.kind() == FlowGraph.Kind.STORE ? "0" : "1"));
            if (count < (node.kind() == FlowGraph.Kind.STORE ? 0 : 1) || count > 1000000) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("取物数量须为1～1000000；存物允许0代表全部"); }
    }
    public static void validate(FlowGraph.Node node) {
        if (applies(node) && !POLICIES.contains(policy(node))) throw new IllegalArgumentException("物品失败处理选项无效");
        if (node.kind() == FlowGraph.Kind.TAKE || node.kind() == FlowGraph.Kind.STORE) quantity(node);
    }
}
