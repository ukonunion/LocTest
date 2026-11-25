package ru.paperlab;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.imageio.ImageIO;

public final class MonitorTest {
    public static void main(String[] args) throws Exception {
        if (SystemMonitor.cpuPercent(0, 2_000_000_000L, 1_000_000_000L, 4) != 50) throw new AssertionError("CPU normalization");
        if (SystemMonitor.cpuPercent(-1, 1, 1, 4) != -1 || SystemMonitor.cpuPercent(2, 1, 1, 4) != -1) throw new AssertionError("CPU baseline/reset");
        if (SystemMonitor.percent(1,0) != -1 || SystemMonitor.percent(50,100) != 50) throw new AssertionError("Memory ratio");
        SwingUtilities.invokeAndWait(() -> {
            try (SystemMonitor m = new SystemMonitor(() -> Path.of("."), () -> null)) {
                m.close();
                var s = m.sample();
                if (s.pid() != -1 || s.serverCpu() != -1) throw new AssertionError("Stopped server metrics");
                m.display(s); m.setSize(1100,540); layout(m);
                BufferedImage image = new BufferedImage(1100,540,BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics(); m.printAll(g);g.dispose();
                try { Files.createDirectories(Path.of("build")); ImageIO.write(image,"png",Path.of("build/monitor-preview.png").toFile()); } catch(Exception e){throw new RuntimeException(e);}
            }
        });
        System.out.println("PASS: CPU normalization/reset, unknown values, stopped process, live system metrics, dashboard render");
    }
    static void layout(Container c) { c.doLayout(); for (Component child:c.getComponents()) if(child instanceof Container cc) layout(cc); }
}
