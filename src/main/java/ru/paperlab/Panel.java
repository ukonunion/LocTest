package ru.paperlab;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.jar.JarFile;

public final class Panel {
    private final JFrame frame = new JFrame("LocTest • Fabric 26.3 → локальный Paper");
    private final JTextArea console = new JTextArea();
    private final JLabel status = new JLabel("Остановлен");
    private final Path home;
    private final ServerRunner runner;
    private final JSpinner memory = new JSpinner(new SpinnerNumberModel(2048, 1024, 32768, 512));
    private final JCheckBox eula = new JCheckBox("Я прочитал и принимаю Minecraft EULA");
    private final java.util.concurrent.ExecutorService jobs = java.util.concurrent.Executors.newSingleThreadExecutor();
    private FileChannel lockChannel;
    private FileLock lock;

    private Panel(Path home) throws IOException {
        this.home = home.toAbsolutePath();
        Files.createDirectories(this.home);
        lockChannel = FileChannel.open(this.home.resolve("panel.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) { lockChannel.close(); throw new IOException("Панель для этой папки уже открыта"); }
        runner = new ServerRunner(this.home, this::log);
        Runtime.getRuntime().addShutdownHook(new Thread(runner::close));
        build();
    }
    private void build() {
        console.setEditable(false); console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(buttons, "Скачать Paper ↗", () -> browse("https://papermc.io/downloads/paper"));
        button(buttons, "Выбрать Paper JAR", this::choosePaper);
        button(buttons, "Добавить плагины", this::addPlugins);
        button(buttons, "Папка сервера", () -> Desktop.getDesktop().open(home.toFile()));
        button(buttons, "EULA ↗", () -> browse("https://www.minecraft.net/eula"));
        JPanel control = new JPanel(new FlowLayout(FlowLayout.LEFT));
        control.add(new JLabel("RAM, МБ:")); control.add(memory);
        button(control, "Запустить", () -> {
            int mb = (Integer) memory.getValue(); boolean accepted = eula.isSelected();
            if (!accepted) throw new IOException("Прочитайте EULA по ссылке и отметьте согласие");
            background(() -> {
                Files.writeString(home.resolve("eula.txt"), "eula=true\n"); runner.start(mb);
            });
        });
        button(control, "Остановить", () -> background(runner::stop));
        button(control, "Дескрипторы", () -> background(() -> log(runner.inventory())));
        button(control, "Отчёт", () -> background(() -> log("Отчёт сохранён: " + runner.report())));
        control.add(status);
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(new JLabel("  Подключение: Сетевая игра → Прямое подключение → 127.0.0.1:25575"));
        top.add(buttons); top.add(eula); top.add(control);
        JTextField command = new JTextField();
        command.setToolTipText("Команда консоли без /, например: plugins, version, op ВашНик");
        command.addActionListener(e -> {
            String text = command.getText(); command.setText("");
            background(() -> runner.command(text));
        });
        JPanel bottom = new JPanel(new BorderLayout()); bottom.add(new JLabel(" Команда: "), BorderLayout.WEST); bottom.add(command);
        frame.add(top, BorderLayout.NORTH); frame.add(new JScrollPane(console)); frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(1100, 650); frame.setLocationByPlatform(true);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) {
            if (runner.running() && JOptionPane.showConfirmDialog(frame, "Остановить Paper и закрыть панель?", "LocTest", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            frame.setEnabled(false);
            jobs.submit(() -> {
                runner.close();
                try { lock.release(); lockChannel.close(); } catch (IOException ignored) {}
                System.exit(0);
            });
        }});
        new Timer(500, e -> status.setText(switch (runner.state()) {
            case STOPPED -> "Остановлен"; case STARTING -> "Запускается…";
            case READY -> "Готов: 127.0.0.1:25575"; case STOPPING -> "Останавливается…"; case FAILED -> "Ошибка — смотрите лог";
        })).start();
        log("Рабочая папка: " + home + "\nВыберите Paper именно для 26.3. Добавьте JAR плагинов и их зависимости.\nПанель работает отдельно от Minecraft; закрытие игры не останавливает Paper.\n");
        frame.setVisible(true);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private void button(JPanel p, String title, Action action) {
        JButton button = new JButton(title); p.add(button);
        button.addActionListener(e -> { try { action.run(); } catch (Exception ex) { error(ex); } });
    }
    private void background(Action action) { jobs.submit(() -> { try { action.run(); } catch (Exception ex) { error(ex); } }); }
    private void error(Exception e) { log("ОШИБКА: " + e.getMessage()); }
    private void log(String line) { SwingUtilities.invokeLater(() -> {
        console.append(line + "\n");
        if (console.getDocument().getLength() > 250000) console.replaceRange("", 0, 50000);
        console.setCaretPosition(console.getDocument().getLength());
    }); }
    private void stopped() throws IOException {
        if (runner.running() || runner.state() == ServerRunner.State.STARTING || runner.state() == ServerRunner.State.STOPPING)
            throw new IOException("Сначала остановите Paper и дождитесь завершения");
    }
    private JFileChooser chooser(boolean multi) {
        JFileChooser chooser = new JFileChooser(); chooser.setMultiSelectionEnabled(multi);
        chooser.setFileFilter(new FileNameExtensionFilter("Java архив (*.jar)", "jar")); return chooser;
    }
    private void choosePaper() {
        JFileChooser c = chooser(false);
        if (c.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path source = c.getSelectedFile().toPath();
        background(() -> {
            stopped();
            try (JarFile jar = new JarFile(source.toFile())) {
                if (jar.getManifest() == null || jar.getManifest().getMainAttributes().getValue("Main-Class") == null)
                    throw new IOException("Это не исполняемый серверный JAR");
            }
            copy(source, home.resolve("paper.jar"));
            log("Paper JAR установлен. Версию 26.3 проверьте в логе или командой version.");
        });
    }
    private void addPlugins() {
        JFileChooser c = chooser(true);
        if (c.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File[] files = c.getSelectedFiles();
        background(() -> {
            stopped();
            for (File file : files) {
                try {
                    log(ServerRunner.inspect(file.toPath()));
                    Path target = home.resolve("plugins").resolve(file.getName());
                    if (Files.exists(target)) throw new IOException("Файл уже есть: " + file.getName() + ". Удалите старый JAR в папке сервера после остановки.");
                    copy(file.toPath(), target); log("Добавлен: " + file.getName());
                } catch (IOException e) { error(e); }
            }
        });
    }
    private static void copy(Path source, Path target) throws IOException {
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) return;
        Path temp = Files.createTempFile(target.getParent(), ".paperlab-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void browse(String url) throws IOException { Desktop.getDesktop().browse(URI.create(url)); }
    public static void main(String[] args) {
        Path home = args.length == 0 ? Path.of("paperlab") : Path.of(args[0]);
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); new Panel(home); }
            catch (Exception e) { JOptionPane.showMessageDialog(null, e.toString(), "LocTest", JOptionPane.ERROR_MESSAGE); System.exit(1); }
        });
    }
}

