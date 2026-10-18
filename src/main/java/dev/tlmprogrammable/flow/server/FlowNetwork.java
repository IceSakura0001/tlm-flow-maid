package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.tlmprogrammable.flow.FlowGraphNbt;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FlowNetwork {
    private static final String VERSION = "road-experiment-1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("tlm_programmable", "flows"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static final Map<ServerPlayer, long[]> BUDGETS = new WeakHashMap<>();
    private static Consumer<Reply> clientSink = reply -> { };
    private FlowNetwork() { }
    public static void init() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(Request::encode)
                .decoder(Request::decode).consumerMainThread(Request::handle).add();
        CHANNEL.messageBuilder(Reply.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(Reply::encode)
                .decoder(Reply::decode).consumerMainThread(Reply::handle).add();
    }
    public static void clientSink(Consumer<Reply> sink) { clientSink = sink; }
    public static void send(int entityId, UUID maidId, String command, CompoundTag payload) {
        CHANNEL.sendToServer(new Request(entityId, maidId, command, payload));
    }
    public static void send(int entityId, UUID maidId, String command) { send(entityId, maidId, command, new CompoundTag()); }
    private static EntityMaid authorize(ServerPlayer player, int id, UUID uuid) {
        return authorize(player, id, uuid, 256);
    }
    /**
     * @param maxDistanceSqr management radius. Ordinary edits stay inside 16 blocks; the editor lease
     *                       allows a wider range because the owner may walk around while picking targets.
     */
    private static EntityMaid authorize(ServerPlayer player, int id, UUID uuid, double maxDistanceSqr) {
        if (!(player.serverLevel().getEntity(id) instanceof EntityMaid maid) || !maid.getUUID().equals(uuid)
                || !maid.isAlive() || player.isSpectator() || !player.getUUID().equals(maid.getOwnerUUID())
                || player.distanceToSqr(maid) > maxDistanceSqr) throw new IllegalArgumentException("仅主人可在女仆 16 格内管理流程任务");
        return maid;
    }
    public static void push(ServerPlayer player, Reply reply) { reply(player, reply); }
    private static void reply(ServerPlayer player, Reply reply) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply); }
    private record Request(int entityId, UUID maidId, String command, CompoundTag payload) {
        void encode(FriendlyByteBuf buf) { buf.writeVarInt(entityId); buf.writeUUID(maidId); buf.writeUtf(command, 24); buf.writeNbt(payload); }
        static Request decode(FriendlyByteBuf buf) {
            Request request = new Request(buf.readVarInt(), buf.readUUID(), buf.readUtf(24), buf.readNbt(new NbtAccounter(262144)));
            if (request.payload == null || buf.readableBytes() != 0) throw new IllegalArgumentException("流程数据包无效");
            return request;
        }
        void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get(); ServerPlayer player = context.getSender();
            context.setPacketHandled(true);
            if (player == null) return;
            EntityMaid maid = null;
            try {
                boolean lease = command.equals("editor") || command.equals("editor_close") || command.equals("road_rules") || command.equals("settings_read");
                maid = authorize(player, entityId, maidId, command.equals("road_answer") ? 128 * 128 : lease ? 64 * 64 : 256);
                long now = player.serverLevel().getGameTime();
                long[] budget = BUDGETS.computeIfAbsent(player, ignored -> new long[]{now, 0});
                if (now - budget[0] >= 40 || now < budget[0]) { budget[0] = now; budget[1] = 0; }
                if (++budget[1] > 30) throw new IllegalArgumentException("操作过于频繁，请稍候");
                String message = "";
                switch (command) {
                    case "read" -> { }
                    case "settings_read" -> { reply(player, new Reply(entityId, maidId, command, true, "", RoadRules.get(player).view(player))); return; }
                    case "road_answer" -> { RoadRun.answer(player, maid, payload.getUUID("ticket"), payload.getBoolean("allow")); message = "本次规划选择已提交"; }
                    case "road_rules" -> { RoadRules.get(player).change(player, payload); var rules = RoadRules.get(player).view(player); reply(player, new Reply(entityId, maidId, "settings_read", true, "", rules)); message = "禁拆规则已保存：" + rules.getList("areas", 10).size() + "个全高度区域，" + rules.getList("blocks", 8).size() + "种方块"; }
                    case "save" -> {
                        FlowLibrary.save(maid, FlowGraphNbt.read(payload.getCompound("graph")), payload.getInt("revision"));
                        message = "任务已保存到女仆任务栏";
                    }
                    case "delete" -> {
                        FlowLibrary.delete(maid, payload.getUUID("task"), payload.getInt("revision")); message = "任务已删除";
                    }
                    case "select" -> {
                        FlowRuntime.select(maid, FlowLibrary.find(maid, payload.getUUID("task")));
                        message = "已选择自定义任务，关闭女仆界面后开始执行";
                    }
                    case "editor" -> {
                        CompoundTag data = FlowLibrary.data(maid); data.putLong("editorUntil", now + 240); FlowLibrary.put(maid, data); return;
                    }
                    case "editor_close" -> {
                        CompoundTag data = FlowLibrary.data(maid); data.remove("editorUntil"); FlowLibrary.put(maid, data); return;
                    }
                    case "open_menu" -> {
                        CompoundTag data = FlowLibrary.data(maid); data.remove("editorUntil"); FlowLibrary.put(maid, data);
                        maid.openMaidGui(player); return;
                    }
                    default -> throw new IllegalArgumentException("未知流程任务操作");
                }
                reply(player, new Reply(entityId, maidId, command, true, message, FlowLibrary.view(maid)));
                if (!message.isEmpty() && !command.equals("save")) player.displayClientMessage(Component.literal(message), true);
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null ? "任务操作失败" : exception.getMessage();
                reply(player, new Reply(entityId, maidId, command, false, message, maid == null ? new CompoundTag() : FlowLibrary.view(maid)));
            }
            context.setPacketHandled(true);
        }
    }
    public record Reply(int entityId, UUID maidId, String command, boolean ok, String message, CompoundTag data) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(entityId); buf.writeUUID(maidId); buf.writeUtf(command, 24); buf.writeBoolean(ok);
            buf.writeUtf(message, 1024); buf.writeNbt(data);
        }
        static Reply decode(FriendlyByteBuf buf) {
            Reply reply = new Reply(buf.readVarInt(), buf.readUUID(), buf.readUtf(24), buf.readBoolean(),
                    buf.readUtf(1024), buf.readNbt(new NbtAccounter(4194304)));
            if (reply.data == null || buf.readableBytes() != 0) throw new IllegalArgumentException("任务库数据包无效");
            return reply;
        }
        void handle(Supplier<NetworkEvent.Context> supplier) { clientSink.accept(this); supplier.get().setPacketHandled(true); }
    }
}
