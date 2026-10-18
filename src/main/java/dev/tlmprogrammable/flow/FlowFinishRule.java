package dev.tlmprogrammable.flow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Shared editor/server schema. Time is active server ticks, never wall-clock time. */
public final class FlowFinishRule {
    public static final List<String> MODES = List.of("clear", "duration", "count", "loot", "quiet");
    public static final List<String> UNITS = List.of("seconds", "minutes", "days");
    public static final long MAX_TICKS = 30L * 24000;
    private FlowFinishRule() { }
    public static boolean applies(FlowGraph.Kind kind) {
        return kind == FlowGraph.Kind.ATTACK || kind == FlowGraph.Kind.HARVEST || kind == FlowGraph.Kind.PATROL || kind == FlowGraph.Kind.NATIVE_WORK || kind == FlowGraph.Kind.FEED || kind == FlowGraph.Kind.BREED;
    }
    public static List<String> modes(FlowGraph.Node node) {
        if (node.kind() == FlowGraph.Kind.FEED || node.kind() == FlowGraph.Kind.BREED) return List.of("clear", "duration", "count", "quiet");
        return node.kind() == FlowGraph.Kind.NATIVE_WORK ? List.of("duration", "loot", "inventory", "count") : MODES;
    }
    public static String mode(FlowGraph.Node node) {
        return node.setting("finishMode", node.kind() == FlowGraph.Kind.PATROL || node.kind() == FlowGraph.Kind.NATIVE_WORK ? "duration" : "clear");
    }
    public static String label(String mode) {
        return switch (mode) {
            case "duration" -> "工作指定时长";
            case "count" -> "处理指定数量目标";
            case "loot" -> "新拾取指定数量物品";
            case "inventory" -> "库存物品达到指定数量";
            case "quiet" -> "连续一段时间无目标";
            default -> "清空范围内所有目标";
        };
    }
    public static String unitLabel(String unit) {
        return switch (unit) { case "minutes" -> "分钟"; case "days" -> "游戏日"; default -> "秒"; };
    }
    public static long durationTicks(FlowGraph.Node node) {
        String unit = node.setting("finishUnit", "seconds");
        if (!UNITS.contains(unit)) throw new IllegalArgumentException("结束规则：时间单位无效");
        try {
            BigDecimal amount = new BigDecimal(node.setting("finishDuration", "60"));
            if (amount.signum() <= 0) throw new NumberFormatException();
            long factor = switch (unit) { case "minutes" -> 1200; case "days" -> 24000; default -> 20; };
            BigDecimal ticks = amount.multiply(BigDecimal.valueOf(factor));
            if (ticks.compareTo(BigDecimal.valueOf(MAX_TICKS)) > 0) throw new NumberFormatException();
            if (ticks.compareTo(BigDecimal.ONE) <= 0) return 1;
            return ticks.setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("结束规则：时长必须大于 0 且不超过 30 游戏日（支持小数）");
        }
    }
    public static int requiredCount(FlowGraph.Node node) {
        try {
            int value = Integer.parseInt(node.setting("finishCount", "1"));
            if (value < 1 || value > 1000000) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("结束规则：数量须为 1～1000000 的整数"); }
    }
    public static void validate(FlowGraph.Node node) {
        if (!applies(node.kind())) return;
        String mode = mode(node);
        if (!modes(node).contains(mode)) throw new IllegalArgumentException("此模块不支持该结束规则");
        if (mode.equals("duration") || mode.equals("quiet")) durationTicks(node);
        if (mode.equals("count") || mode.equals("loot") || mode.equals("inventory")) requiredCount(node);
    }
}
