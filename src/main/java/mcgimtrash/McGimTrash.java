package mcgimtrash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class McGimTrash extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {
    private static final long STORAGE_RETRY_MILLIS = 30L * 1000L;

    private TrashBin trashBin;
    private StateStore stateStore;
    private PluginSettings settings;
    private BukkitTask clockTask;
    private BukkitTask queuedSaveTask;
    private int completedSweeps;
    private long nextSweepAt;
    private int warningMask;
    private long nextStorageRetryAt;
    private boolean dirty;
    private boolean storageAvailable = true;
    private boolean preserveBackupOnNextSave;
    private boolean initialized;
    private boolean shuttingDown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = readSettingsFile();
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            getLogger().log(Level.SEVERE,
                    "Configuration is invalid; disabling mcgimtrash.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        trashBin = new TrashBin(this);
        stateStore = new StateStore(getDataFolder().toPath());

        boolean shouldRewriteState = false;
        try {
            StateStore.LoadResult loadResult = stateStore.load();
            long now = System.currentTimeMillis();
            if (loadResult == null) {
                completedSweeps = 0;
                nextSweepAt = now + settings.sweepIntervalMillis();
                warningMask = 0;
                shouldRewriteState = true;
            } else {
                StateStore.StoredState state = loadResult.state();
                trashBin.restoreContents(state.items());
                completedSweeps = state.completedSweeps();
                nextSweepAt = state.nextSweepAt();
                warningMask = state.warningMask();
                if (loadResult.recoveredFromBackup()) {
                    getLogger().warning("Recovered trash state from backup file.");
                    preserveBackupOnNextSave = true;
                    shouldRewriteState = true;
                }
                if (nextSweepAt <= 0
                        || nextSweepAt > now + settings.sweepIntervalMillis()) {
                    nextSweepAt = now + settings.sweepIntervalMillis();
                    warningMask = 0;
                    shouldRewriteState = true;
                }
            }
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.SEVERE,
                    "Trash state is unreadable; disabling mcgimtrash without sweeping items.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("mcgimtrash");
        if (command == null) {
            getLogger().severe("Command mcgimtrash is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);

        initialized = true;
        if (shouldRewriteState) {
            dirty = true;
            saveNow();
        }
        clockTask = getServer().getScheduler().runTaskTimer(this, this::tickClock, 10L, 10L);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (clockTask != null) {
            clockTask.cancel();
        }
        if (queuedSaveTask != null) {
            queuedSaveTask.cancel();
            queuedSaveTask = null;
        }
        if (trashBin != null) {
            trashBin.closeAll();
        }
        if (initialized) {
            dirty = true;
            saveNow();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mcgimtrash.admin")) {
                sender.sendMessage(Component.text("你没有权限重载 mcgimtrash。",
                        NamedTextColor.RED));
                return true;
            }
            reloadPluginSettings(sender);
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(Component.text("用法：/" + label + " [reload]",
                    NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("控制台请使用 /mcgimtrash reload。");
            return true;
        }
        if (!storageAvailable) {
            player.sendMessage(Component.text("垃圾桶存储暂时不可用，请稍后重试。", NamedTextColor.RED));
            return true;
        }
        trashBin.openPage(player, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("mcgimtrash.admin")
                && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("reload");
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (trashBin.getPageHolder(event.getInventory()) != null && !storageAvailable) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        TrashPageHolder holder = trashBin.getPageHolder(top);
        if (holder == null) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < top.getSize()) {
            if (trashBin.isNavigationSlot(rawSlot)) {
                event.setCancelled(true);
                if (storageAvailable && event.getWhoClicked() instanceof Player player) {
                    int direction = rawSlot == TrashBin.PREVIOUS_PAGE_SLOT ? -1 : 1;
                    getServer().getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            trashBin.openPage(player, holder.pageIndex() + direction);
                        }
                    });
                }
                return;
            }

            if (!storageAvailable || !isAllowedWithdrawal(event.getAction())) {
                event.setCancelled(true);
                return;
            }
            if (event.getAction() != InventoryAction.NOTHING) {
                queueStateSave();
            }
            return;
        }

        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.UNKNOWN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (trashBin.getPageHolder(top) == null) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (trashBin.getPageHolder(event.getInventory()) != null && dirty) {
            queueStateSave();
        }
    }

    private boolean isAllowedWithdrawal(InventoryAction action) {
        return switch (action) {
            case NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                    MOVE_TO_OTHER_INVENTORY -> true;
            default -> false;
        };
    }

    private void tickClock() {
        long now = System.currentTimeMillis();
        if (!storageAvailable) {
            if (now >= nextStorageRetryAt) {
                saveNow();
            }
            return;
        }
        if (now >= nextSweepAt) {
            performSweep(now);
            return;
        }
        sendDueReminder(now);
    }

    private void sendDueReminder(long now) {
        long remaining = nextSweepAt - now;
        PluginSettings.Reminder due = null;
        for (PluginSettings.Reminder reminder : settings.reminders()) {
            if (remaining <= reminder.leadTimeMillis()
                    && (warningMask & reminder.mask()) == 0) {
                due = reminder;
                break;
            }
        }
        if (due == null) {
            return;
        }

        for (PluginSettings.Reminder reminder : settings.reminders()) {
            if (reminder.leadTimeMillis() >= due.leadTimeMillis()) {
                warningMask |= reminder.mask();
            }
        }
        dirty = true;
        saveNow();
        Component message = messagePrefix()
                .append(Component.text("距离清理地面掉落物还有 " + due.label() + "。",
                        NamedTextColor.AQUA));
        if (completedSweeps >= settings.sweepsPerCycle()) {
            message = message.append(Component.text(
                    " 本次清扫将开启新周期并清空旧垃圾桶。", NamedTextColor.YELLOW));
        }
        getServer().broadcast(message);
    }

    private void performSweep(long now) {
        boolean startedNewCycle = completedSweeps >= settings.sweepsPerCycle();
        long purgedItems = 0L;
        if (startedNewCycle) {
            purgedItems = trashBin.clearContents();
            completedSweeps = 0;
        }

        SweepResult result = trashBin.sweepLoadedItems();
        completedSweeps++;
        nextSweepAt = now + settings.sweepIntervalMillis();
        warningMask = 0;
        dirty = true;
        saveNow();
        broadcastSweepResult(result, startedNewCycle, purgedItems);
    }

    private void broadcastSweepResult(
            SweepResult result, boolean startedNewCycle, long purgedItems) {
        Component message = messagePrefix()
                .append(Component.text("已清理 " + result.collectedItems()
                        + " 件地面掉落物。", NamedTextColor.AQUA));
        if (startedNewCycle) {
            message = message.append(Component.text(
                    " 新周期开始，旧垃圾桶已清空 " + purgedItems + " 件物品。",
                    NamedTextColor.GRAY));
        }
        if (result.itemsLeftOnGround() > 0) {
            message = message.append(Component.text(
                    " 垃圾桶容量不足，" + result.itemsLeftOnGround() + " 件仍留在地面。",
                    NamedTextColor.YELLOW));
        }
        if (result.failures() > 0) {
            message = message.append(Component.text(
                    " 有 " + result.failures() + " 组物品处理失败并留在原处。",
                    NamedTextColor.RED));
        }

        Component openButton = Component.text("[打开垃圾桶]", NamedTextColor.GREEN)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/mcgimtrash"))
                .hoverEvent(HoverEvent.showText(
                        Component.text("点击查看本轮收集的物品", NamedTextColor.GRAY)));
        getServer().broadcast(message.append(Component.space()).append(openButton));
    }

    private void queueStateSave() {
        dirty = true;
        if (shuttingDown || !storageAvailable || queuedSaveTask != null) {
            return;
        }
        queuedSaveTask = getServer().getScheduler().runTask(this, () -> {
            queuedSaveTask = null;
            if (dirty) {
                saveNow();
            }
        });
    }

    private void reloadPluginSettings(CommandSender sender) {
        PluginSettings reloaded;
        try {
            reloaded = readSettingsFile();
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            getLogger().log(Level.WARNING,
                    "Cannot reload invalid mcgimtrash configuration.", exception);
            sender.sendMessage(Component.text(
                    "配置重载失败，插件继续使用原有设置；请检查控制台日志。",
                    NamedTextColor.RED));
            return;
        }

        settings = reloaded;
        long now = System.currentTimeMillis();
        long latestAllowedSweep = now + settings.sweepIntervalMillis();
        if (nextSweepAt > latestAllowedSweep) {
            nextSweepAt = latestAllowedSweep;
        }
        warningMask = 0;
        dirty = true;
        boolean persisted = saveNow();

        Component result = Component.text("mcgimtrash 配置已重载。", NamedTextColor.GREEN);
        if (!persisted) {
            result = result.append(Component.text(
                    " 状态存储仍不可用，清扫和垃圾桶已暂停。", NamedTextColor.RED));
        }
        sender.sendMessage(result);
    }

    private PluginSettings readSettingsFile()
            throws IOException, InvalidConfigurationException {
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.load(getDataFolder().toPath().resolve("config.yml").toFile());
        return PluginSettings.from(candidate);
    }

    private Component messagePrefix() {
        return Component.text(settings.messagePrefix() + " ", NamedTextColor.DARK_AQUA);
    }

    private boolean saveNow() {
        if (stateStore == null || trashBin == null) {
            return false;
        }
        boolean wasUnavailable = !storageAvailable;
        try {
            stateStore.save(new StateStore.StoredState(
                            completedSweeps, nextSweepAt, warningMask, trashBin.snapshotContents()),
                    preserveBackupOnNextSave);
            preserveBackupOnNextSave = false;
            dirty = false;
            storageAvailable = true;
            if (wasUnavailable) {
                getLogger().info("Trash storage is writable again; normal operation resumed.");
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            storageAvailable = false;
            dirty = true;
            nextStorageRetryAt = System.currentTimeMillis() + STORAGE_RETRY_MILLIS;
            trashBin.closeAll();
            getLogger().log(Level.SEVERE,
                    "Cannot persist trash state; sweeping and GUI access are paused.", exception);
            return false;
        }
    }
}
