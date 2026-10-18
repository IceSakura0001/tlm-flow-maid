package dev.tlmprogrammable.flow.client;

import dev.tlmprogrammable.flow.server.FlowNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** Settings never mutate task nodes or resume the editor's paused execution. */
public final class FlowSettingsScreen extends Screen {
    private final FlowEditorScreen editor;
    private CompoundTag rules = new CompoundTag();
    private boolean loaded;
    private int page;
    private String cornerA = "", cornerB = "", block = "", blockText = "", status = "正在读取设置…", clearArmed = "";
    private long clearUntil;
    private RegistrySuggestBox input;
    public FlowSettingsScreen(FlowEditorScreen editor) {
        super(Component.literal("流程任务设置")); this.editor = editor;
        FlowNetwork.send(editor.maidEntityId(), editor.maidUuid(), "settings_read");
    }
    public FlowEditorScreen editor() { return editor; }
    public void receive(FlowNetwork.Reply reply) { if (reply.ok()) { rules = reply.data().copy(); loaded = true; status = "设置已同步"; rebuild(); } else status = reply.message(); }
    private void rebuild() { if (minecraft != null) init(); }
    private Button button(String title, int y, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(title), b -> action.run()).bounds(width / 2 - 155, y, 310, 20).build());
    }
    private void send(CompoundTag data) { FlowNetwork.send(editor.maidEntityId(), editor.maidUuid(), "road_rules", data); }
    private void clear(String action) {
        long now = System.currentTimeMillis();
        if (!action.equals(clearArmed) || now > clearUntil) { clearArmed = action; clearUntil = now + 5000; status = "5秒内再次点击同一按钮确认清空"; return; }
        clearArmed = ""; CompoundTag tag = new CompoundTag(); tag.putString("action", action); send(tag);
    }
    private void pick(boolean first) {
        WorldPickSession.begin(this, editor, editor.maidEntityId(), editor.maidUuid(), WorldPickSession.Purpose.ROAD_CORNER,
                value -> { if (first) cornerA = value; else cornerB = value; status = "已选：" + value; });
    }
    @Override protected void init() {
        clearWidgets(); input = null;
        button("设置分类：" + (page == 0 ? "通用移动" : page == 1 ? "禁拆区域" : page == 2 ? "禁拆方块" : "流程图") + "  ▶", 30, () -> { page = (page + 1) % 4; init(); });
        if (page == 0) {
            button("所有流程动作的筑路 / 拆障：" + (rules.getBoolean("roadEnabled") ? "开" : "关"), 62, () -> {
                CompoundTag tag = new CompoundTag(); tag.putString("action", "settings"); tag.putBoolean("roadEnabled", !rules.getBoolean("roadEnabled")); send(tag);
            }).active = loaded;
        } else if (page == 1) {
            button("对角点一：" + (cornerA.isEmpty() ? "准星点选" : cornerA), 62, () -> pick(true));
            button("对角点二：" + (cornerB.isEmpty() ? "准星点选" : cornerB), 88, () -> pick(false));
            button("保存此全高度禁拆区域", 114, () -> { CompoundTag tag = new CompoundTag(); tag.putString("action", "area"); tag.putString("a", cornerA); tag.putString("b", cornerB); send(tag); }).active = loaded && !cornerA.isEmpty() && !cornerB.isEmpty();
            button("清空全部禁拆区域（再次点击确认）", 140, () -> clear("clear_areas")).active = loaded;
        } else if (page == 2) {
            input = new RegistrySuggestBox(font, width / 2 - 155, 62, 310, "禁拆方块（中文联想）", blockText, RegistrySuggestBox.Domain.BLOCK,
                    value -> { blockText = value; block = value; }, candidate -> { block = candidate.id(); });
            addRenderableWidget(input);
            button("添加禁拆方块类型", 88, () -> { CompoundTag tag = new CompoundTag(); tag.putString("action", "block"); tag.putString("block", block); send(tag); }).active = loaded;
            button("清空全部禁拆类型（再次点击确认）", 114, () -> clear("clear_blocks")).active = loaded;
        } else {
            button("本任务箭头避让：" + (editor.avoidsWires() ? "开" : "关"), 62, () -> { editor.toggleAvoidance(); init(); });
            button("本任务排列规则：" + editor.layoutLabel(), 88, () -> { editor.cycleLayout(); init(); });
        }
        button("返回流程图", height - 27, this::onClose);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g); g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        super.render(g, mx, my, partial);
        int x = width / 2 - 155;
        if (page == 0) {
            g.drawString(font, "主人设置：本存档内所有女仆的流程任务共用", x, 94, 0xFFD37A);
            g.drawString(font, "普通寻路失败三次才规划；只消耗真实圆石", x, 110, 0xFFFFFF);
            g.drawString(font, "拆障必须批准本次规划；禁拆保护始终有效", x, 126, 0xFFFFFF);
            g.drawString(font, "局部通路最多24步；不强制加载远处区块", x, 142, 0xFFFFFF);
        }
        if (page == 1) g.drawString(font, "已保存 " + rules.getList("areas", 10).size() + " 个区域；覆盖所有高度", x, 168, 0xFFFFFF);
        if (page == 2) g.drawString(font, "已保存 " + rules.getList("blocks", 8).size() + " 种方块（所有维度）", x, 146, 0xFFFFFF);
        g.drawString(font, font.plainSubstrByWidth(status, 310), x, height - 42, 0x9BE89B);
        if (input != null) input.renderPopup(g, mx, my);
    }
    @Override public boolean mouseClicked(double x, double y, int b) { return input != null && input.clickPopup(x, y, b) || super.mouseClicked(x, y, b); }
    @Override public void onClose() { minecraft.setScreen(editor); }
    @Override public boolean isPauseScreen() { return false; }
}
