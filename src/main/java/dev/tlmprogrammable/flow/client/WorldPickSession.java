package dev.tlmprogrammable.flow.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;
import java.util.function.Consumer;

/** Temporary in-world picker; it captures a block via the crosshair without opening another Screen. */
public final class WorldPickSession {
    public enum Purpose { LOCATION, CONTAINER, ENTITY, ROAD_CORNER }
    private static WorldPickSession active;
    private final net.minecraft.client.gui.screens.Screen parent;
    private final FlowEditorScreen editor;
    private final UUID maidUuid;
    private final int maidEntityId;
    private final String dimension;
    private final Purpose purpose;
    private final Consumer<String> result;
    private BlockPos hovered;
    private String pickedValue;
    private String status = "准星对准目标方块，左键确认；Esc 取消";

    private WorldPickSession(net.minecraft.client.gui.screens.Screen parent, FlowEditorScreen editor, int maidEntityId, UUID maidUuid,
                             Purpose purpose, Consumer<String> result) {
        this.parent = parent; this.editor = editor;
        this.maidEntityId = maidEntityId;
        this.maidUuid = maidUuid;
        this.purpose = purpose;
        this.result = result;
        this.dimension = Minecraft.getInstance().level.dimension().location().toString();
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(WorldPickSession::mouse);
        MinecraftForge.EVENT_BUS.addListener(WorldPickSession::interaction);
        MinecraftForge.EVENT_BUS.addListener(WorldPickSession::key);
        MinecraftForge.EVENT_BUS.addListener(WorldPickSession::opening);
        MinecraftForge.EVENT_BUS.addListener(WorldPickSession::tick);
        MinecraftForge.EVENT_BUS.addListener(WorldPickSession::render);
    }
    public static boolean owns(FlowEditorScreen screen) { return active != null && active.editor == screen; }

    public static void begin(FlowEditorScreen parent, int maidEntityId, UUID maidUuid, Purpose purpose,
                             Consumer<String> result) {
        begin(parent, parent, maidEntityId, maidUuid, purpose, result);
    }
    public static void begin(net.minecraft.client.gui.screens.Screen parent, FlowEditorScreen editor, int maidEntityId, UUID maidUuid, Purpose purpose, Consumer<String> result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        active = new WorldPickSession(parent, editor, maidEntityId, maidUuid, purpose, result);
        clear(mc.options.keyAttack); clear(mc.options.keyUse);
        mc.setScreen(null); mc.mouseHandler.grabMouse();
    }

    private boolean valid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.isAlive() && mc.level != null
                && dimension.equals(mc.level.dimension().location().toString())
                && mc.level.getEntity(maidEntityId) instanceof EntityMaid maid && maid.isAlive()
                && maidUuid.equals(maid.getUUID());
    }

    private void refresh() {
        hovered = null; pickedValue = null;
        if (!valid()) { status = "女仆或世界状态已变化，点选已取消"; return; }
        Minecraft mc = Minecraft.getInstance();
        if (purpose == Purpose.ENTITY) {
            if (mc.hitResult instanceof EntityHitResult entityHit && entityHit.getEntity().isAlive()
                    && entityHit.getEntity() != mc.player
                    && !(entityHit.getEntity() instanceof EntityMaid)) {
                pickedValue = entityHit.getEntity().getUUID().toString();
                status = "已指向实体：" + entityHit.getEntity().getName().getString() + "；左键确认";
            } else status = "准星对准一个实体后左键确认";
            return;
        }
        var eye = mc.player.getEyePosition(1f);
        var hit = mc.level.clip(new ClipContext(eye, eye.add(mc.player.getViewVector(1f).scale(6)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !mc.level.hasChunkAt(hit.getBlockPos())) {
            status = "请将准星对准 6 格内的方块"; return;
        }
        BlockPos blockPos = hit.getBlockPos();
        EntityMaid maid = (EntityMaid) mc.level.getEntity(maidEntityId);
        if (purpose == Purpose.ROAD_CORNER) { hovered = blockPos.immutable(); }
        else if (purpose == Purpose.CONTAINER) {
            var blockEntity = mc.level.getBlockEntity(blockPos);
            boolean usable = blockEntity != null;
            if (usable) {
                usable = false;
                for (var direction : net.minecraft.core.Direction.values()) {
                    if (blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, direction).isPresent()) {
                        usable = true; break;
                    }
                }
            }
            if (!usable) { status = "这里不是可访问的物品容器"; return; }
            hovered = blockPos.immutable();
        } else {
            // Select the standing position on the top face of the highlighted block.
            BlockPos feet = blockPos.above();
            if (!maid.isWithinRestriction(feet)) { status = "位置超出女仆当前允许活动范围"; return; }
            var shifted = maid.getBoundingBox().move(feet.getX() + .5 - maid.getX(), feet.getY() - maid.getY(),
                    feet.getZ() + .5 - maid.getZ());
            if (!mc.level.noCollision(maid, shifted) || !mc.level.getBlockState(blockPos).isFaceSturdy(
                    mc.level, blockPos, net.minecraft.core.Direction.UP)) {
                status = "方块上方没有安全站立位置"; return;
            }
            hovered = feet.immutable();
        }
        pickedValue = hovered.getX() + "," + hovered.getY() + "," + hovered.getZ();
        status = "可选位置：" + hovered.getX() + ", " + hovered.getY() + ", " + hovered.getZ() + "；左键确认";
    }

    private void finish(boolean accept, boolean returnToEditor) {
        if (active != this) return;
        active = null;
        Minecraft mc = Minecraft.getInstance();
        clear(mc.options.keyAttack); clear(mc.options.keyUse);
        if (accept && pickedValue != null) result.accept(pickedValue);
        if (returnToEditor && mc.player != null && mc.level != null) mc.setScreen(parent);
    }
    private static void clear(KeyMapping key) { key.setDown(false); while (key.consumeClick()) { } }
    private static void mouse(InputEvent.MouseButton.Pre event) {
        WorldPickSession session = active;
        if (session == null) return;
        if (event.getButton() <= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            event.setCanceled(true);
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.getAction() == GLFW.GLFW_PRESS) {
                session.refresh(); if (session.pickedValue != null) session.finish(true, true);
            }
        }
    }
    private static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (active != null) { event.setCanceled(true); event.setSwingHand(false); }
    }
    private static void key(InputEvent.Key event) {
        WorldPickSession session = active;
        if (session == null) return;
        Minecraft mc = Minecraft.getInstance();
        clear(mc.options.keyDrop); clear(mc.options.keySwapOffhand);
        if (event.getKey() == GLFW.GLFW_KEY_ESCAPE && event.getAction() == GLFW.GLFW_PRESS) {
            event.setCanceled(true); session.finish(false, true);
        }
    }
    private static void opening(ScreenEvent.Opening event) {
        WorldPickSession session = active;
        if (session == null || event.getNewScreen() == null) return;
        if (event.getNewScreen() instanceof PauseScreen) { event.setCanceled(true); session.finish(false, true); }
        else session.finish(false, false);
    }
    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && active != null && !active.valid()) active.finish(false, false);
    }
    private static void render(RenderGuiEvent.Post event) {
        WorldPickSession session = active;
        if (session == null) return;
        session.refresh();
        Minecraft mc = Minecraft.getInstance();
        var g = event.getGuiGraphics();
        int right = Math.min(mc.getWindow().getGuiScaledWidth() - 4, 440);
        g.fill(4, 4, right, 44, 0xCC14212B);
        String title = switch (session.purpose) {
            case CONTAINER -> "选择容器";
            case ENTITY -> "选择攻击目标";
            default -> "选择移动位置";
        };
        g.drawString(mc.font, title, 9, 8, 0xFFFFD37A);
        g.drawString(mc.font, mc.font.plainSubstrByWidth(session.status, right - 14), 9, 23,
                session.pickedValue == null ? 0xFFFFA0A0 : 0xFF9BE89B);
        g.drawString(mc.font, "准星指向方块后左键确认 · Esc 取消", 9, 33, 0xFFFFFFFF);
    }
}
