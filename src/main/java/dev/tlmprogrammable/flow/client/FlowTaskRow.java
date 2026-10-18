package dev.tlmprogrammable.flow.client;

import com.github.tartaricacid.touhoulittlemaid.api.client.gui.ITooltipButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.FlowGraph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;
import java.util.function.Supplier;

/** Occupies the original TLM task-list slot, preserving native paging. */
final class FlowTaskRow extends Button implements ITooltipButton {
    private final EntityMaid maid;
    private final FlowGraph graph;
    FlowTaskRow(EntityMaid maid, FlowGraph graph, int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal(graph.name()), button -> FlowClient.command(maid, graph, "select"), Supplier::get);
        this.maid = maid; this.graph = graph;
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || !isMouseOver(mouseX, mouseY)) return false;
        if (button == 1) { FlowClient.openEditor(maid, graph); return true; }
        if (button == 0 && mouseX >= getX() + width - 13) { FlowClient.command(maid, graph, "delete"); return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        boolean selected = graph.id().equals(FlowClient.selected(maid.getUUID()));
        graphics.fill(getX(), getY(), getX() + width, getY() + height, selected ? 0xFFDBBE78 : isHovered() ? 0xFFB9AC8A : 0xFFA49A7B);
        graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, 0xFFEEE4C0);
        graphics.renderItem(new ItemStack(Items.WRITABLE_BOOK), getX() + 1, getY() + 1);
        graphics.drawString(mc.font, mc.font.plainSubstrByWidth(graph.name(), width - 34), getX() + 20, getY() + 6, 0xFF28251E, false);
        graphics.fill(getX() + width - 13, getY() + 1, getX() + width - 1, getY() + height - 1, 0xFF765853);
        graphics.drawString(mc.font, "×", getX() + width - 10, getY() + 5, 0xFFFFD8D0, false);
    }
    @Override public boolean isTooltipHovered() { return isHovered(); }
    @Override public void renderTooltip(GuiGraphics graphics, Minecraft mc, int mouseX, int mouseY) {
        graphics.renderComponentTooltip(mc.font, List.of(Component.literal(graph.name()),
                Component.literal("状态：" + FlowClient.status(maid.getUUID())),
                Component.literal("左键选择 · 关闭界面后执行"), Component.literal("右键编辑 · × 删除任务")), mouseX, mouseY);
    }
}
