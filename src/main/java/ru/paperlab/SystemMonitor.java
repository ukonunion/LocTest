package ru.paperlab;

import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Right-hand server-only Performance panel. Sampling never runs on the Swing thread. */
public final class SystemMonitor extends JPanel implements AutoCloseable {
    record Sample(long pid,double cpu,long used,long maximum,long committed,int threads,long gc,long uptime,int players,int slots,double tps) {}
    static double percent(long used,long total){return used<0||total<=0?-1:Math.clamp(100.0*used/total,0,100);}
    static double cpuPercent(long before,long after,long elapsed,int cores){return before<0||after<before||elapsed<=0||cores<1?-1:Math.clamp(100.0*(after-before)/elapsed/cores,0,100);}
    private final Supplier<ServerRunner> server;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"loctest-monitor");t.setDaemon(true);return t;});
    private long lastPid=-1,lastCpu=-1,lastTime; private int poll;
    private boolean dark=true;
    private final JLabel title=new JLabel("Производительность"), state=new JLabel("Сервер остановлен"), cpuValue=new JLabel("—"), memoryValue=new JLabel("—"), memoryDetail=new JLabel("Нет данных JVM"), players=new JLabel("—"), tps=new JLabel("—"), uptime=new JLabel("—"), threads=new JLabel("—"), gc=new JLabel("—");
    private final Chart cpuChart=new Chart(new Color(77,159,230)),memoryChart=new Chart(new Color(174,114,222));
    public SystemMonitor(Supplier<ServerRunner> server){
        this.server=server;setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));setPreferredSize(new Dimension(360,650));setMinimumSize(new Dimension(320,450));
        title.setFont(new Font("SansSerif",Font.BOLD,22));add(title);add(Box.createVerticalStrut(6));add(state);add(Box.createVerticalStrut(18));
        add(row("ЦП сервера",cpuValue));add(cpuChart);add(label("Доля общей мощности CPU • 60 секунд"));add(Box.createVerticalStrut(18));
        add(row("Память JVM",memoryValue));add(memoryChart);add(memoryDetail);add(Box.createVerticalStrut(18));
        add(row("Игроки онлайн",players));add(row("TPS · среднее за 1 мин",tps));add(row("Время работы",uptime));add(row("Потоки JVM",threads));add(row("Время GC · всего",gc));add(Box.createVerticalGlue());
        add(label("Пустой график — показатель недоступен."));theme(true);
        executor.scheduleWithFixedDelay(()->{try{Sample s=sample();SwingUtilities.invokeLater(()->display(s));}catch(Exception ignored){}},0,1,TimeUnit.SECONDS);
    }
    private JLabel label(String s){JLabel l=new JLabel(s);l.setFont(new Font("SansSerif",Font.PLAIN,11));l.setAlignmentX(LEFT_ALIGNMENT);return l;}
    private JPanel row(String name,JLabel value){JPanel p=new JPanel(new BorderLayout());p.add(new JLabel(name));p.add(value,BorderLayout.EAST);p.setMaximumSize(new Dimension(Integer.MAX_VALUE,32));p.setPreferredSize(new Dimension(320,32));p.setAlignmentX(LEFT_ALIGNMENT);value.setFont(new Font("SansSerif",Font.BOLD,16));return p;}
    Sample sample(){
        ServerRunner runner=server.get();ProcessHandle process=runner.processHandle();long pid=process==null||!process.isAlive()?-1:process.pid(),now=System.nanoTime();double load=-1;long seconds=-1;
        if(pid>0){var info=process.info();long cpu=info.totalCpuDuration().map(Duration::toNanos).orElse(-1L);if(pid==lastPid)load=cpuPercent(lastCpu,cpu,now-lastTime,Runtime.getRuntime().availableProcessors());lastCpu=cpu;seconds=info.startInstant().map(t->Math.max(0,Duration.between(t,Instant.now()).toSeconds())).orElse(-1L);}else lastCpu=-1;
        lastPid=pid;lastTime=now;
        if(++poll%5==0)try{runner.pollGameMetrics();}catch(Exception ignored){}
        Properties p=pid < 0 ? new Properties() : runner.telemetry();
        return new Sample(pid,load,number(p,"used"),number(p,"max"),number(p,"committed"),(int)number(p,"threads"),number(p,"gcMillis"),seconds,pid<0?-1:runner.metrics.online(),pid<0?-1:runner.metrics.maximum(),pid<0?-1:runner.metrics.tps());
    }
    static long number(Properties p,String k){try{return Long.parseLong(p.getProperty(k,"-1"));}catch(NumberFormatException e){return -1;}}
    private long displayedPid=-1;
    void display(Sample s){
        if(s.pid!=displayedPid){cpuChart.clear();memoryChart.clear();displayedPid=s.pid;}
        state.setText(s.pid<0?"Сервер остановлен":"PID "+s.pid+" • обновление 1 с");
        cpuValue.setText(s.cpu<0?"—":String.format(Locale.ROOT,"%.1f %%",s.cpu));
        double memory=percent(s.used,s.maximum);memoryValue.setText(memory<0?"—":String.format(Locale.ROOT,"%.1f %%",memory));
        memoryDetail.setText(memory<0?"Heap недоступен для этого запуска":String.format(Locale.ROOT,"%.0f / %.0f МиБ • выделено JVM %.0f МиБ",s.used/1048576.0,s.maximum/1048576.0,s.committed/1048576.0));
        players.setText(s.players<0?"Нет данных":s.players+" / "+s.slots);tps.setText(s.tps<0?"Нет данных":String.format(Locale.ROOT,"%.2f",s.tps));
        uptime.setText(s.uptime<0?"—":String.format("%02d:%02d:%02d",s.uptime/3600,s.uptime/60%60,s.uptime%60));threads.setText(s.threads<0?"—":""+s.threads);gc.setText(s.gc<0?"—":s.gc+" мс");
        cpuChart.append(s.cpu);memoryChart.append(memory);
    }
    public void theme(boolean dark){this.dark=dark;paintTheme(this);cpuChart.dark=memoryChart.dark=dark;repaint();}
    private void paintTheme(Component c){c.setBackground(dark?new Color(28,29,33):Color.WHITE);c.setForeground(dark?new Color(232,232,235):new Color(30,30,34));if(c instanceof Container p)for(Component child:p.getComponents())paintTheme(child);}
    public void close(){executor.shutdownNow();}
    private static final class Chart extends JPanel{
        final double[] values=new double[60];final Color accent;boolean dark;int size;
        Chart(Color c){accent=c;setPreferredSize(new Dimension(320,100));setMaximumSize(new Dimension(Integer.MAX_VALUE,110));setMinimumSize(new Dimension(200,75));setAlignmentX(LEFT_ALIGNMENT);}
        void clear(){Arrays.fill(values,-1);size=0;}
        void append(double v){System.arraycopy(values,1,values,0,59);values[59]=v;size=Math.min(60,size+1);repaint();}
        protected void paintComponent(Graphics raw){super.paintComponent(raw);Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int w=getWidth()-2,h=getHeight()-2;g.setColor(dark?new Color(48,53,62):new Color(219,231,241));for(int i=0;i<=10;i++)g.drawLine(1+i*w/10,1,1+i*w/10,h);for(int i=0;i<=4;i++)g.drawLine(1,1+i*h/4,w,1+i*h/4);g.setColor(accent);g.setStroke(new BasicStroke(1.7f));for(int i=61-size;i<60;i++){if(values[i-1]<0||values[i]<0)continue;g.drawLine(1+(i-1)*w/59,1+h-(int)(values[i-1]*h/100),1+i*w/59,1+h-(int)(values[i]*h/100));}g.drawRect(0,0,w+1,h+1);g.dispose();}
    }
}
