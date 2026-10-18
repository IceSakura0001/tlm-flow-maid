package dev.tlmprogrammable.flow.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Owner-scoped rules in overworld SavedData, independent of maid death and task libraries. */
public final class RoadRules extends SavedData {
    private CompoundTag owners = new CompoundTag();
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, RoadRules::destroying);
    }
    private static void destroying(net.minecraftforge.event.entity.living.LivingDestroyBlockEvent event) {
        if (event.getEntity() instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid
                && maid.getOwner() instanceof ServerPlayer owner && owner.level() == maid.level()
                && get(owner).forbids(owner, event.getPos(), ForgeRegistries.BLOCKS.getKey(event.getState().getBlock()).toString())) event.setCanceled(true);
    }
    public static RoadRules get(ServerPlayer owner) { return owner.server.overworld().getDataStorage().computeIfAbsent(RoadRules::load, RoadRules::new, "tlm_road_experiment_rules"); }
    public static RoadRules load(CompoundTag tag) { RoadRules result = new RoadRules(); result.owners = tag.getCompound("owners").copy(); return result; }
    @Override public CompoundTag save(CompoundTag tag) { tag.put("owners", owners.copy()); return tag; }
    public CompoundTag view(ServerPlayer owner) { return owners.getCompound(owner.getUUID().toString()).copy(); }
    public void change(ServerPlayer owner, CompoundTag request) {
        CompoundTag rules = view(owner); String action = request.getString("action");
        ListTag areas = rules.getList("areas", 10), blocks = rules.getList("blocks", 8);
        switch (action) {
            case "settings" -> rules.putBoolean("roadEnabled", request.getBoolean("roadEnabled"));
            case "area" -> {
                if (areas.size() >= 32) throw new IllegalArgumentException("最多32个禁拆区域");
                BlockPos a = parse(request.getString("a")), b = parse(request.getString("b")); CompoundTag area = new CompoundTag();
                area.putString("dimension", owner.level().dimension().location().toString());
                area.putInt("minX", Math.min(a.getX(), b.getX())); area.putInt("maxX", Math.max(a.getX(), b.getX()));
                area.putInt("minZ", Math.min(a.getZ(), b.getZ())); area.putInt("maxZ", Math.max(a.getZ(), b.getZ())); areas.add(area);
            }
            case "block" -> {
                if (blocks.size() >= 64) throw new IllegalArgumentException("最多64种禁拆方块");
                ResourceLocation id = ResourceLocation.tryParse(request.getString("block"));
                if (id == null || !ForgeRegistries.BLOCKS.containsKey(id)) throw new IllegalArgumentException("请选择有效方块类型");
                if (!blocks.contains(StringTag.valueOf(id.toString()))) blocks.add(StringTag.valueOf(id.toString()));
            }
            case "clear_areas" -> areas = new ListTag();
            case "clear_blocks" -> blocks = new ListTag();
            default -> throw new IllegalArgumentException("未知道路保护设置");
        }
        rules.put("areas", areas); rules.put("blocks", blocks); rules.putInt("revision", rules.getInt("revision") + 1);
        owners.put(owner.getUUID().toString(), rules); setDirty();
    }
    public boolean enabled(ServerPlayer owner) { return view(owner).getBoolean("roadEnabled"); }
    public boolean forbids(ServerPlayer owner, BlockPos pos, String block) {
        CompoundTag rules = view(owner); String dimension = owner.level().dimension().location().toString();
        for (Tag tag : rules.getList("blocks", 8)) if (tag.getAsString().equals(block)) return true;
        for (Tag tag : rules.getList("areas", 10)) {
            CompoundTag area = (CompoundTag) tag;
            if (dimension.equals(area.getString("dimension")) && pos.getX() >= area.getInt("minX") && pos.getX() <= area.getInt("maxX")
                    && pos.getZ() >= area.getInt("minZ") && pos.getZ() <= area.getInt("maxZ")) return true;
        }
        return false;
    }
    public static BlockPos parse(String text) {
        try { String[] parts = text.split(","); if (parts.length != 3) throw new IllegalArgumentException();
            return new BlockPos(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()), Integer.parseInt(parts[2].strip()));
        } catch (RuntimeException error) { throw new IllegalArgumentException("请先点选两个有效对角点"); }
    }
}
