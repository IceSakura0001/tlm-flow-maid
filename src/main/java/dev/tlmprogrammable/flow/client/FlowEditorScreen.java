package dev.tlmprogrammable.flow.client;

import dev.tlmprogrammable.flow.FlowGraph;
import dev.tlmprogrammable.flow.FlowGraph.Edge;
import dev.tlmprogrammable.flow.FlowGraph.Kind;
import dev.tlmprogrammable.flow.FlowGraph.Node;
import dev.tlmprogrammable.flow.FlowGraph.Port;
import dev.tlmprogrammable.flow.FlowGraphNbt;
import dev.tlmprogrammable.flow.FlowFinishRule;
import dev.tlmprogrammable.flow.FlowItemRule;
import dev.tlmprogrammable.flow.FlowDiagram;
import dev.tlmprogrammable.flow.server.FlowNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/** First clean flow-editor implementation: draggable palette, movable nodes and orthogonal snapped links. */
public final class FlowEditorScreen extends Screen {
    private static final int PALETTE_W = 148, NODE_W = 166, NODE_H = 48, GRID = 12, ACTION_COUNT = 11;
    private final int maidEntityId;
    private final UUID maidUuid;
    private FlowGraph graph = FlowGraph.empty("新建任务");
    private final java.util.List<RegistrySuggestBox> suggestionInputs = new java.util.ArrayList<>();
    private String taskName;
    private boolean saving;
    private int paletteScroll;
    private int panelScroll, panelMaximum;
    private String panelNode;
    private String nativePageNode;
    private int nativeTaskPage;
    private Kind paletteDrag;
    private String selected;
    private final java.util.Set<String> selectedNodes = new java.util.LinkedHashSet<>();
    private FlowGraph dragBase;
    private double dragAnchorX, dragAnchorY;
    private boolean avoidWires = true;
    private FlowDiagram.Layout diagramLayout = FlowDiagram.Layout.VERTICAL;
    private java.util.Map<String, FlowDiagram.Point> beforeArrange = java.util.Map.of();
    private final java.util.Set<net.minecraft.client.gui.components.AbstractWidget> mapTools = new java.util.HashSet<>();
    private FlowGraph routedGraph;
    private boolean routedAvoid;
    private final java.util.Map<Edge, java.util.List<FlowDiagram.Point>> routes = new java.util.HashMap<>();
    private int routeBudget;
    private int mapToolsBottom = 48;
    private String moving;
    private int moveDx, moveDy;
    private String edgeFrom;
    private Port edgePort;
    private boolean rewiringEdge;
    private Edge selectedEdge;
    private Edge edgePressed;
    private double edgePressX, edgePressY;
    private double pointerX, pointerY, panX, panY;
    private double zoom = 1.0;
    private int coordinateLabelX = -1, coordinateLabelY = -1;
    private boolean panning;
    private double dragX, dragY;
    private String message = "拖左侧模块到画布；从模块下方出口拖线到目标模块";
    private String syncWarning = "";
    private long lastDraftWrite;
    private boolean draftDirty;
    private static final long DRAFT_WRITE_INTERVAL_MS = 800L;

    public FlowEditorScreen(int maidEntityId, UUID maidUuid) {
        this(maidEntityId, maidUuid, FlowGraph.empty("新建任务"));
    }

    public FlowEditorScreen(int maidEntityId, UUID maidUuid, FlowGraph initial) {
        super(Component.literal("任务流程编辑器"));
        this.maidEntityId = maidEntityId;
        this.maidUuid = maidUuid;
        graph = initial; taskName = graph.name();
        Node start = graph.nodes().stream().filter(node -> node.kind() == Kind.START).findFirst().orElseThrow();
        avoidWires = Boolean.parseBoolean(start.setting("diagramAvoid", "true"));
        try { diagramLayout = FlowDiagram.Layout.valueOf(start.setting("diagramLayout", "VERTICAL")); } catch (IllegalArgumentException ignored) { }
    }
    public int maidEntityId() { return maidEntityId; }
    public UUID maidUuid() { return maidUuid; }

    @Override protected void init() {
        setFocused(null);
        clearWidgets(); mapTools.clear();
        suggestionInputs.clear();
        coordinateLabelX = coordinateLabelY = -1;
        EditBox name = new EditBox(font, PALETTE_W + 8, 3, nameWidth(), 18, Component.literal("任务名称"));
        name.setMaxLength(64); name.setValue(taskName); name.setHint(Component.literal("任务名称"));
        name.setResponder(value -> {
            taskName = value;
            if (!value.isBlank()) graph = new FlowGraph(graph.id(), value.strip(), graph.nodes(), graph.edges());
        });
        addRenderableWidget(name);
        addMapTools();
        if (selected == null) return;
        Node node = node(selected);
        if (node == null) { selected = null; return; }
        if (!java.util.Objects.equals(panelNode, selected)) { panelNode = selected; panelScroll = 0; }
        int x = Math.max(PALETTE_W + 8, width - 216);
        addRenderableWidget(Button.builder(Component.literal("模块：" + node.kind().label()), b -> { })
                .bounds(x + 8, 42, 200, 20).build()).active = false;
        int y = 68;
        String mode = node.setting("targetMode", defaultMode(node.kind()));
        if (node.kind() == Kind.MOVE || node.kind() == Kind.STORE || node.kind() == Kind.TAKE || node.kind() == Kind.ATTACK) {
            String title = node.kind() == Kind.MOVE ? "移动到：" : node.kind() == Kind.ATTACK ? "攻击目标：" : "容器位置：";
            addRenderableWidget(Button.builder(Component.literal(title + mode), b -> cycleTargetMode(node))
                    .bounds(x + 8, y, 200, 20).build());
            y += 28;
        }

        if (node.kind() == Kind.NATIVE_WORK) {
            y = addNativeTaskPicker(node, x, y);
            addDisabledInfo(x, y, "沿用母模组工具、背包和工作配置"); y += 28;
        } else if (node.kind() == Kind.MOVE) {
            switch (mode) {
                case "主人所在位置" -> {
                    addDisabledInfo(x, y, "主人前方 2 格；寻路失败时传送");
                }
                case "准星点选位置" -> { addPicker(node, x, y, WorldPickSession.Purpose.LOCATION, "准星选择位置"); }
                case "寻找目标" -> {
                    addTextInput(node, "filter", x, y, 200, "目标方块/实体（可输入中文）"); y += 28;
                    addRadius(node, x, y); y += 28;
                }
                case "坐标（高级）" -> addCoordinateInputs(node, x, y + 14);
                default -> { }
            }
            y += 52;
        } else if (node.kind() == Kind.STORE || node.kind() == Kind.TAKE) {
            addTextInput(node, "filter", x, y, 200, node.kind() == Kind.STORE ? "要存放的物品（支持中文）" : "要取出的物品（支持中文）");
            y += 28;
            addDisabledInfo(x, y, node.kind() == Kind.TAKE ? "少附魔→高耐久，差距≤5%时随机" : "按种类识别，忽略耐久/名称/附魔"); y += 28;
            addTextInput(node, "itemCount", x, y, 200, node.kind() == Kind.TAKE ? "取物数量（默认1）" : "存物数量（0代表全部）"); y += 28;
            if (mode.equals("准星点选")) addPicker(node, x, y, WorldPickSession.Purpose.CONTAINER, "准星选择容器");
            else if (mode.equals("坐标（高级）")) addCoordinateInputs(node, x, y + 14);
            y += 48;
        } else if (node.kind() == Kind.ATTACK) {
            if (mode.equals("指定实体类型")) addTextInput(node, "filter", x, y, 200, "实体类型（名称或 ID）");
            else addPicker(node, x, y, WorldPickSession.Purpose.ENTITY, "准星选择攻击目标");
            y += 28; addRadius(node, x, y); y += 28;
        } else if (node.kind() == Kind.FEED || node.kind() == Kind.BREED) {
            addTextInput(node, "animalType", x, y, 200, "动物类型（支持中文联想）"); y += 28;
            addTextInput(node, "filter", x, y, 200, "喂食物品（如小麦，支持中文）"); y += 28;
            addPicker(node, x, y, WorldPickSession.Purpose.LOCATION, "准星选择工作范围中心"); y += 28;
            addRadius(node, x, y); y += 28;
            addDisabledInfo(x, y, node.kind() == Kind.FEED ? "饲养幼崽，促进成长；不催情" : "喂食成年动物；数量按实际出生幼崽计"); y += 28;
        } else if (node.kind().family() == FlowGraph.Family.CONDITION) {
            String condition = node.setting("conditionType", node.kind() == Kind.LOOP_END ? "loop_count" : "always");
            addRenderableWidget(Button.builder(Component.literal("条件：" + conditionLabel(condition)), b -> cycleCondition(node))
                    .bounds(x + 8, y, 200, 20).build()); y += 28;
            if (condition.equals("loop_count")) addTextInput(node, "loopCount", x, y, 200, "循环次数（默认 3）");
            if (condition.equals("has_item")) addTextInput(node, "filter", x, y, 200, "背包中的物品");
        } else if (node.kind() == Kind.HARVEST || node.kind() == Kind.PATROL || node.kind() == Kind.USE) {
            addTextInput(node, "filter", x, y, 200, node.kind() == Kind.PATROL ? "实体类型（留空防御敌对生物）" : "目标/物品（可输入中文）"); y += 28;
            if (node.kind() == Kind.HARVEST || node.kind() == Kind.PATROL) {
                addPicker(node, x, y, WorldPickSession.Purpose.LOCATION, "准星选择范围中心"); y += 28;
                addRadius(node, x, y); y += 28;
            }
        } else if (node.kind().family() == FlowGraph.Family.ACTION) {
            addTextInput(node, "filter", x, y, 200, "目标/物品（可输入中文）");
        }

        if (FlowItemRule.applies(node)) {
            String policy = FlowItemRule.policy(node);
            addRenderableWidget(Button.builder(Component.literal(FlowItemRule.label(policy)), button -> {
                configure(node.id(), "itemFailure", FlowItemRule.POLICIES.get((FlowItemRule.POLICIES.indexOf(policy) + 1) % FlowItemRule.POLICIES.size())); init();
            }).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("失败后按此策略处理；等待每5秒复查，求助只发送给所有者")))
                    .bounds(x + 8, y + 8, 200, 20).build()); y += 36;
        }
        if (FlowFinishRule.applies(node.kind())) addFinishRule(node, x, y + 8);
        Button delete = null;
        if (node.kind() != Kind.START && node.kind() != Kind.END) {
            delete = addRenderableWidget(Button.builder(Component.literal("删除此模块"), b -> deleteSelected())
                    .bounds(x + 8, height - 54, 200, 20).build());
        }
        layoutPanel(delete);
    }
    /** Reuse TLM's actual icon/name task buttons; clicking edits the node, never the live maid. */
    private int addNativeTaskPicker(Node node, int x, int y) {
        var maid = minecraft.level != null && minecraft.level.getEntity(maidEntityId)
                instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid entity ? entity : null;
        var tasks = com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getTaskIndex().stream()
                .filter(task -> !task.getUid().equals(dev.tlmprogrammable.flow.server.FlowWorkTask.UID))
                .filter(task -> maid == null || !task.isHidden(maid)).toList();
        final int pageSize = 12;
        int pages = Math.max(1, (tasks.size() + pageSize - 1) / pageSize);
        if (!node.id().equals(nativePageNode)) {
            nativePageNode = node.id(); nativeTaskPage = 0;
            for (int i = 0; i < tasks.size(); i++)
                if (tasks.get(i).getUid().toString().equals(node.setting("nativeTask", ""))) nativeTaskPage = i / pageSize;
        }
        nativeTaskPage = Math.max(0, Math.min(pages - 1, nativeTaskPage));
        int left = x + 66;
        var texture = new net.minecraft.resources.ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_task.png");
        var previous = addRenderableWidget(Button.builder(Component.literal("▲"), button -> {
            nativeTaskPage--; init();
        }).bounds(left, y, 22, 18).build());
        previous.active = nativeTaskPage > 0;
        addRenderableWidget(Button.builder(Component.literal((nativeTaskPage + 1) + "/" + pages), button -> { })
                .bounds(left + 23, y, 36, 18).build()).active = false;
        var next = addRenderableWidget(Button.builder(Component.literal("▼"), button -> {
            nativeTaskPage++; init();
        }).bounds(left + 60, y, 23, 18).build());
        next.active = nativeTaskPage + 1 < pages;
        y += 20;
        for (int index = nativeTaskPage * pageSize; index < Math.min(tasks.size(), (nativeTaskPage + 1) * pageSize); index++) {
            var task = tasks.get(index);
            boolean chosen = task.getUid().toString().equals(node.setting("nativeTask", ""));
            var row = new com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.TaskButton(
                    task, true, left, y, 83, 19, 93, 28, 20, texture, button -> {
                Node current = node(node.id());
                if (current == null) return;
                String label = task.getName().getString();
                graph = graph.replace(current.configure("nativeTask", task.getUid().toString())
                        .configure("nativeTaskName", label.substring(0, Math.min(label.length(), FlowGraph.MAX_TEXT))));
                saveDraftQuietly(); init();
            }) {
                @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                    super.renderWidget(graphics, mouseX, mouseY, partialTick);
                    if (chosen) graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), 0xFFFFD166);
                }
            };
            row.setMessage(task.getName());
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(task.getName()));
            addRenderableWidget(row); y += 19;
        }
        if (tasks.isEmpty()) { addDisabledInfo(x, y, "暂无可用女仆工作"); y += 24; }
        addDisabledInfo(x, y + 5, "已选：" + node.setting("nativeTaskName", "请点击上方工作按钮"));
        return y + 33;
    }

    boolean avoidsWires() { return avoidWires; }
    String layoutLabel() { return diagramLayout.label; }
    void toggleAvoidance() { avoidWires = !avoidWires; storeDiagramOptions(); }
    void cycleLayout() { diagramLayout = FlowDiagram.Layout.values()[(diagramLayout.ordinal() + 1) % FlowDiagram.Layout.values().length]; storeDiagramOptions(); }

    private void addMapTools() {
        int available = Math.max(64, (selected == null ? width : width - 224) - PALETTE_W - 8);
        int columns = available >= 300 ? 4 : available >= 144 ? 2 : 1;
        int cell = Math.max(32, (available - (columns - 1) * 3) / columns);
        String[] labels = {"设置", "排列：" + diagramLayout.label, "自动整理", "撤销整理"};
        String[] help = {"通用寻路、禁拆保护与流程图设置", "在设置中选择排列规则", "按当前规则整理全部卡片，连线与任务参数保持", "恢复上一次整理前的位置，保留之后的参数修改"};
        for (int index = 0; index < labels.length; index++) {
            final int action = index;
            Button button = Button.builder(Component.literal(fit(labels[index], cell - 8)), b -> {
                if (action == 0 || action == 1) { minecraft.setScreen(new FlowSettingsScreen(this)); }
                if (action == 2) arrangeDiagram();
                if (action == 3) { graph = FlowDiagram.restorePositions(graph, beforeArrange); beforeArrange = java.util.Map.of(); saveDraftQuietly(); init(); message = "已恢复整理前的位置"; }
            }).bounds(PALETTE_W + 4 + index % columns * (cell + 3), 28 + index / columns * 19, cell, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(help[index]))).build();
            if (index == 3) button.active = !beforeArrange.isEmpty();
            mapTools.add(addRenderableWidget(button));
        }
        mapToolsBottom = 28 + ((labels.length + columns - 1) / columns) * 19;
    }
    private void storeDiagramOptions() {
        Node start = graph.nodes().stream().filter(node -> node.kind() == Kind.START).findFirst().orElseThrow();
        graph = graph.replace(start.configure("diagramAvoid", Boolean.toString(avoidWires)).configure("diagramLayout", diagramLayout.name())); saveDraftQuietly();
    }
    private void arrangeDiagram() {
        java.util.Map<String, FlowDiagram.Point> positions = new java.util.HashMap<>();
        graph.nodes().forEach(node -> positions.put(node.id(), new FlowDiagram.Point(node.x(), node.y()))); beforeArrange = positions;
        graph = FlowDiagram.arrange(graph, diagramLayout); saveDraftQuietly();
        Node start = graph.nodes().stream().filter(node -> node.kind() == Kind.START).findFirst().orElseThrow();
        zoom = 1;
        panX = ((selected == null ? width : width - 224) - PALETTE_W) / 2.0 - (start.x() - PALETTE_W + NODE_W / 2.0);
        panY = mapToolsBottom + 18 - start.y();
        message = "已按“" + diagramLayout.label + "”整理；可撤销整理，执行顺序保持不变"; init();
    }

    private void layoutPanel(Button delete) {
        java.util.List<net.minecraft.client.gui.components.AbstractWidget> body = children().stream()
                .filter(child -> child instanceof net.minecraft.client.gui.components.AbstractWidget)
                .map(child -> (net.minecraft.client.gui.components.AbstractWidget) child)
                .filter(widget -> widget != delete && !mapTools.contains(widget) && widget.getY() >= 68).toList();
        int bottom = body.stream().mapToInt(widget -> widget.getY() + widget.getHeight()).max().orElse(68);
        panelMaximum = Math.max(0, bottom - (height - 68)); panelScroll = Math.max(0, Math.min(panelMaximum, panelScroll));
        for (var widget : body) {
            widget.setY(widget.getY() - panelScroll);
            widget.visible = widget.getY() >= 68 && widget.getY() + widget.getHeight() <= height - 68;
        }
        if (coordinateLabelY >= 0) coordinateLabelY -= panelScroll;
    }

    private static String defaultMode(Kind kind) {
        return switch (kind) {
            case MOVE -> "主人所在位置";
            case ATTACK -> "指定实体类型";
            case STORE, TAKE -> "准星点选";
            default -> "准星点选";
        };
    }

    private void addDisabledInfo(int x, int y, String text) {
        addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(text, 190)), b -> { })
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(text)))
                .bounds(x + 8, y, 200, 20).build()).active = false;
    }

    private void addTextInput(Node node, String key, int x, int y, int w, String hint) {
        if (key.equals("filter") || key.equals("finishItem") || key.equals("nativeTask") || key.equals("animalType") || key.equals("roadDenyBlock")) {
            String nameKey = key + "Name";
            String kindKey = key + "Kind";
            RegistrySuggestBox.Domain domain = key.equals("roadDenyBlock") ? RegistrySuggestBox.Domain.BLOCK : key.equals("animalType") ? RegistrySuggestBox.Domain.ENTITY : key.equals("nativeTask") ? RegistrySuggestBox.Domain.WORK : key.equals("finishItem") ? RegistrySuggestBox.Domain.ITEM : switch (node.kind()) {
                case MOVE -> RegistrySuggestBox.Domain.TARGET;
                case HARVEST -> RegistrySuggestBox.Domain.BLOCK;
                case ATTACK, PATROL, NATIVE_WORK -> RegistrySuggestBox.Domain.ENTITY;
                default -> RegistrySuggestBox.Domain.ITEM;
            };
            RegistrySuggestBox input = new RegistrySuggestBox(font, x + 8, y, w, hint,
                    node.setting(nameKey, node.setting(key, "")), domain,
                    value -> {
                        Node current = node(node.id());
                        if (current != null) graph = graph.replace(current.withoutSettings(nameKey, kindKey).configure(key, value));
                        saveDraftQuietly();
                    }, candidate -> {
                        Node current = node(node.id());
                        if (current != null) graph = graph.replace(current.configure(key, candidate.id())
                                .configure(nameKey, candidate.name().substring(0, Math.min(candidate.name().length(), FlowGraph.MAX_TEXT)))
                                .configure(kindKey, candidate.kind()));
                        saveDraftQuietly();
                    });
            suggestionInputs.add(input); addRenderableWidget(input); return;
        }
        EditBox input = new EditBox(font, x + 8, y, w, 20, Component.literal(hint));
        input.setMaxLength(FlowGraph.MAX_TEXT); input.setValue(node.setting(key,
                key.equals("finishDuration") ? "60" : key.equals("finishCount") ? "1" : "")); input.setHint(Component.literal(hint));
        input.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(hint)));
        input.setResponder(value -> configure(node.id(), key, value)); addRenderableWidget(input);
    }
    private void addFinishRule(Node node, int x, int y) {
        String mode = FlowFinishRule.mode(node);
        addRenderableWidget(Button.builder(Component.literal("结束：" + FlowFinishRule.label(mode)), button -> {
            Node current = node(node.id()); if (current == null) return;
            String next = FlowFinishRule.modes(current).get((FlowFinishRule.modes(current).indexOf(FlowFinishRule.mode(current)) + 1) % FlowFinishRule.modes(current).size());
            graph = graph.replace(current.withoutSettings("finishDuration", "finishUnit", "finishCount", "finishItem", "finishItemName", "finishItemKind")
                    .configure("finishMode", next)); saveDraftQuietly(); init();
        }).bounds(x + 8, y, 200, 20).build()); y += 28;
        if (mode.equals("duration") || mode.equals("quiet")) {
            addTextInput(node, "finishDuration", x, y, 116, mode.equals("quiet") ? "连续无目标时长（支持小数）" : "工作时长（支持小数）");
            String unit = node.setting("finishUnit", "seconds");
            addRenderableWidget(Button.builder(Component.literal(FlowFinishRule.unitLabel(unit)), button -> {
                Node current = node(node.id()); if (current == null) return;
                String currentUnit = current.setting("finishUnit", "seconds");
                configure(node.id(), "finishUnit", FlowFinishRule.UNITS.get((FlowFinishRule.UNITS.indexOf(currentUnit) + 1) % FlowFinishRule.UNITS.size())); init();
            }).bounds(x + 130, y, 78, 20).build()); y += 28;
            addDisabledInfo(x, y, "仅计工作时间；1 游戏日 = 20 分钟");
        } else if (mode.equals("count") || mode.equals("loot") || mode.equals("inventory")) {
            if (mode.equals("count") && node.kind() == Kind.NATIVE_WORK) { addTextInput(node, "filter", x, y, 200, "要击杀的实体类型（中文联想）"); y += 28; }
            if (mode.equals("loot") || mode.equals("inventory")) { addTextInput(node, "finishItem", x, y, 200, mode.equals("inventory") ? "目标库存物品（中文联想）" : "要拾取的物品（中文联想）"); y += 28; }
            addTextInput(node, "finishCount", x, y, 200, mode.equals("inventory") ? "库存目标数量（包含原有物品）" : mode.equals("loot") ? "新拾取数量（原有库存不计）"
                    : node.kind() == Kind.FEED ? "成功喂养次数" : node.kind() == Kind.BREED ? "实际出生幼崽数量" : node.kind() == Kind.HARVEST ? "成功采集方块数量" : "女仆击杀目标数量"); y += 28;
            addDisabledInfo(x, y, mode.equals("loot") ? "本节点新拾取物品数，达标后继续" : "数量不足时等待新目标，达标后继续");
        } else addDisabledInfo(x, y, node.kind() == Kind.FEED ? "范围内没有仍可成长的幼崽后结束" : node.kind() == Kind.BREED ? "无可繁殖动物且无待出生幼崽后结束" : "清空已加载工作范围后继续下一步");
    }
    private static String conditionLabel(String type) {
        return switch (type) {
            case "never" -> "始终不成立";
            case "day" -> "白天";
            case "night" -> "夜晚";
            case "owner_near" -> "主人在 8 格内";
            case "has_item" -> "背包有指定物品";
            case "loop_count" -> "循环指定次数";
            default -> "始终成立";
        };
    }
    private void cycleCondition(Node node) {
        java.util.List<String> types = java.util.List.of("always", "never", "day", "night", "owner_near", "has_item", "loop_count");
        String current = node.setting("conditionType", node.kind() == Kind.LOOP_END ? "loop_count" : "always");
        String next = types.get((types.indexOf(current) + 1) % types.size());
        configure(node.id(), "conditionType", next); init();
    }

    private void addPicker(Node node, int x, int y, WorldPickSession.Purpose purpose, String label) {
        String key = purpose == WorldPickSession.Purpose.ENTITY ? "attackTarget" : "target";
        addRenderableWidget(Button.builder(Component.literal(label), b -> WorldPickSession.begin(this,
                        maidEntityId, maidUuid, purpose, picked -> configure(node.id(), key, picked)))
                .bounds(x + 8, y, 200, 20).build());
    }

    private void addRadius(Node node, int x, int y) {
        addRenderableWidget(Button.builder(Component.literal("搜索范围：" + node.setting("radius", "2") + " 区块"),
                        b -> cycleRadius(node)).bounds(x + 8, y, 200, 20).build());
    }

    private void addCoordinateInputs(Node node, int x, int y) {
        coordinateLabelX = x + 8; coordinateLabelY = y;
        String[] keys = {"targetX", "targetY", "targetZ"};
        String[] hints = {"X", "Y", "Z"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i], hint = hints[i];
            EditBox input = new EditBox(font, x + 8 + i * 66, y, 60, 20, Component.literal(hint));
            input.setMaxLength(12); input.setValue(node.setting(key, ""));
            input.setResponder(value -> configure(node.id(), key, value)); addRenderableWidget(input);
        }
    }

    private void configure(String id, String key, String value) {
        Node current = node(id);
        if (current == null) return;
        graph = graph.replace(current.configure(key, value));
        saveDraftQuietly();
    }

    private void cycleTargetMode(Node node) {
        node = node(node.id()); if (node == null) return;
        String current = node.setting("targetMode", defaultMode(node.kind()));
        String next;
        if (node.kind() == Kind.MOVE) next = switch (current) {
            case "主人所在位置" -> "准星点选位置";
            case "准星点选位置" -> "寻找目标";
            case "寻找目标" -> "坐标（高级）";
            default -> "主人所在位置";
        };
        else if (node.kind() == Kind.ATTACK) next = current.equals("指定实体类型") ? "指定具体目标" : "指定实体类型";
        else next = current.equals("准星点选") ? "坐标（高级）" : "准星点选";
        Node updated = node.withoutSettings("target", "targetX", "targetY", "targetZ", "attackTarget");
        if (node.kind() == Kind.MOVE && next.equals("主人所在位置")) updated = updated.withoutSettings("filter", "filterName", "filterKind", "radius");
        graph = graph.replace(updated.configure("targetMode", next));
        selected = node.id(); selectedEdge = null; saveDraftQuietly(); init();
    }
    private void cycleRadius(Node node) {
        node = node(node.id()); if (node == null) return;
        int current;
        try { current = Integer.parseInt(node.setting("radius", "2")); } catch (NumberFormatException e) { current = 2; }
        int next = current >= (node.kind() == Kind.HARVEST ? 4 : 8) ? 1 : current + 1;
        graph = graph.replace(node.configure("radius", Integer.toString(next))); saveDraftQuietly(); init();
    }

    private static Kind[] palette() {
        return new Kind[]{Kind.MOVE, Kind.STORE, Kind.TAKE, Kind.HARVEST, Kind.ATTACK, Kind.FEED,
                Kind.BREED, Kind.TRADE, Kind.USE, Kind.PATROL, Kind.NATIVE_WORK, Kind.BRANCH, Kind.TRIGGER, Kind.END_CONDITION,
                Kind.LOOP_START, Kind.LOOP_END};
    }

    private int paletteY(int index) {
        return (index < ACTION_COUNT ? 49 + index * 25 : 49 + ACTION_COUNT * 25 + 31 + (index - ACTION_COUNT) * 25) - paletteScroll;
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF111820);
        g.fill(0, 0, width, 25, 0xFF202C37);
        g.drawString(font, "女仆任务 · 拖拽流程图", 8, 8, 0xFFFFFFFF);
        g.drawString(font, saving ? "保存中…" : "保存任务", width - 170, 8, 0xFFBCE1FF);
        g.drawString(font, "检查流程", width - 82, 8, 0xFFBCE1FF);
        g.fill(0, 25, PALETTE_W, height - 20, 0xFF18232D);
        g.enableScissor(0, 26, PALETTE_W, height - 44);
        g.drawString(font, "动作模块", 10, 32 - paletteScroll, 0xFFFFD678);
        g.drawString(font, "判断与循环", 10, 49 + ACTION_COUNT * 25 + 9 - paletteScroll, 0xFF86D9E7);
        Kind[] kinds = palette();
        for (int i = 0; i < kinds.length; i++) {
            int y = paletteY(i);
            boolean active = paletteDrag == kinds[i];
            g.fill(8, y, PALETTE_W - 8, y + 19, active ? 0xFF4D7385 : 0xFF293744);
            g.drawString(font, fit(kinds[i].label(), PALETTE_W - 26), 14, y + 5, 0xFFFFFFFF);
        }
        g.disableScissor();
        g.drawString(font, "滚轮浏览模块", 8, height - 31, 0xFFB8C7D4);
        g.enableScissor(PALETTE_W, 26, selected == null ? width : width - 224, height - 20);
        drawGrid(g);
        g.pose().pushPose();
        g.pose().translate(PALETTE_W + panX, 26 + panY, 0);
        g.pose().scale((float) zoom, (float) zoom, 1f);
        g.pose().translate(-PALETTE_W, -26, 0);
        routeBudget = 4;
        for (Edge edge : graph.edges()) drawEdge(g, edge, edge.equals(selectedEdge) ? 0xFFFFD36A : 0xFF9BC8E1);
        if (edgeFrom != null) drawPreview(g, mouseX, mouseY);
        for (Node node : graph.nodes()) drawNode(g, node);
        g.pose().popPose();
        g.disableScissor();
        if (selected != null) {
            g.fill(width - 224, 25, width, height - 20, 0xE514202A);
            g.drawString(font, panelMaximum > 0 ? "模块参数（滚轮浏览）" : "模块参数", width - 208, 31, 0xFFBCE1FF);
            if (coordinateLabelX >= 0 && coordinateLabelY >= 78 && coordinateLabelY + 20 <= height - 68) {
                g.drawString(font, "X", coordinateLabelX, coordinateLabelY - 10, 0xFFFFFFFF);
                g.drawString(font, "Y", coordinateLabelX + 66, coordinateLabelY - 10, 0xFFFFFFFF);
                g.drawString(font, "Z", coordinateLabelX + 132, coordinateLabelY - 10, 0xFFFFFFFF);
            }
        }
        if (paletteDrag != null) {
            int x = mouseX - 70, y = mouseY - 20;
            g.fill(x, y, x + NODE_W, y + NODE_H, 0xDD43596A);
            g.drawString(font, paletteDrag.label(), x + 8, y + 8, 0xFFFFFFFF);
        }
        g.fill(0, height - 19, width, height, 0xFF202C37);
        long unrouted = routes.values().stream().filter(java.util.List::isEmpty).count();
        String visibleMessage = avoidWires && unrouted > 0 ? "有 " + unrouted + " 条连线无法安全避让：请先整理卡片或关闭避让" : message;
        g.drawString(font, fit(visibleMessage + "   已选 " + selectedNodes.size() + " 个   缩放 " + (int) Math.round(zoom * 100) + "%", width - 16), 8, height - 14, 0xFFFFFFFF);
        if (!syncWarning.isEmpty()) {
            int right = Math.min(width - 4, PALETTE_W + 8 + font.width("同步暂停：" + syncWarning) + 8);
            g.fill(PALETTE_W + 4, 25, right, 40, 0xCC5A2018);
            g.drawString(font, fit("同步暂停：" + syncWarning, Math.max(80, width - PALETTE_W - 14)), PALETTE_W + 8, 28, 0xFFFFC3B4);
        }
        int canvasRight = selected == null ? width : width - 224;
        g.fill(PALETTE_W, 26, canvasRight, mapToolsBottom + 3, 0xEE202C37);
        super.render(g, mouseX, mouseY, partialTick);
        for (RegistrySuggestBox input : suggestionInputs) input.renderPopup(g, mouseX, mouseY);
    }

    private void drawGrid(GuiGraphics g) {
        double step = Math.max(5, GRID * zoom);
        double offsetX = positiveRemainder(panX, step), offsetY = positiveRemainder(panY, step);
        for (double x = PALETTE_W + offsetX; x < width; x += step)
            g.fill((int) x, 26, (int) x + 1, height - 20, 0x171A9AB5);
        for (double y = 26 + offsetY; y < height - 20; y += step)
            g.fill(PALETTE_W, (int) y, width, (int) y + 1, 0x171A9AB5);
    }
    private static double positiveRemainder(double value, double divisor) {
        double remainder = value % divisor;
        return remainder < 0 ? remainder + divisor : remainder;
    }

    private void drawNode(GuiGraphics g, Node node) {
        int x = node.x(), y = node.y();
        int color = node.kind().family() == FlowGraph.Family.ACTION ? 0xFF344B5D
                : node.kind().family() == FlowGraph.Family.CONDITION ? 0xFF28525A : 0xFF615133;
        if (selectedNodes.contains(node.id()) || node.id().equals(selected)) color = 0xFF426783;
        g.fill(x, y, x + NODE_W, y + NODE_H, 0xFF0B1117);
        g.fill(x + 1, y + 1, x + NODE_W - 1, y + NODE_H - 1, color);
        if (selectedNodes.contains(node.id())) g.renderOutline(x, y, NODE_W, NODE_H, 0xFFFFD36A);
        g.drawString(font, node.kind().label(), x + 8, y + 7, 0xFFFFFFFF);
        String detail = node.kind() == Kind.NATIVE_WORK ? node.setting("nativeTaskName", node.setting("nativeTask", "请选择女仆工作能力")) : node.setting("target", node.setting("attackTarget", node.setting("filter", "尚未设置")));
        if (node.kind() == Kind.START) detail = "任务入口";
        if (node.kind() == Kind.END) detail = "任务完成";
        if (node.kind().family() == FlowGraph.Family.CONDITION) detail = conditionLabel(node.setting("conditionType", node.kind() == Kind.LOOP_END ? "loop_count" : "always"));
        if (node.kind() == Kind.MOVE && !node.settings().containsKey("target"))
            detail = node.setting("targetMode", "主人面前");
        g.drawString(font, font.plainSubstrByWidth(detail, NODE_W - 14), x + 8, y + 25, 0xFFD0DCE5);
        g.fill(x + NODE_W / 2 - 4, y - 3, x + NODE_W / 2 + 4, y + 3, 0xFFE5EFF5);
        if (node.kind().family() == FlowGraph.Family.CONDITION) {
            int yesX = x + NODE_W / 3, noX = x + NODE_W * 2 / 3;
            g.fill(yesX - 4, y + NODE_H - 3, yesX + 4, y + NODE_H + 3, 0xFF70E1A1);
            g.fill(noX - 4, y + NODE_H - 3, noX + 4, y + NODE_H + 3, 0xFFFF9A83);
            String yes = node.kind() == Kind.BRANCH ? "是·成立" : node.kind() == Kind.LOOP_END ? "是·循环" : "是·进入";
            String no = node.kind() == Kind.BRANCH ? "否·不成立" : node.kind() == Kind.LOOP_START ? "否·跳过" : "否·退出";
            g.drawString(font, yes, x + 4, y + NODE_H - 10, 0xFF9CF0BC);
            g.drawString(font, no, x + NODE_W - font.width(no) - 4, y + NODE_H - 10, 0xFFFFB3A3);
        } else if (node.kind() != Kind.END) {
            g.fill(x + NODE_W / 2 - 4, y + NODE_H - 3, x + NODE_W / 2 + 4, y + NODE_H + 3, 0xFFE5EFF5);
        }
    }

    private java.util.List<FlowDiagram.Point> routed(Edge edge, boolean immediate) {
        if (routedGraph != graph || routedAvoid != avoidWires) { routes.clear(); routedGraph = graph; routedAvoid = avoidWires; }
        var cached = routes.get(edge);
        if (cached != null) return cached;
        // Hit-test only routes already rendered, rather than calculating every wire during a mouse click.
        if (immediate || routeBudget-- <= 0) return java.util.List.of();
        var route = FlowDiagram.route(graph, edge, avoidWires); routes.put(edge, route); return route;
    }
    private void drawEdge(GuiGraphics g, Edge edge, int color) {
        var points = routed(edge, false);
        if (points.isEmpty()) return;
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1); var b = points.get(i);
            if (edge.equals(selectedEdge)) g.renderOutline(Math.min(a.x(), b.x()) - 4, Math.min(a.y(), b.y()) - 4,
                    Math.abs(a.x() - b.x()) + 9, Math.abs(a.y() - b.y()) + 9, 0xFFFFD36A);
            line(g, a.x(), a.y(), b.x(), b.y(), color);
        }
        var end = points.get(points.size() - 1);
        g.fill(end.x() - 3, end.y() - 2, end.x() + 4, end.y() + 2, color);
        g.fill(end.x() - 3, end.y() - 5, end.x(), end.y() + 2, color);
    }

    private void drawPreview(GuiGraphics g, int mx, int my) {
        Node from = node(edgeFrom); if (from == null) return;
        int worldX = (int) Math.round(PALETTE_W + (mx - PALETTE_W - panX) / zoom);
        int worldY = (int) Math.round(26 + (my - 26 - panY) / zoom);
        orthogonal(g, outputX(from, edgePort), from.y() + NODE_H, worldX, worldY, 0xFFF6E38B);
    }

    private static void orthogonal(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int bend = y1 + (y2 - y1) / 2;
        line(g, x1, y1, x1, bend, color); line(g, x1, bend, x2, bend, color); line(g, x2, bend, x2, y2, color);
    }
    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(Math.min(x1, x2) - (y1 == y2 ? 0 : 1), Math.min(y1, y2) - (x1 == x2 ? 0 : 1),
                Math.max(x1, x2) + (y1 == y2 ? 1 : 2), Math.max(y1, y2) + (x1 == x2 ? 1 : 2), color);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        pointerX = x; pointerY = y;
        for (RegistrySuggestBox input : suggestionInputs) if (input.clickPopup(x, y, button)) return true;
        // Screen owns its buttons and text fields. Previously this canvas consumed every click
        // before widgets could receive it, making the inspector look enabled but inert.
        if (super.mouseClicked(x, y, button)) return true;
        // Palette/canvas clicks must release text focus, including fields discarded by init().
        setFocused(null);
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) { panning = true; dragX = x; dragY = y; return true; }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (y < 25 && x > width - 176 && x < width - 88) { saveDraft(); return true; }
        if (y < 25 && x >= width - 88) { checkGraph(); return true; }
        if (x < PALETTE_W && y > 26 && y < height - 44) {
            for (int i = 0; i < palette().length; i++) {
                int py = paletteY(i);
                if (y >= py && y <= py + 19) { paletteDrag = palette()[i]; return true; }
            }
            return false;
        }
        if (x >= (selected == null ? width : width - 224) || y <= mapToolsBottom + 3 || y >= height - 20) return false;
        for (Node node : graph.nodes()) {
            int nx = sx(node.x()), ny = sy(node.y());
            if (Math.abs(y - (ny + NODE_H * zoom)) < Math.max(6, 8 * zoom)) {
                Port port = outputPortAt(node, x, nx);
                if (port != null && !hasShiftDown()) { edgeFrom = node.id(); edgePort = port; rewiringEdge = false; selectedEdge = null; return true; }
            }
            if (x >= nx && x <= nx + NODE_W * zoom && y >= ny && y <= ny + NODE_H * zoom) {
                if (hasShiftDown()) {
                    if (!selectedNodes.add(node.id())) {
                        selectedNodes.remove(node.id()); selected = selectedNodes.stream().findFirst().orElse(null); init();
                        message = "已取消选择；Shift 点击可增减选择"; return true;
                    }
                } else if (!selectedNodes.contains(node.id())) { selectedNodes.clear(); selectedNodes.add(node.id()); }
                selected = node.id(); selectedEdge = null; init(); moving = node.id(); dragBase = graph;
                dragAnchorX = x; dragAnchorY = y;
                message = "拖动已选 " + selectedNodes.size() + " 个模块；Shift 点击多选，Ctrl+A 全选";
                return true;
            }
            if (Math.abs(y - ny) < Math.max(6, 8 * zoom) && x >= nx + NODE_W * zoom / 2 - 8 * zoom
                    && x <= nx + NODE_W * zoom / 2 + 8 * zoom) {
                selected = node.id(); return true;
            }
        }
        Edge hitEdge = graph.edges().stream().filter(edge -> nearEdge(edge, x, y)).findFirst().orElse(null);
        if (hitEdge != null) {
            selectedNodes.clear(); selectedEdge = hitEdge; selected = null; edgePressed = hitEdge; edgePressX = x; edgePressY = y;
            init(); message = "已选中箭头：Delete 删除；拖动可改接"; return true;
        }
        selectedEdge = null;
        if (!hasShiftDown()) selectedNodes.clear();
        if (selected != null && !hasShiftDown()) { selected = null; init(); }
        panning = true; dragX = x; dragY = y;
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        pointerX = x; pointerY = y;
        if (edgePressed != null && Math.hypot(x - edgePressX, y - edgePressY) > 4) {
            edgeFrom = edgePressed.from(); edgePort = edgePressed.port(); rewiringEdge = true; edgePressed = null;
        }
        boolean widgetHandled = super.mouseDragged(x, y, button, dx, dy);
        if (widgetHandled && moving == null && paletteDrag == null && edgeFrom == null && !panning) return true;
        if (panning) { panX += dx; panY += dy; return true; }
        if (moving != null) {
            int dxWorld = snap((int) Math.round((x - dragAnchorX) / zoom));
            int dyWorld = snap((int) Math.round((y - dragAnchorY) / zoom));
            graph = FlowDiagram.move(dragBase, selectedNodes, dxWorld, dyWorld);
            saveDraftQuietly(); return true;
        }
        return paletteDrag != null || edgeFrom != null || widgetHandled;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        super.mouseReleased(x, y, button);
        edgePressed = null;
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) { panning = false; return true; }
        if (paletteDrag != null) {
            if (x > PALETTE_W && x < (selected == null ? width : width - 224) && y > mapToolsBottom + 3 && y < height - 20) {
                double worldX = PALETTE_W + (x - PALETTE_W - panX) / zoom;
                double worldY = 26 + (y - 26 - panY) / zoom;
                place(paletteDrag, (int) Math.round(worldX - NODE_W / 2.0), (int) Math.round(worldY - NODE_H / 2.0));
            }
            paletteDrag = null; return true;
        }
        if (edgeFrom != null) {
            // Accept a drop anywhere on the target node, not only on its narrow top edge; dropping on
            // a node body used to silently do nothing, which looked like the wire tool was broken.
            Node target = graph.nodes().stream().filter(n -> x >= sx(n.x()) - 6 && x <= sx(n.x()) + NODE_W * zoom + 6
                    && y >= sy(n.y()) - 6 && y <= sy(n.y()) + NODE_H * zoom + 6).findFirst().orElse(null);
            if (target == null) {
                Edge snappedArrow = graph.edges().stream().filter(edge -> nearEdge(edge, x, y)).findFirst().orElse(null);
                if (snappedArrow != null) target = node(snappedArrow.to());
            }
            if (target != null) {
                try {
                    graph = graph.connect(new Edge(edgeFrom, edgePort, target.id()));
                    selectedEdge = graph.edges().stream().filter(edge -> edge.from().equals(edgeFrom) && edge.port() == edgePort).findFirst().orElse(null);
                    saveDraftQuietly(); message = "已吸附连线；点选连线后按 Delete 可删除";
                }
                catch (IllegalArgumentException e) { message = e.getMessage(); }
            } else message = "没有连到模块：请把线拖到目标模块上（原连线保持不变）";
            edgeFrom = null; edgePort = null; rewiringEdge = false; return true;
        }
        moving = null; dragBase = null; panning = false;
        return true;
    }

    private void place(Kind kind, int x, int y) {
        if (graph.nodes().size() >= FlowGraph.MAX_NODES) { message = "模块数量达到安全上限"; return; }
        String id = UUID.randomUUID().toString();
        Node added = new Node(id, kind, Math.max(PALETTE_W + 8, snap(x)), Math.max(34, snap(y)), java.util.Map.of());
        try {
            graph = graph.add(added);
            selectedNodes.clear(); selectedNodes.add(id); selected = id; selectedEdge = null; saveDraftQuietly(); init();
            message = "已添加" + kind.label() + "；请从出口手动拖线，不会自动接线";
        } catch (IllegalArgumentException e) { message = e.getMessage(); }
    }

    private double edgeDistance(Edge e, int x, int y) {
        Node a = node(e.from()), b = node(e.to());
        return Math.abs(x - (a.x() + b.x() + NODE_W) / 2.0) + Math.abs(y - (a.y() + b.y() + NODE_H) / 2.0);
    }
    private boolean nearEdge(Edge edge, double x, double y) {
        var points = routed(edge, true);
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1); var b = points.get(i);
            if (nearSegment(x, y, sx(a.x()), sy(a.y()), sx(b.x()), sy(b.y()))) return true;
        }
        return false;
    }
    private static boolean nearSegment(double x, double y, int x1, int y1, int x2, int y2) {
        if (x1 == x2) return Math.abs(x - x1) <= 5 && y >= Math.min(y1, y2) - 5 && y <= Math.max(y1, y2) + 5;
        return Math.abs(y - y1) <= 5 && x >= Math.min(x1, x2) - 5 && x <= Math.max(x1, x2) + 5;
    }
    private static int snap(int v) { return Math.round(v / (float)GRID) * GRID; }
    private int sx(int x) { return (int) Math.round(PALETTE_W + panX + (x - PALETTE_W) * zoom); }
    private int sy(int y) { return (int) Math.round(26 + panY + (y - 26) * zoom); }
    private int outputX(Node node, Port port) {
        return node.x() + (node.kind().family() == FlowGraph.Family.CONDITION
                ? (port == Port.YES ? NODE_W / 3 : NODE_W * 2 / 3) : NODE_W / 2);
    }
    private Port outputPortAt(Node node, double x, int nx) {
        if (node.kind() == Kind.END) return null;
        if (node.kind().family() != FlowGraph.Family.CONDITION)
            return Math.abs(x - (nx + NODE_W * zoom / 2)) <= 10 * zoom ? Port.NEXT : null;
        if (Math.abs(x - (nx + NODE_W * zoom / 3)) <= 10 * zoom) return Port.YES;
        if (Math.abs(x - (nx + NODE_W * zoom * 2 / 3)) <= 10 * zoom) return Port.NO;
        return null;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (selected != null && mouseX >= width - 224 && mouseY > 62 && mouseY < height - 60) {
            if (panelMaximum > 0) { panelScroll = Math.max(0, Math.min(panelMaximum, panelScroll + (int) (-delta * 24))); init(); }
            return true;
        }
        if (mouseX < PALETTE_W && mouseY > 25 && mouseY < height - 20) {
            int maximum = Math.max(0, 49 + ACTION_COUNT * 25 + 31 + (palette().length - ACTION_COUNT - 1) * 25 + 24 - (height - 44));
            paletteScroll = Math.max(0, Math.min(maximum, paletteScroll + (int) (-delta * 25))); return true;
        }
        if (mouseX <= PALETTE_W || mouseX >= width - 224 || mouseY <= 25 || mouseY >= height - 20)
            return super.mouseScrolled(mouseX, mouseY, delta);
        if (delta == 0) return super.mouseScrolled(mouseX, mouseY, delta);
        double next = Math.max(0.45, Math.min(2.25, zoom * (delta > 0 ? 1.12 : 1 / 1.12)));
        if (Math.abs(next - zoom) < 0.001) return true;
        double anchorX = PALETTE_W + (mouseX - PALETTE_W - panX) / zoom;
        double anchorY = 26 + (mouseY - 26 - panY) / zoom;
        panX = mouseX - PALETTE_W - (anchorX - PALETTE_W) * next;
        panY = mouseY - 26 - (anchorY - 26) * next;
        zoom = next;
        return true;
    }
    private Node node(String id) { return graph.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElse(null); }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox input && input.isFocused() && input.visible && children().contains(input))
            return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_A && hasControlDown()) {
            selectedNodes.clear(); graph.nodes().forEach(node -> selectedNodes.add(node.id()));
            selected = null; selectedEdge = null; init(); message = "已全选 " + selectedNodes.size() + " 个模块，拖动任意已选模块可整体移动"; return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && selectedEdge != null) {
            graph = graph.disconnect(selectedEdge.from(), selectedEdge.port());
            selectedEdge = null; saveDraftQuietly(); message = "连线已删除"; return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && (!selectedNodes.isEmpty() || selected != null)) {
            deleteSelected();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (paletteDrag != null || edgeFrom != null || moving != null)) {
            paletteDrag = null; edgeFrom = null; edgePort = null; moving = null; rewiringEdge = false; return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void deleteSelected() {
        if (selectedNodes.isEmpty() && selected == null) return;
        java.util.Set<String> ids = new java.util.HashSet<>(selectedNodes); if (ids.isEmpty()) ids.add(selected);
        int removed = 0;
        for (String id : ids) {
            Node node = node(id);
            if (node != null && node.kind() != Kind.START && node.kind() != Kind.END) { graph = graph.remove(id); removed++; }
        }
        selectedNodes.clear(); selected = null; selectedEdge = null;
        saveDraftQuietly(); message = "已删除 " + removed + " 个模块（开始与结束保留）"; init();
    }

    private void checkGraph() {
        try {
            dev.tlmprogrammable.flow.server.FlowRuntime.validate(graph);
            message = "流程可以运行：" + graph.nodes().size() + " 个模块，" + graph.edges().size() + " 条连线";
        } catch (IllegalArgumentException e) { message = "无法运行：" + e.getMessage(); }
    }
    private void saveDraft() {
        if (saving) return;
        if (taskName == null || taskName.isBlank()) { message = "请填写任务名称"; return; }
        flushDraft();
        CompoundTag payload = new CompoundTag(); payload.put("graph", FlowGraphNbt.write(graph));
        payload.putInt("revision", FlowClient.revision(maidUuid));
        saving = true; message = "正在保存到女仆任务栏…";
        FlowNetwork.send(maidEntityId, maidUuid, "save", payload);
    }
    public void serverReply(FlowNetwork.Reply reply) {
        if (!reply.command().equals("save") || !saving) return;
        saving = false; message = reply.message();
        if (reply.ok()) FlowClient.closeEditor(this, true);
        else FlowNetwork.send(maidEntityId, maidUuid, "read");
    }
    public void setSyncWarning(String warning) { syncWarning = warning == null ? "" : warning; }
    private boolean saveDraftQuietly() {
        draftDirty = true;
        if (System.currentTimeMillis() - lastDraftWrite < DRAFT_WRITE_INTERVAL_MS) return true;
        return writeDraft();
    }
    private boolean writeDraft() {
        draftDirty = false; lastDraftWrite = System.currentTimeMillis();
        try {
            var folder = FMLPaths.GAMEDIR.get().resolve("tlm_flow").resolve("drafts").resolve(maidUuid.toString());
            Files.createDirectories(folder);
            NbtIo.writeCompressed(FlowGraphNbt.write(graph), folder.resolve(graph.id() + ".dat").toFile());
            return true;
        } catch (IOException e) { message = "保存本机草稿失败：" + e.getMessage(); return false; }
    }
    private void flushDraft() { if (draftDirty) writeDraft(); }
    @Override public void tick() {
        super.tick();
        if (draftDirty && System.currentTimeMillis() - lastDraftWrite >= DRAFT_WRITE_INTERVAL_MS) writeDraft();
    }
    private int nameWidth() {
        int available = width - 176 - 6 - (PALETTE_W + 8);
        return Math.max(70, Math.min(150, available));
    }
    private String fit(String text, int maxWidth) { return font.plainSubstrByWidth(text == null ? "" : text, maxWidth); }

    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        return super.keyReleased(keyCode, scanCode, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { flushDraft(); FlowClient.closeEditor(this, true); }
}
