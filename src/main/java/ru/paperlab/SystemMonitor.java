package ru.paperlab;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.swing.*;
import java.awt.*;

/** Samples outside the Swing event thread; unavailable measurements remain unavailable. */
public final class SystemMonitor extends JPanel implements AutoCloseable {
    record Sample(double cpu, long totalRam, long freeRam, long totalSwap, long freeSwap,
                  long totalDisk, long freeDisk, long pid, double serverCpu, long uptime) {}
    static double percent(long used, long total) { return total <= 0 ? -1 : Math.clamp(100.0 * used / total, 0, 100); }
    static double cpuPercent(long previous, long current, long elapsed, int cores) {
        return previous < 0 || current < previous || elapsed <= 0 || cores < 1 ? -1 : Math.clamp(100.0 * (current - previous) / elapsed / cores, 0, 100);
    }
    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean b ? b : null;
    private final Supplier<Path> directory;
    private final Supplier<ProcessHandle> server;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "loctest-monitor"); t.setDaemon(true); return t; });
    private long previousPid = -1, previousCpu = -1, previousTime;
    private final Meter cpu = new Meter("CPU · вся система"), ram = new Meter("ОЗУ · физическая память"), disk = new Meter("Диск · раздел сервера"), swap = new Meter("Виртуальная память · swap"), process = new Meter("CPU · процесс сервера"), uptime = new Meter("Сервер · время работы");
    private final History graph = new History();
    private final JLabel detail = new JLabel("Первое измерение…");
    public SystemMonitor(Supplier<Path> directory, Supplier<ProcessHandle> server) {
        this.directory = directory; this.server = server;
        setLayout(new BorderLayout(14, 14)); setBackground(new Color(18, 24, 35));
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        JPanel heading = new JPanel(new BorderLayout()); heading.setOpaque(false);
        JLabel title = new JLabel("Состояние системы"); title.setFont(new Font("SansSerif", Font.BOLD, 25)); title.setForeground(Color.WHITE);
        heading.add(title); JLabel cadence = new JLabel("Обновление каждую секунду"); cadence.setForeground(new Color(151, 166, 186)); heading.add(cadence, BorderLayout.EAST); add(heading, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(12, 14)); body.setOpaque(false);
        JPanel cards = new JPanel(new GridLayout(2, 3, 12, 12)); cards.setOpaque(false);
        for (Meter m : new Meter[]{cpu, ram, disk, swap, process, uptime}) cards.add(m);
        body.add(cards, BorderLayout.NORTH); body.add(graph); add(body);
        detail.setForeground(new Color(151, 166, 186)); add(detail, BorderLayout.SOUTH);
        sampler.scheduleWithFixedDelay(() -> {
            try { Sample s = sample(); SwingUtilities.invokeLater(() -> display(s)); }
            catch (Exception e) { SwingUtilities.invokeLater(() -> detail.setText("Измерение недоступно: " + e.getClass().getSimpleName())); }
        }, 0, 1, TimeUnit.SECONDS);
    }
    Sample sample() {
        long totalDisk = -1, freeDisk = -1;
        try { FileStore store = Files.getFileStore(directory.get()); totalDisk = store.getTotalSpace(); freeDisk = store.getUsableSpace(); } catch (Exception ignored) {}
        ProcessHandle p = server.get(); long pid = p == null || !p.isAlive() ? -1 : p.pid();
        long now = System.nanoTime(), elapsed = now - previousTime; double load = -1; long seconds = -1;
        if (pid > 0) {
            var info = p.info(); long nanos = info.totalCpuDuration().map(Duration::toNanos).orElse(-1L);
            if (pid == previousPid) load = cpuPercent(previousCpu, nanos, elapsed, Runtime.getRuntime().availableProcessors());
            seconds = info.startInstant().map(t -> Math.max(0, Duration.between(t, Instant.now()).toSeconds())).orElse(-1L);
            previousCpu = nanos;
        } else previousCpu = -1;
        previousPid = pid; previousTime = now;
        return new Sample(os == null ? -1 : os.getCpuLoad() * 100,
            os == null ? -1 : os.getTotalMemorySize(), os == null ? -1 : os.getFreeMemorySize(),
            os == null ? -1 : os.getTotalSwapSpaceSize(), os == null ? -1 : os.getFreeSwapSpaceSize(),
            totalDisk, freeDisk, pid, load, seconds);
    }
    void display(Sample s) {
        cpu.value(s.cpu, "Логических процессоров: " + Runtime.getRuntime().availableProcessors());
        memory(ram, s.totalRam, s.freeRam); memory(disk, s.totalDisk, s.freeDisk); memory(swap, s.totalSwap, s.freeSwap);
        process.value(s.serverCpu, s.pid > 0 ? "PID " + s.pid + " · доля всех ядер CPU" : "Сервер остановлен");
        uptime.value.setText(s.uptime < 0 ? "—" : String.format("%02d:%02d:%02d", s.uptime / 3600, s.uptime / 60 % 60, s.uptime % 60));
        uptime.note.setText(s.pid > 0 ? "Запущенный процесс сервера" : "Запустите сервер из панели");
        graph.append(s.cpu, percent(s.totalRam - s.freeRam, s.totalRam));
        detail.setText("CPU и ОЗУ — весь компьютер. CPU сервера — только запущенный процесс. График: последние 60 измерений.");
    }
    private static void memory(Meter meter, long total, long free) {
        meter.value(percent(total - free, total), total < 0 || free < 0 ? "Данные недоступны" : String.format(Locale.ROOT, "%.1f / %.1f ГиБ занято", (total-free)/1073741824.0, total/1073741824.0));
    }
    @Override public void close() { sampler.shutdownNow(); }
    private static final class Meter extends JPanel {
        final JLabel value = new JLabel("—"), note = new JLabel("Ожидание данных"); final JProgressBar bar = new JProgressBar(0,100);
        Meter(String name) {
            setBackground(new Color(29, 38, 52)); setLayout(new BorderLayout(4,4)); setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
            JLabel label = new JLabel(name); label.setForeground(new Color(170,185,205)); add(label,BorderLayout.NORTH);
            value.setFont(new Font("SansSerif",Font.BOLD,29)); value.setForeground(Color.WHITE); add(value);
            JPanel bottom = new JPanel(new BorderLayout(2,6)); bottom.setOpaque(false); note.setForeground(new Color(170,185,205)); note.setFont(new Font("SansSerif",Font.PLAIN,11));
            bottom.add(note,BorderLayout.NORTH); bar.setPreferredSize(new Dimension(100,5)); bar.setForeground(new Color(70,205,180)); bottom.add(bar); add(bottom,BorderLayout.SOUTH);
        }
        void value(double percent, String caption) { value.setText(percent < 0 || !Double.isFinite(percent) ? "—" : String.format(Locale.ROOT,"%.1f %%",percent)); bar.setValue(percent < 0 ? 0 : (int)percent); note.setText(caption); }
    }
    private static final class History extends JPanel {
        final double[] cpu = new double[60], ram = new double[60]; int size;
        History() { setBackground(new Color(22,30,43)); setPreferredSize(new Dimension(600,180)); }
        void append(double c, double r) { System.arraycopy(cpu,1,cpu,0,59); System.arraycopy(ram,1,ram,0,59); cpu[59]=c; ram[59]=r; size=Math.min(60,size+1); repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int left=44, top=35, width=Math.max(1,getWidth()-64), height=Math.max(1,getHeight()-58);
            g.setColor(new Color(170,185,205)); g.drawString("Загрузка, %     CPU — зелёный    ОЗУ — синий",left,20);
            for(int i=0;i<=4;i++){int y=top+height*i/4;g.setColor(new Color(42,53,69));g.drawLine(left,y,left+width,y);g.setColor(new Color(130,145,165));g.drawString(""+(100-i*25),8,y+4);}
            draw(g,cpu,new Color(70,205,180),left,top,width,height); draw(g,ram,new Color(100,158,255),left,top,width,height);g.dispose();
        }
        void draw(Graphics2D g,double[] values,Color color,int x,int y,int w,int h){g.setColor(color);g.setStroke(new BasicStroke(2));for(int i=61-size;i<60;i++){if(values[i-1]<0||values[i]<0)continue;g.drawLine(x+(i-1)*w/59,y+h-(int)(values[i-1]*h/100),x+i*w/59,y+h-(int)(values[i]*h/100));}}
    }
}
