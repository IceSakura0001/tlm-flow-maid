package dev.tlmprogrammable.flow.client;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.MaidContainerGuiEvent;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.TaskButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import dev.tlmprogrammable.flow.*;
import dev.tlmprogrammable.flow.server.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import java.lang.reflect.Field;
import java.util.*;

public final class FlowClient {
    private static final Map<UUID, CompoundTag> LIBRARIES = new HashMap<>();
    private static Field nativeTaskList;
    private static Object lastRequested;
    private static FlowEditorScreen editing;
    private static int ticks;
    private FlowClient() { }
    public static void init() {
        FlowNetwork.clientSink(FlowClient::reply);
        MinecraftForge.EVENT_BUS.addListener(FlowClient::beforeInit);
        MinecraftForge.EVENT_BUS.addListener(FlowClient::afterInit);
        MinecraftForge.EVENT_BUS.addListener(FlowClient::addButton);
        MinecraftForge.EVENT_BUS.addListener(FlowClient::tick);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            LIBRARIES.clear(); lastRequested = null; editing = null;
        });
        WorldPickSession.register();
    }
    public static int revision(UUID maidId) { return LIBRARIES.getOrDefault(maidId, new CompoundTag()).getInt("revision"); }
    public static UUID selected(UUID maidId) {
        CompoundTag data = LIBRARIES.get(maidId);
        return data != null && data.hasUUID("selected") ? data.getUUID("selected") : null;
    }
    public static String status(UUID maidId) {
        CompoundTag data = LIBRARIES.get(maidId);
        String status = data == null ? "" : data.getString("status");
        return switch (status) {
            case "RUNNING" -> "执行中";
            case "PAUSED" -> "已暂停";
            case "SUCCEEDED" -> "已完成";
            case "FAILED" -> "执行失败";
            case "READY" -> "准备执行";
            case "" -> "未开始";
            default -> status;
        };
    }
    /** TLM 1.5.3 has no per-maid list mutation hook. This adapter only changes its presentation list. */
    @SuppressWarnings("unchecked")
    private static void beforeInit(ScreenEvent.Init.Pre event) {
        if (!(event.getScreen() instanceof AbstractMaidContainerGui<?> gui) || gui.getMenu().getMaid() == null) return;
        try {
            if (nativeTaskList == null) {
                nativeTaskList = AbstractMaidContainerGui.class.getDeclaredField("notHiddenTasks"); nativeTaskList.setAccessible(true);
            }
            List<IMaidTask> tasks = (List<IMaidTask>) nativeTaskList.get(gui);
            tasks.removeIf(task -> task instanceof LibraryEntry);
            CompoundTag cached = LIBRARIES.get(gui.getMenu().getMaid().getUUID());
            if (cached != null) {
                List<IMaidTask> entries = new ArrayList<>();
                for (FlowGraph graph : FlowLibrary.graphs(cached)) entries.add(new LibraryEntry(graph));
                tasks.addAll(0, entries);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("TLM 1.5.3 任务栏接口不匹配，无法显示自定义任务", exception);
        }
    }
    private static void afterInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractMaidContainerGui<?> gui)) return;
        EntityMaid maid = gui.getMenu().getMaid(); if (maid == null) return;
        if (lastRequested != gui) { lastRequested = gui; FlowNetwork.send(maid.getId(), maid.getUUID(), "read"); }
    }
    private static void addButton(MaidContainerGuiEvent.Init event) {
        EntityMaid maid = event.getGui().getMenu().getMaid(); if (maid == null) return;
        // This event also fires for TLM's own page/toggle refreshes, which bypass ScreenEvent.Init.
        for (var listener : List.copyOf(event.getGui().children())) {
            if (listener instanceof TaskButton button && button.getTask() instanceof LibraryEntry entry) {
                FlowTaskRow row = new FlowTaskRow(maid, entry.graph, button.getX(), button.getY(), button.getWidth(), button.getHeight());
                row.visible = button.visible; button.visible = false;
                event.addButton("tlm_programmable:library/" + entry.graph.id(), row);
            }
        }
        int x = Math.max(4, Math.min(event.getGui().width - 92, event.getLeftPos() + 246));
        int y = Math.max(4, event.getTopPos() + 10);
        event.addButton("tlm_programmable:flow_editor", Button.builder(Component.literal("流程任务"),
                button -> openEditor(maid, FlowGraph.empty("新建任务"))).bounds(x, y, 88, 20).build());
    }
    public static void openEditor(EntityMaid maid, FlowGraph graph) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !maid.isAlive()) return;
        FlowNetwork.send(maid.getId(), maid.getUUID(), "editor");
        mc.player.closeContainer(); editing = new FlowEditorScreen(maid.getId(), maid.getUUID(), graph); mc.setScreen(editing);
    }
    public static void closeEditor(FlowEditorScreen screen, boolean reopenMenu) {
        if (editing == screen) editing = null;
        Minecraft mc = Minecraft.getInstance(); mc.setScreen(null);
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        boolean near = mc.level.getEntity(screen.maidEntityId()) instanceof EntityMaid maid
                && maid.getUUID().equals(screen.maidUuid()) && mc.player.distanceToSqr(maid) <= 256;
        if (!near) {
            // Walking away while picking a target is normal. Do not ask the server to reopen a menu
            // that the 16 block rule would reject (which used to leave a confusing error in chat).
            FlowNetwork.send(screen.maidEntityId(), screen.maidUuid(), "editor_close");
            if (reopenMenu) mc.player.displayClientMessage(Component.literal("已退出编辑：离女仆太远，未打开女仆界面"), true);
            return;
        }
        FlowNetwork.send(screen.maidEntityId(), screen.maidUuid(), reopenMenu ? "open_menu" : "editor_close");
    }
    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || editing == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != editing && !(mc.screen instanceof FlowSettingsScreen settings && settings.editor() == editing) && !WorldPickSession.owns(editing)) {
            FlowEditorScreen previous = editing; editing = null;
            if (mc.player != null && mc.getConnection() != null) FlowNetwork.send(previous.maidEntityId(), previous.maidUuid(), "editor_close");
            return;
        }
        if (++ticks % 40 != 0 || mc.player == null) return;
        EntityMaid maid = null;
        if (mc.level != null && mc.level.getEntity(editing.maidEntityId()) instanceof EntityMaid candidate
                && candidate.getUUID().equals(editing.maidUuid())) maid = candidate;
        if (maid == null) { editing.setSyncWarning("女仆不在附近加载范围内，编辑会话已暂停"); return; }
        if (mc.player.distanceToSqr(maid) > 64 * 64) {
            editing.setSyncWarning("已离女仆太远，编辑会话已暂停；靠近后自动恢复"); return;
        }
        FlowNetwork.send(editing.maidEntityId(), editing.maidUuid(), "editor");
    }
    private static void reply(FlowNetwork.Reply reply) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        EntityMaid maid = mc.level.getEntity(reply.entityId()) instanceof EntityMaid candidate
                && candidate.getUUID().equals(reply.maidId()) ? candidate : null;
        if (reply.command().equals("settings_read")) {
            if (mc.screen instanceof FlowSettingsScreen settings && settings.editor().maidUuid().equals(reply.maidId())) settings.receive(reply);
            return;
        }
        if (reply.command().equals("road_offer")) {
            if (mc.screen instanceof RoadApprovalScreen) {
                CompoundTag refusal = new CompoundTag(); refusal.putUUID("ticket", reply.data().getUUID("ticket")); refusal.putBoolean("allow", false);
                FlowNetwork.send(reply.entityId(), reply.maidId(), "road_answer", refusal);
            } else mc.setScreen(new RoadApprovalScreen(mc.screen, reply));
            return;
        }
        if (reply.data().contains("tasks", 9)) LIBRARIES.put(reply.maidId(), reply.data().copy());
        if (reply.ok() && reply.command().equals("save")) {
            try {
                Field page = AbstractMaidContainerGui.class.getDeclaredField("TASK_PAGE"); page.setAccessible(true); page.setInt(null, 0);
                Field open = AbstractMaidContainerGui.class.getDeclaredField("TASK_LIST_OPEN"); open.setAccessible(true); open.setBoolean(null, true);
            } catch (ReflectiveOperationException exception) { mc.player.sendSystemMessage(Component.literal("任务已保存，请翻到任务栏第一页查看")); }
        }
        if (reply.ok() && reply.command().equals("select") && maid != null)
            TaskManager.findTask(FlowWorkTask.UID).ifPresent(maid::setTask);
        boolean lease = reply.command().equals("editor") || reply.command().equals("editor_close") || reply.command().equals("read");
        if (!reply.ok() && !lease) mc.player.sendSystemMessage(Component.literal(reply.message()));
        if (mc.screen instanceof FlowEditorScreen editor && editor.maidUuid().equals(reply.maidId())) {
            if (lease) editor.setSyncWarning(reply.ok() ? "" : reply.message());
            editor.serverReply(reply);
        }
        if (mc.screen instanceof AbstractMaidContainerGui<?> gui && gui.getMenu().getMaid() != null
                && gui.getMenu().getMaid().getUUID().equals(reply.maidId())) gui.init(mc, gui.width, gui.height);
    }
    public static void command(EntityMaid maid, FlowGraph graph, String command) {
        CompoundTag payload = new CompoundTag(); payload.putUUID("task", graph.id()); payload.putInt("revision", revision(maid.getUUID()));
        FlowNetwork.send(maid.getId(), maid.getUUID(), command, payload);
    }
    private static final class LibraryEntry extends FlowWorkTask {
        final FlowGraph graph;
        LibraryEntry(FlowGraph graph) { this.graph = graph; }
        @Override public ResourceLocation getUid() { return new ResourceLocation("tlm_programmable", "library/" + graph.id()); }
        @Override public net.minecraft.network.chat.MutableComponent getName() { return Component.literal(graph.name()); }
        @Override public boolean isHidden(EntityMaid maid) { return false; }
    }
}
