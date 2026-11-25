package ru.loctest;

import java.lang.management.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Read-only JVM metrics. No bytecode transformation, sockets or remote JMX. Java 8+. */
public final class TelemetryAgent {
    public static void premain(String argument) {
        try {
            final Path output = Paths.get(new String(Base64.getUrlDecoder().decode(argument), StandardCharsets.UTF_8));
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "loctest-heap-monitor"); t.setDaemon(true); return t;
            });
            timer.scheduleWithFixedDelay(() -> {
                try {
                    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                    Properties p = new Properties();
                    p.setProperty("timestamp", "" + System.currentTimeMillis());
                    p.setProperty("pid", ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
                    p.setProperty("used", "" + heap.getUsed()); p.setProperty("max", "" + heap.getMax());
                    p.setProperty("committed", "" + heap.getCommitted());
                    p.setProperty("threads", "" + ManagementFactory.getThreadMXBean().getThreadCount());
                    long gc = 0; for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) gc += Math.max(0, bean.getCollectionTime());
                    p.setProperty("gcMillis", "" + gc);
                    Path temp = output.resolveSibling(output.getFileName() + ".tmp");
                    try (java.io.Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "LocTest JVM telemetry"); }
                    try { Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING); }
                } catch (Exception ignored) { /* Monitoring must never stop the server. */ }
            }, 0, 1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }
}
