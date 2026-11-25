package ru.paperlab;

import java.util.regex.*;

/** Only explicit console responses count; values expire instead of implying zero. */
public final class GameMetrics {
    private static final Pattern PLAYERS = Pattern.compile("^There are (\\d+) (?:of a max of|out of maximum|/) (\\d+) players online", Pattern.CASE_INSENSITIVE);
    private static final Pattern TPS = Pattern.compile("^TPS from last ([^:]+):\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private volatile int online = -1, maximum = -1;
    private volatile double tps = -1;
    private volatile long playersAt, tpsAt;
    private volatile boolean supportsTps;
    public void reset() { online = maximum = -1; tps = -1; playersAt = tpsAt = 0; supportsTps = false; }
    public void observe(String raw) {
        String line = raw.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "").replaceAll("§.", "");
        line = line.replaceFirst("^(?:\\[[^\\]]*\\]\\s*)+:?\\s*", "");
        if (line.matches("(?i).*This server is running (Paper|Purpur|Spigot).*")) supportsTps = true;
        Matcher players = PLAYERS.matcher(line);
        if (players.find()) try { online = Integer.parseInt(players.group(1)); maximum = Integer.parseInt(players.group(2)); playersAt = System.currentTimeMillis(); } catch (NumberFormatException ignored) {}
        Matcher ticks = TPS.matcher(line);
        if (ticks.find()) {
            String[] periods = ticks.group(1).split(",\\s*"), values = ticks.group(2).split(",\\s*");
            for (int i = 0; i < Math.min(periods.length, values.length); i++) if (periods[i].strip().equalsIgnoreCase("1m")) {
                try { double value = Double.parseDouble(values[i].replace("*", "").strip()); if (Double.isFinite(value) && value >= 0) { tps = value; tpsAt = System.currentTimeMillis(); supportsTps = true; } } catch (NumberFormatException ignored) {}
            }
        }
    }
    public int online() { return System.currentTimeMillis() - playersAt < 15000 ? online : -1; }
    public int maximum() { return online() < 0 ? -1 : maximum; }
    public double tps() { return System.currentTimeMillis() - tpsAt < 15000 ? tps : -1; }
    public boolean supportsTps() { return supportsTps; }
}
