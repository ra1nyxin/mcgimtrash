package mcgimtrash;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record PluginSettings(
        long sweepIntervalMillis,
        int sweepsPerCycle,
        String messagePrefix,
        List<Reminder> reminders) {

    private static final int MAX_INTERVAL_MINUTES = 7 * 24 * 60;
    private static final int MAX_SWEEPS_PER_CYCLE = 100_000;
    private static final int MAX_REMINDERS = 30;

    static PluginSettings from(ConfigurationSection config) {
        int intervalMinutes = requiredInt(config, "sweep-interval-minutes", 1,
                MAX_INTERVAL_MINUTES);
        int sweepsPerCycle = requiredInt(config, "sweeps-per-cycle", 1,
                MAX_SWEEPS_PER_CYCLE);

        String prefix = config.getString("message-prefix");
        if (prefix == null || prefix.isBlank() || prefix.length() > 32) {
            throw new IllegalArgumentException(
                    "message-prefix 必须是长度为 1 到 32 的非空文本");
        }

        List<?> configuredReminders = config.getList("reminder-seconds");
        if (configuredReminders == null || configuredReminders.isEmpty()
                || configuredReminders.size() > MAX_REMINDERS) {
            throw new IllegalArgumentException(
                    "reminder-seconds 必须包含 1 到 " + MAX_REMINDERS + " 个数字");
        }

        long intervalSeconds = intervalMinutes * 60L;
        Set<Long> uniqueLeadTimes = new HashSet<>();
        List<Reminder> reminders = new ArrayList<>();
        for (int index = 0; index < configuredReminders.size(); index++) {
            Object configuredValue = configuredReminders.get(index);
            if (!(configuredValue instanceof Number number)) {
                throw new IllegalArgumentException("reminder-seconds 只能包含整数");
            }
            long seconds = number.longValue();
            if (number.doubleValue() != seconds || seconds <= 0 || seconds >= intervalSeconds) {
                throw new IllegalArgumentException(
                        "每个 reminder-seconds 必须是大于 0 且小于清扫间隔的整数");
            }
            if (!uniqueLeadTimes.add(seconds)) {
                throw new IllegalArgumentException("reminder-seconds 不能包含重复值");
            }
            reminders.add(new Reminder(seconds * 1000L, 1 << index, formatDuration(seconds)));
        }
        reminders.sort(Comparator.comparingLong(Reminder::leadTimeMillis));

        return new PluginSettings(intervalMinutes * 60_000L, sweepsPerCycle,
                prefix.strip(), List.copyOf(reminders));
    }

    private static int requiredInt(
            ConfigurationSection config, String path, int minimum, int maximum) {
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " 必须是整数");
        }
        int value = config.getInt(path);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    path + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }

    private static String formatDuration(long seconds) {
        if (seconds >= 120 && seconds % 60 == 0) {
            return (seconds / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    record Reminder(long leadTimeMillis, int mask, String label) {
    }
}
