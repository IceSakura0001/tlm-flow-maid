package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraftforge.items.ItemHandlerHelper;
import dev.tlmprogrammable.flow.FlowItems;

/** Equip only hand-held equipment, retaining NBT/durability and never discarding an occupied hand. */
public final class FlowEquipment {
    private FlowEquipment() { }
    public static boolean usable(ItemStack stack) {
        return !stack.isEmpty() && !stack.isEdible() && Mob.getEquipmentSlotForItem(stack) == EquipmentSlot.MAINHAND
                && (stack.getItem() instanceof TieredItem || stack.getItem() instanceof ProjectileWeaponItem
                || com.github.tartaricacid.touhoulittlemaid.compat.gun.tacz.TacCompat.isGun(stack)
                || com.github.tartaricacid.touhoulittlemaid.compat.gun.swarfare.SWarfareCompat.isGun(stack)
                || stack.isDamageableItem() || stack.getUseDuration() > 0
                || !stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE).isEmpty());
    }
    /** Direct acquisition avoids needing a temporary free backpack slot for the incoming tool. */
    public static boolean takeOneIntoHand(EntityMaid maid, net.minecraftforge.items.IItemHandler source, int slot) {
        ItemStack preview = source.extractItem(slot, 1, true);
        if (!usable(preview) || !maid.getHandsInvWrapper().isItemValid(0, preview)) return false;
        var backpack = maid.getAvailableBackpackInv(); ItemStack old = maid.getMainHandItem().copy();
        if (!old.isEmpty() && !ItemHandlerHelper.insertItemStacked(backpack, old.copy(), true).isEmpty()) return false;
        if (!old.isEmpty()) {
            maid.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(backpack, old, false);
            if (!remainder.isEmpty()) { maid.setItemSlot(EquipmentSlot.MAINHAND, remainder); return false; }
        }
        ItemStack acquired = source.extractItem(slot, 1, false);
        if (acquired.isEmpty()) return false; // Previous equipment remains safely in the backpack.
        maid.setItemSlot(EquipmentSlot.MAINHAND, acquired); return true;
    }
    public static void equipAcquired(EntityMaid maid, String id) {
        var backpack = maid.getAvailableBackpackInv();
        if (FlowItems.matches(maid.getMainHandItem(), id)) return;
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (!FlowItems.matches(stack, id) || !usable(stack)) continue;
            ItemStack incoming = backpack.extractItem(slot, 1, true);
            if (incoming.isEmpty() || !maid.getHandsInvWrapper().isItemValid(0, incoming)) return;
            ItemStack old = maid.getMainHandItem().copy();
            // A full backpack keeps the new tool in its slot; it must never lose either item.
            if (!old.isEmpty() && !ItemHandlerHelper.insertItemStacked(backpack, old.copy(), true).isEmpty()) return;
            incoming = backpack.extractItem(slot, 1, false);
            if (incoming.isEmpty()) return;
            if (!old.isEmpty()) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(backpack, old.copy(), false);
                if (!remainder.isEmpty()) {
                    // Nonconforming mod handlers may reject a previously simulated insertion.
                    ItemStack rollback = ItemHandlerHelper.insertItemStacked(backpack, incoming, false);
                    maid.setItemSlot(EquipmentSlot.MAINHAND, remainder);
                    if (!rollback.isEmpty()) maid.spawnAtLocation(rollback);
                    return;
                }
            }
            maid.setItemSlot(EquipmentSlot.MAINHAND, incoming); return;
        }
    }
}
