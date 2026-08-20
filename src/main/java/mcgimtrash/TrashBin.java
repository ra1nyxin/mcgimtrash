package mcgimtrash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

final class TrashBin {
    static final int PAGE_COUNT = 30;
    static final int PAGE_SIZE = 54;
    static final int PREVIOUS_PAGE_SLOT = 0;
    static final int NEXT_PAGE_SLOT = 8;
    static final int CONTENT_SLOTS_PER_PAGE = PAGE_SIZE - 2;
    static final int TOTAL_CONTENT_SLOTS = PAGE_COUNT * CONTENT_SLOTS_PER_PAGE;

    private static final int[] CONTENT_SLOTS = createContentSlots();

    private final McGimTrash plugin;
    private final NamespacedKey navigationKey;
    private final Inventory[] pages = new Inventory[PAGE_COUNT];

    TrashBin(McGimTrash plugin) {
        this.plugin = plugin;
        this.navigationKey = new NamespacedKey(plugin, "navigation");
        for (int page = 0; page < PAGE_COUNT; page++) {
            TrashPageHolder holder = new TrashPageHolder(this, page);
            Component title = Component.text("垃圾桶 " + (page + 1) + "/" + PAGE_COUNT,
                    NamedTextColor.DARK_GREEN);
            Inventory inventory = plugin.getServer().createInventory(holder, PAGE_SIZE, title);
            holder.bind(inventory);
            pages[page] = inventory;
            installNavigation(inventory, page);
        }
    }

    TrashPageHolder getPageHolder(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof TrashPageHolder pageHolder && pageHolder.owner() == this) {
            return pageHolder;
        }
        return null;
    }

    boolean isNavigationSlot(int slot) {
        return slot == PREVIOUS_PAGE_SLOT || slot == NEXT_PAGE_SLOT;
    }

    void openPage(Player player, int requestedPage) {
        int page = Math.floorMod(requestedPage, PAGE_COUNT);
        player.openInventory(pages[page]);
    }

    void closeAll() {
        for (Inventory page : pages) {
            page.close();
        }
    }

    long clearContents() {
        closeAll();
        long removed = 0L;
        for (Inventory page : pages) {
            for (int slot : CONTENT_SLOTS) {
                ItemStack item = page.getItem(slot);
                if (!isEmpty(item)) {
                    removed += item.getAmount();
                    page.setItem(slot, null);
                }
            }
        }
        return removed;
    }

    SweepResult sweepLoadedItems() {
        long collectedItems = 0L;
        long itemsLeftOnGround = 0L;
        int failures = 0;

        for (World world : plugin.getServer().getWorlds()) {
            List<Item> groundItems = new ArrayList<>(world.getEntitiesByClass(Item.class));
            for (Item entity : groundItems) {
                if (!entity.isValid() || entity.isDead()) {
                    continue;
                }

                ItemStack original = entity.getItemStack().clone();
                if (isEmpty(original) || original.getAmount() <= 0) {
                    continue;
                }

                InsertOperation insertion = insert(original);
                int accepted = original.getAmount() - insertion.remainingAmount();
                if (accepted <= 0) {
                    itemsLeftOnGround += original.getAmount();
                    continue;
                }

                try {
                    if (insertion.remainingAmount() == 0) {
                        entity.remove();
                    } else {
                        ItemStack remainder = original.clone();
                        remainder.setAmount(insertion.remainingAmount());
                        entity.setItemStack(remainder);
                        itemsLeftOnGround += insertion.remainingAmount();
                    }
                    collectedItems += accepted;
                } catch (RuntimeException exception) {
                    insertion.rollback();
                    failures++;
                    itemsLeftOnGround += original.getAmount();
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to collect ground item " + entity.getUniqueId(), exception);
                }
            }
        }

        return new SweepResult(collectedItems, itemsLeftOnGround, failures);
    }

    ItemStack[] snapshotContents() {
        ItemStack[] snapshot = new ItemStack[TOTAL_CONTENT_SLOTS];
        int index = 0;
        for (Inventory page : pages) {
            for (int slot : CONTENT_SLOTS) {
                ItemStack item = page.getItem(slot);
                snapshot[index++] = isEmpty(item) ? null : item.clone();
            }
        }
        return snapshot;
    }

    void restoreContents(ItemStack[] contents) {
        if (contents.length != TOTAL_CONTENT_SLOTS) {
            throw new IllegalArgumentException("Expected " + TOTAL_CONTENT_SLOTS
                    + " trash slots, got " + contents.length);
        }

        int index = 0;
        for (int pageIndex = 0; pageIndex < PAGE_COUNT; pageIndex++) {
            Inventory page = pages[pageIndex];
            for (int slot : CONTENT_SLOTS) {
                ItemStack item = contents[index++];
                page.setItem(slot, isEmpty(item) ? null : item.clone());
            }
            installNavigation(page, pageIndex);
        }
    }

    private InsertOperation insert(ItemStack source) {
        int remaining = source.getAmount();
        List<SlotChange> changes = new ArrayList<>();

        for (Inventory page : pages) {
            for (int slot : CONTENT_SLOTS) {
                ItemStack existing = page.getItem(slot);
                if (isEmpty(existing) || !existing.isSimilar(source)) {
                    continue;
                }
                int limit = Math.min(existing.getMaxStackSize(), page.getMaxStackSize());
                int available = limit - existing.getAmount();
                if (available <= 0) {
                    continue;
                }
                int moved = Math.min(remaining, available);
                changes.add(new SlotChange(page, slot, existing.clone()));
                ItemStack merged = existing.clone();
                merged.setAmount(existing.getAmount() + moved);
                page.setItem(slot, merged);
                remaining -= moved;
                if (remaining == 0) {
                    return new InsertOperation(0, changes);
                }
            }
        }

        for (Inventory page : pages) {
            for (int slot : CONTENT_SLOTS) {
                ItemStack existing = page.getItem(slot);
                if (!isEmpty(existing)) {
                    continue;
                }
                int limit = Math.min(source.getMaxStackSize(), page.getMaxStackSize());
                if (limit <= 0) {
                    continue;
                }
                int moved = Math.min(remaining, limit);
                changes.add(new SlotChange(page, slot, null));
                ItemStack placed = source.clone();
                placed.setAmount(moved);
                page.setItem(slot, placed);
                remaining -= moved;
                if (remaining == 0) {
                    return new InsertOperation(0, changes);
                }
            }
        }

        return new InsertOperation(remaining, changes);
    }

    private void installNavigation(Inventory inventory, int page) {
        inventory.setItem(PREVIOUS_PAGE_SLOT, navigationButton(
                "上一页", -1, "前往第 " + (Math.floorMod(page - 1, PAGE_COUNT) + 1) + " 页"));
        inventory.setItem(NEXT_PAGE_SLOT, navigationButton(
                "下一页", 1, "前往第 " + (Math.floorMod(page + 1, PAGE_COUNT) + 1) + " 页"));
    }

    private ItemStack navigationButton(String name, int direction, String lore) {
        ItemStack button = ItemStack.of(Material.WHITE_STAINED_GLASS_PANE);
        button.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(navigationKey, PersistentDataType.INTEGER, direction);
        });
        return button;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    private static int[] createContentSlots() {
        int[] slots = new int[CONTENT_SLOTS_PER_PAGE];
        int index = 0;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            if (slot != PREVIOUS_PAGE_SLOT && slot != NEXT_PAGE_SLOT) {
                slots[index++] = slot;
            }
        }
        return slots;
    }

    private record SlotChange(Inventory inventory, int slot, ItemStack previous) {
        void rollback() {
            inventory.setItem(slot, previous == null ? null : previous.clone());
        }
    }

    private record InsertOperation(int remainingAmount, List<SlotChange> changes) {
        void rollback() {
            List<SlotChange> reversed = new ArrayList<>(changes);
            Collections.reverse(reversed);
            for (SlotChange change : reversed) {
                change.rollback();
            }
        }
    }
}
