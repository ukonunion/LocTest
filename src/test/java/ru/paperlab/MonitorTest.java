package ru.paperlab;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.imageio.ImageIO;

public final class MonitorTest {
    public static void main(String[] args) throws Exception {
        if(SystemMonitor.cpuPercent(0,2_000_000_000L,1_000_000_000L,4)!=50)throw new AssertionError("CPU normalization");
        if(SystemMonitor.cpuPercent(-1,1,1,4)!=-1||SystemMonitor.cpuPercent(2,1,1,4)!=-1)throw new AssertionError("CPU reset");
        if(SystemMonitor.percent(1,0)!=-1||SystemMonitor.percent(50,100)!=50)throw new AssertionError("Memory ratio");
        GameMetrics metrics=new GameMetrics();metrics.observe("[INFO]: There are 3 of a max of 20 players online: A, B, C");
        metrics.observe("[INFO]: TPS from last 1m, 5m, 15m: §a*19.95, 20.0, 20.0");
        if(metrics.online()!=3||metrics.maximum()!=20||metrics.tps()!=19.95)throw new AssertionError("Game metrics");
        metrics.observe("TPS from last 5s, 1m, 5m, 15m: 18.0, 19.0, 20.0, 20.0");
        metrics.observe("[INFO]: <Player> There are 99 of a max of 100 players online");
        if(metrics.online()!=3)throw new AssertionError("Chat spoofed metrics");
        if(metrics.tps()!=19.0)throw new AssertionError("TPS period");metrics.reset();if(metrics.online()!=-1||metrics.tps()!=-1)throw new AssertionError("Reset stale metrics");
        if(ConsoleView.severity("[Server thread/ERROR]: broken")!=2||ConsoleView.severity("[WARN]: old API")!=1||ConsoleView.severity("[INFO]: ready")!=0)throw new AssertionError("Console levels");
        ServerRunner runner=new ServerRunner(Files.createTempDirectory("loctest-monitor-test"),s->{});
        SwingUtilities.invokeAndWait(()->{
            try(SystemMonitor m=new SystemMonitor(()->runner)){
                m.close();if(m.sample().pid()!=-1)throw new AssertionError("Stopped process");
                ConsoleView console=new ConsoleView();console.appendLine("[INFO]: LocTest 1.0 — демонстрация интерфейса");console.appendLine("[WARN]: Пример предупреждения");console.appendLine("[ERROR]: Пример ошибки");console.appendLine("[INFO]: Данные справа — тестовый сценарий, не реальный сервер");
                for(int i=0;i<60;i++)m.display(new SystemMonitor.Sample(1234,15+Math.sin(i/5.0)*10,500_000_000L+i*4_000_000L,2_147_483_648L,1_073_741_824L,42,1100,3600+i,3,20,19.98));
                JPanel root=new JPanel(new BorderLayout());JLabel title=new JLabel("  LocTest 1.0  •  Консоль сервера");title.setPreferredSize(new Dimension(1100,38));root.add(title,BorderLayout.NORTH);
                JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(console),m);split.setDividerLocation(710);root.add(split);root.setSize(1100,720);
                for(boolean dark:new boolean[]{true,false}){Theme.defaults(dark);Theme.apply(root,dark);layout(root);
                    BufferedImage image=new BufferedImage(1100,720,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();root.printAll(g);g.dispose();
                    try{Files.createDirectories(Path.of("build"));ImageIO.write(image,"png",Path.of("build/monitor-"+(dark?"dark":"light")+".png").toFile());}catch(Exception e){throw new RuntimeException(e);}
                }
            }
        });
        System.out.println("PASS: CPU/heap ratios, stopped state, players/TPS parsing/reset, console levels, dark/light rendering");
    }
    static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container cc)layout(cc);}
}
