package dev.tlmprogrammable.flow.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.Consumer;

/** Input-method style completion using the active resource-pack language, including modded registries. */
public final class RegistrySuggestBox extends EditBox {
    public enum Domain { ITEM, BLOCK, ENTITY, TARGET, WORK }
    public record Candidate(String id, String name, String kind, ItemStack icon) { }
    private static final Map<Domain, List<Candidate>> CATALOGS = new EnumMap<>(Domain.class);
    private static String language = "";
    private static final int ROW = 24, MAX_ROWS = 6;
    private final Domain domain;
    private final Consumer<Candidate> choose;
    private List<Candidate> suggestions = List.of();
    private int highlighted;
    private boolean dismissed;

    public RegistrySuggestBox(Font font, int x, int y, int width, String hint, String value,
                              Domain domain, Consumer<String> onText, Consumer<Candidate> choose) {
        super(font, x, y, width, 20, Component.literal(hint));
        this.domain = domain; this.choose = choose;
        setHint(Component.literal(hint)); setMaxLength(256); setValue(value);
        setResponder(text -> { dismissed = false; highlighted = 0; onText.accept(text); refresh(text); });
        refresh(value);
    }
    private void refresh(String text) {
        String query = text.strip().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) { suggestions = List.of(); return; }
        suggestions = catalog(domain).stream().filter(candidate -> candidate.name().toLowerCase(Locale.ROOT).contains(query)
                        || candidate.id().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.<Candidate>comparingInt(candidate -> candidate.name().startsWith(query) ? 0 : 1)
                        .thenComparingInt(candidate -> candidate.name().length()).thenComparing(Candidate::id))
                .limit(MAX_ROWS).toList();
        highlighted = Math.min(highlighted, Math.max(0, suggestions.size() - 1));
    }
    private static List<Candidate> catalog(Domain domain) {
        if (domain == Domain.WORK) {
            Minecraft mc = Minecraft.getInstance();
            var maid = mc.screen instanceof FlowEditorScreen editor && mc.level != null
                    && mc.level.getEntity(editor.maidEntityId()) instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid entity ? entity : null;
            List<Candidate> tasks = new ArrayList<>();
            for (var task : com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getTaskIndex()) {
                if (task.getUid().equals(dev.tlmprogrammable.flow.server.FlowWorkTask.UID) || maid != null && task.isHidden(maid)) continue;
                tasks.add(new Candidate(task.getUid().toString(), task.getName().getString(), "task", task.getIcon().copy()));
            }
            return tasks;
        }
        String currentLanguage = Minecraft.getInstance().options.languageCode;
        if (!currentLanguage.equals(language)) { language = currentLanguage; CATALOGS.clear(); }
        return CATALOGS.computeIfAbsent(domain, key -> {
            List<Candidate> list = new ArrayList<>();
            if (key == Domain.ITEM) for (var item : ForgeRegistries.ITEMS.getValues()) {
                if (item == Items.AIR) continue;
                ItemStack stack = new ItemStack(item);
                list.add(new Candidate(Objects.requireNonNull(ForgeRegistries.ITEMS.getKey(item)).toString(), stack.getHoverName().getString(), "item", stack));
            }
            if (key == Domain.BLOCK || key == Domain.TARGET) {
                list.add(new Candidate("#minecraft:logs", "树木（所有原木）", "block", new ItemStack(Items.OAK_LOG)));
                for (var block : ForgeRegistries.BLOCKS.getValues()) {
                    String id = Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(block)).toString();
                    if (id.equals("minecraft:air")) continue;
                    list.add(new Candidate(id, block.getName().getString(), "block", new ItemStack(block.asItem())));
                }
            }
            if (key == Domain.ENTITY || key == Domain.TARGET) for (var type : ForgeRegistries.ENTITY_TYPES.getValues())
                list.add(new Candidate(Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getKey(type)).toString(),
                        type.getDescription().getString(), "entity", new ItemStack(Items.NAME_TAG)));
            return List.copyOf(list);
        });
    }
    private boolean popup() { return isFocused() && visible && !dismissed && !suggestions.isEmpty(); }
    private int popupRows() {
        var screen = Minecraft.getInstance().screen;
        int height = screen == null ? 240 : screen.height;
        int above = Math.max(0, getY() - 28), below = Math.max(0, height - 22 - getY() - getHeight() - 2);
        return Math.min(suggestions.size(), Math.max(1, Math.max(above, below) / ROW));
    }
    private int popupTop() {
        var screen = Minecraft.getInstance().screen;
        int height = screen == null ? 240 : screen.height;
        int below = getY() + getHeight() + 2, rows = popupRows();
        return below + rows * ROW <= height - 22 ? below : Math.max(26, getY() - rows * ROW - 2);
    }
    private int popupFirst() { return Math.max(0, highlighted - popupRows() + 1); }
    public boolean clickPopup(double mouseX, double mouseY, int button) {
        int top = popupTop();
        if (!popup() || button != 0 || mouseX < getX() || mouseX >= getX() + getWidth()
                || mouseY < top || mouseY >= top + popupRows() * ROW) return false;
        pick(popupFirst() + (int) ((mouseY - top) / ROW)); return true;
    }
    private void pick(int index) {
        if (index < 0 || index >= suggestions.size()) return;
        Candidate selected = suggestions.get(index);
        setValue(selected.name()); choose.accept(selected); dismissed = true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (popup()) {
            if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
                highlighted = Math.floorMod(highlighted + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), suggestions.size()); return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_TAB) { pick(highlighted); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { dismissed = true; return true; }
        }
        return super.keyPressed(key, scan, modifiers);
    }
    public void renderPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!popup()) return;
        Minecraft mc = Minecraft.getInstance(); int top = popupTop(), rows = popupRows(), first = popupFirst();
        graphics.pose().pushPose(); graphics.pose().translate(0, 0, 500);
        graphics.fill(getX() - 1, top - 1, getX() + getWidth() + 1, top + rows * ROW + 1, 0xFFF0D080);
        for (int i = 0; i < rows; i++) {
            int y = top + i * ROW; Candidate candidate = suggestions.get(first + i);
            boolean hover = mouseX >= getX() && mouseX < getX() + getWidth() && mouseY >= y && mouseY < y + ROW;
            graphics.fill(getX(), y, getX() + getWidth(), y + ROW, hover || first + i == highlighted ? 0xFF385A70 : 0xFF172733);
            graphics.renderItem(candidate.icon(), getX() + 3, y + 3);
            graphics.drawString(mc.font, mc.font.plainSubstrByWidth(candidate.name(), getWidth() - 26), getX() + 24, y + 3, 0xFFFFFFFF);
            graphics.drawString(mc.font, mc.font.plainSubstrByWidth(candidate.id(), getWidth() - 26), getX() + 24, y + 13, 0xFF9BB5C8);
        }
        graphics.pose().popPose();
    }
}
