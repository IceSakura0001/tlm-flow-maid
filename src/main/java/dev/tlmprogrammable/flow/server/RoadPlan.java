package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.ForgeEventFactory;
import java.util.*;

/** Incremental short-range construction planner; no chunk forcing and at most 96 expansions per tick. */
public final class RoadPlan {
    public enum Kind { PLACE, BREAK }
    public record Edit(BlockPos pos, BlockState expected, Kind kind) { }
    public record Step(BlockPos feet, List<Edit> edits) { }
    public static final int MAX_STEPS = 24, MAX_EDITS = 48;
    private record Search(BlockPos feet, int cost, int depth, int score) { }
    private final EntityMaid maid; private final ServerPlayer owner; private final BlockPos start, goal;
    private final PriorityQueue<Search> open = new PriorityQueue<>(Comparator.comparingInt(Search::score));
    private final Map<BlockPos, Integer> costs = new HashMap<>();
    private final Map<BlockPos, BlockPos> previous = new HashMap<>();
    private final Map<BlockPos, List<Edit>> changes = new HashMap<>();
    private int expanded; private boolean done; private List<Step> result = List.of();
    public RoadPlan(EntityMaid maid, ServerPlayer owner, BlockPos goal) {
        this.maid = maid; this.owner = owner; this.start = maid.blockPosition(); this.goal = goal;
        costs.put(start, 0); open.add(new Search(start, 0, 0, distance(start, goal)));
    }
    private static int distance(BlockPos a, BlockPos b) { return a.distManhattan(b); }
    public boolean tick() {
        for (int budget = 0; budget < 96 && !open.isEmpty() && !done; budget++) {
            Search current = open.remove(); if (costs.getOrDefault(current.feet(), Integer.MAX_VALUE) != current.cost()) continue;
            if (++expanded > 4096) { done = true; break; }
            if (current.feet().equals(goal)) { build(current.feet()); done = true; break; }
            if (current.depth() >= MAX_STEPS) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) {
                BlockPos next = current.feet().relative(direction).offset(0, dy, 0);
                if (Math.abs(next.getX() - start.getX()) > 16 || Math.abs(next.getZ() - start.getZ()) > 16 || Math.abs(next.getY() - start.getY()) > 6) continue;
                List<Edit> edits = inspect(maid, owner, next);
                if (edits == null || conflictsWithBuiltRoute(current.feet(), next, edits)) continue;
                // A step upwards additionally needs headroom over the current standing position.
                if (dy > 0 && !maid.level().getBlockState(current.feet().above(2)).getCollisionShape(maid.level(), current.feet().above(2)).isEmpty()) continue;
                int cost = current.cost() + 10 + Math.abs(dy) * 4 + edits.stream().mapToInt(edit -> edit.kind() == Kind.BREAK ? 40 : 18).sum();
                if (cost >= costs.getOrDefault(next, Integer.MAX_VALUE)) continue;
                costs.put(next, cost); previous.put(next, current.feet()); changes.put(next, edits);
                open.add(new Search(next, cost, current.depth() + 1, cost + distance(next, goal) * 10));
            }
        }
        if (open.isEmpty()) done = true; return done;
    }
    /** Future stairs must not fill a previously visited standing space, or route through
     * supports already planned. Otherwise an apparent zigzag becomes a solid pillar. */
    private boolean conflictsWithBuiltRoute(BlockPos from, BlockPos next, List<Edit> edits) {
        BlockPos at = from;
        for (int depth = 0; depth <= MAX_STEPS && at != null; depth++, at = previous.get(at)) {
            for (Edit edit : edits) if (edit.kind() == Kind.PLACE && (edit.pos().equals(at) || edit.pos().equals(at.above()))) return true;
            for (Edit edit : changes.getOrDefault(at, List.of())) if (edit.kind() == Kind.PLACE
                    && (edit.pos().equals(next) || edit.pos().equals(next.above()))) return true;
        }
        return false;
    }
    private void build(BlockPos at) {
        List<Step> reversed = new ArrayList<>();
        while (!at.equals(start)) { reversed.add(new Step(at, List.copyOf(changes.get(at)))); at = previous.get(at); }
        Collections.reverse(reversed);
        if (!reversed.isEmpty()) reversed.add(0, new Step(start, List.of()));
        long edits = reversed.stream().flatMap(step -> step.edits().stream()).map(Edit::pos).distinct().count();
        if (edits <= MAX_EDITS) result = List.copyOf(reversed);
    }
    public List<Step> result() { return result; }
    public String describe() { return start.toShortString() + " -> " + goal.toShortString() + " (" + expanded + ")"; }
    public static List<Edit> inspect(EntityMaid maid, ServerPlayer owner, BlockPos feet) {
        List<Edit> edits = new ArrayList<>();
        for (BlockPos pos : List.of(feet, feet.above(), feet.below())) {
            if (!maid.level().isInWorldBounds(pos) || !maid.level().hasChunkAt(pos) || !maid.level().getWorldBorder().isWithinBounds(pos)
                    || !maid.level().mayInteract(owner, pos) || !maid.level().getFluidState(pos).isEmpty()) return null;
        }
        for (BlockPos pos : List.of(feet, feet.above())) {
            BlockState state = maid.level().getBlockState(pos);
            if (!state.getCollisionShape(maid.level(), pos).isEmpty()) {
                if (!breakAllowed(maid, owner, pos)) return null;
                edits.add(new Edit(pos.immutable(), state, Kind.BREAK));
            }
        }
        BlockPos support = feet.below(); BlockState state = maid.level().getBlockState(support);
        if (!state.isFaceSturdy(maid.level(), support, Direction.UP)) {
            if (!state.isAir()) return null;
            edits.add(new Edit(support, state, Kind.PLACE));
        }
        return edits;
    }
    public static boolean breakAllowed(EntityMaid maid, ServerPlayer owner, BlockPos pos) {
        var state = maid.level().getBlockState(pos);
        for (Direction side : Direction.values()) {
            BlockPos adjacent = pos.relative(side);
            if (!maid.level().hasChunkAt(adjacent) || !maid.level().getFluidState(adjacent).isEmpty()
                    || maid.level().getBlockState(adjacent).getBlock() instanceof FallingBlock) return false;
        }
        return maid.level().hasChunkAt(pos) && maid.level().mayInteract(owner, pos) && !state.isAir()
                && !state.hasBlockEntity() && state.getDestroySpeed(maid.level(), pos) >= 0 && state.getDestroySpeed(maid.level(), pos) <= 50
                && maid.level().getFluidState(pos).isEmpty() && !(state.getBlock() instanceof FallingBlock) && !(state.getBlock() instanceof DoorBlock)
                && !(state.getBlock() instanceof BedBlock) && !(state.getBlock() instanceof DoublePlantBlock)
                && !(state.getBlock() instanceof TntBlock) && !state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                && !RoadRules.get(owner).forbids(owner, pos, ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString())
                && ForgeEventFactory.getMobGriefingEvent(maid.level(), maid) && maid.canDestroyBlock(pos)
                && FlowEquipment.usable(maid.getMainHandItem()) && (!state.requiresCorrectToolForDrops() || maid.getMainHandItem().isCorrectToolForDrops(state));
    }
}
