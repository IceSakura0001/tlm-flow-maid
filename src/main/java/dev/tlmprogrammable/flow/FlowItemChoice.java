package dev.tlmprogrammable.flow;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.items.IItemHandler;
import java.util.*;

/** Fewest enchantment entries first, then highest remaining durability; a 5% band is randomized. */
public final class FlowItemChoice {
    public static final double DURABILITY_TOLERANCE = .05;
    private record Choice(int slot, int enchantments, double durability) { }
    private FlowItemChoice() { }
    public static List<Integer> order(IItemHandler inventory, String id, RandomSource random) {
        List<Choice> choices = new ArrayList<>();
        for (int slot = 0; slot < Math.min(inventory.getSlots(), 256); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!FlowItems.matches(stack, id) || inventory.extractItem(slot, 1, true).isEmpty()) continue;
            double durability = !stack.isDamageableItem() ? 1 : Math.max(0, (stack.getMaxDamage() - stack.getDamageValue()) / (double) Math.max(1, stack.getMaxDamage()));
            choices.add(new Choice(slot, EnchantmentHelper.getEnchantments(stack).size(), durability));
        }
        choices.sort(Comparator.comparingInt(Choice::enchantments).thenComparing(Comparator.comparingDouble(Choice::durability).reversed()));
        List<Integer> result = new ArrayList<>();
        for (int first = 0; first < choices.size();) {
            Choice best = choices.get(first); int last = first + 1;
            while (last < choices.size() && choices.get(last).enchantments() == best.enchantments()
                    && choices.get(last).durability() + DURABILITY_TOLERANCE + 1e-9 >= best.durability()) last++;
            List<Integer> tied = new ArrayList<>(); for (int index = first; index < last; index++) tied.add(choices.get(index).slot());
            for (int index = tied.size() - 1; index > 0; index--) Collections.swap(tied, index, random.nextInt(index + 1));
            result.addAll(tied); first = last;
        }
        return List.copyOf(result);
    }
}
