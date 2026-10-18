package dev.tlmprogrammable.flow;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** A task selects an item TYPE, not a pristine template stack. Never strip NBT from the actual item. */
public final class FlowItems {
    private FlowItems() { }
    public static boolean matches(ItemStack stack, String selectedId) {
        if (stack.isEmpty() || selectedId == null) return false;
        ResourceLocation wanted = ResourceLocation.tryParse(selectedId.strip());
        return wanted != null && wanted.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }
}
