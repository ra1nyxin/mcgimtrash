package mcgimtrash;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class TrashPageHolder implements InventoryHolder {
    private final TrashBin owner;
    private final int pageIndex;
    private Inventory inventory;

    TrashPageHolder(TrashBin owner, int pageIndex) {
        this.owner = owner;
        this.pageIndex = pageIndex;
    }

    TrashBin owner() {
        return owner;
    }

    int pageIndex() {
        return pageIndex;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
