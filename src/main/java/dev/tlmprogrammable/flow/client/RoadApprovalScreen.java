package dev.tlmprogrammable.flow.client;

import dev.tlmprogrammable.flow.server.FlowNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

/** Approval is exact-plan scoped. Closing/Escape means refusal, never implicit consent. */
public final class RoadApprovalScreen extends Screen {
    private final Screen previous; private final FlowNetwork.Reply offer; private int page; private boolean answered;
    public RoadApprovalScreen(Screen previous, FlowNetwork.Reply offer) { super(Component.literal("女仆申请拆障（道路实验）")); this.previous = previous; this.offer = offer; }
    @Override protected void init() {
        int center = width / 2;
        addRenderableWidget(Button.builder(Component.literal("允许本次规划"), button -> answer(true)).bounds(center - 148, height - 54, 144, 20).build());
        addRenderableWidget(Button.builder(Component.literal("拒绝，停止本次筑路"), button -> answer(false)).bounds(center + 4, height - 54, 144, 20).build());
        addRenderableWidget(Button.builder(Component.literal("上一页"), button -> { page = Math.max(0, page - 1); }).bounds(center - 104, height - 82, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"), button -> { int size = offer.data().getList("blocks", 10).size(); page = Math.min(Math.max(0, (size - 1) / rows()), page + 1); }).bounds(center + 4, height - 82, 100, 20).build());
    }
    private int rows() { return Math.max(1, (height - 168) / 16); }
    private void answer(boolean allow) {
        if (answered) return; answered = true;
        CompoundTag tag = new CompoundTag(); tag.putUUID("ticket", offer.data().getUUID("ticket")); tag.putBoolean("allow", allow);
        FlowNetwork.send(offer.entityId(), offer.maidId(), "road_answer", tag); minecraft.setScreen(previous);
    }
    @Override public void onClose() { answer(false); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xF018202B);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFD080);
        graphics.drawCenteredString(font, "只授权下列方块；重规划/超时需重新申请，禁拆规则仍优先", width / 2, 34, 0xFFFFFFFF);
        graphics.drawCenteredString(font, "计划破坏 " + offer.data().getInt("count") + " 块，搭建圆石 " + offer.data().getInt("placeCount") + " 块", width / 2, 52, 0xFFFFFFFF);
        graphics.drawCenteredString(font, "维度：" + offer.data().getString("dimension"), width / 2, 68, 0xFFAEC8DF);
        ListTag blocks = offer.data().getList("blocks", 10); int first = page * rows();
        for (int i = first; i < Math.min(blocks.size(), first + rows()); i++) {
            CompoundTag entry = blocks.getCompound(i); BlockPos pos = BlockPos.of(entry.getLong("pos"));
            graphics.drawString(font, font.plainSubstrByWidth(pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "  " + entry.getString("block"), width - 32), 16, 90 + (i - first) * 16, 0xFFFFFFFF);
        }
        graphics.drawCenteredString(font, "第 " + (page + 1) + " 页 · 许可最长2分钟 · Esc为拒绝", width / 2, height - 24, 0xFFFFC090);
        super.render(graphics, mouseX, mouseY, partial);
    }
}
