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
    private final JFrame frame = new JFrame("LocTest 0.8 • запуск серверных ядер");
    private final JTextArea console = new JTextArea();
    private final JLabel status = new JLabel("Остановлен");
    private final Path home;
    private volatile ServerRunner runner;
    private volatile LaunchConfig config;
    private final JLabel connection = new JLabel();
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
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
        config = LaunchConfig.load(this.home.resolve("launcher.properties"), this.home);
        runner = new ServerRunner(config.directory(), this::log);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> runner.close()));
        build();
    }
    private void build() {
        console.setEditable(false); console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(buttons, "Ядро и запуск…", this::settings);
        button(buttons, "Добавить плагины", () -> addFiles(false));
        button(buttons, "Добавить моды", () -> addFiles(true));
        button(buttons, "Папка сервера", () -> Desktop.getDesktop().open(config.directory().toFile()));
        button(buttons, "EULA ↗", () -> browse("https://www.minecraft.net/eula"));
        JPanel control = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(control, "Запустить", () -> {
            boolean accepted = eula.isSelected();
            if (!accepted) throw new IOException("Прочитайте EULA по ссылке и отметьте согласие");
            background(() -> {
                stopped(); config.command();
                Files.writeString(config.directory().resolve("eula.txt"), "eula=true\n"); runner.start(config);
            });
        });
        button(control, "Остановить", () -> background(runner::stop));
        button(control, "Дескрипторы", () -> background(() -> log(runner.inventory())));
        button(control, "Отчёт", () -> background(() -> log("Отчёт сохранён: " + runner.report())));
        control.add(status);
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(connection); updateConnection();
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
            if (runner.running() && JOptionPane.showConfirmDialog(frame, "Остановить сервер и закрыть панель?", "LocTest", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            frame.setEnabled(false);
            jobs.submit(() -> {
                runner.close();
                try { lock.release(); lockChannel.close(); } catch (IOException ignored) {}
                System.exit(0);
            });
        }});
        new Timer(500, e -> status.setText(switch (runner.state()) {
            case STOPPED -> "Остановлен"; case STARTING -> "Запускается…";
            case RUNNING -> "Процесс работает (готовность не проверяется)";
            case READY -> "Получен маркер готовности"; case STOPPING -> "Останавливается…"; case FAILED -> "Ошибка — смотрите лог";
        })).start();
        log("Папка сервера: " + config.directory() + "\nВ «Ядро и запуск» выберите JAR или аргументы запуска установленного ядра.\nПлагины работают только на поддерживающих их ядрах. Для модов нужен подходящий загрузчик.\nЗакрытие игры не останавливает сервер — закройте эту панель.\n");
        frame.setVisible(true);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private void button(JPanel p, String title, Action action) {
        JButton button = new JButton(title); p.add(button);
        button.addActionListener(e -> { try { action.run(); } catch (Exception ex) { error(ex); } });
    }
    private void background(Action action) {
        if (!busy.compareAndSet(false, true)) { log("Дождитесь завершения текущей операции"); return; }
        jobs.submit(() -> { try { action.run(); } catch (Exception ex) { error(ex); } finally { busy.set(false); } });
    }
    private void error(Exception e) { log("ОШИБКА: " + e.getMessage()); }
    private void log(String line) { SwingUtilities.invokeLater(() -> {
        console.append(line + "\n");
        if (console.getDocument().getLength() > 250000) console.replaceRange("", 0, 50000);
        console.setCaretPosition(console.getDocument().getLength());
    }); }
    private void stopped() throws IOException {
        if (runner.running() || runner.state() == ServerRunner.State.STARTING || runner.state() == ServerRunner.State.STOPPING)
            throw new IOException("Сначала остановите сервер и дождитесь завершения");
    }
    private JFileChooser chooser(boolean multi) {
        JFileChooser chooser = new JFileChooser(); chooser.setMultiSelectionEnabled(multi);
        chooser.setFileFilter(new FileNameExtensionFilter("Java архив (*.jar)", "jar")); return chooser;
    }
    private void updateConnection() {
        connection.setText(config.localSettings() ? "  Локальный сервер: 127.0.0.1:" + config.port() + " • " + config.mode()
            : "  " + config.mode() + " • адрес и порт задаются конфигурацией ядра");
    }
    private void settings() throws Exception {
        if (busy.get()) throw new IOException("Дождитесь завершения текущей операции");
        stopped();
        JComboBox<LaunchConfig.Mode> mode = new JComboBox<>(LaunchConfig.Mode.values()); mode.setSelectedItem(config.mode());
        JTextField directory = new JTextField(config.directory().toString());
        JTextField java = new JTextField(config.executable());
        JTextField jar = new JTextField(config.jar());
        JTextArea vm = new JTextArea(config.vmArgs(), 3, 55);
        JTextArea args = new JTextArea(config.arguments(), 5, 55);
        JTextField stop = new JTextField(config.stopCommand()), ready = new JTextField(config.readyMarker());
        JSpinner ram = new JSpinner(new SpinnerNumberModel(config.memoryMb(), 512, 131072, 512));
        JSpinner port = new JSpinner(new SpinnerNumberModel(config.port(), 1, 65535, 1));
        JCheckBox local = new JCheckBox("Настроить server.properties: 127.0.0.1, порт ниже, RCON/query выключены", config.localSettings());
        JPanel fields = new JPanel(new GridBagLayout());
        int row = 0;
        field(fields, row++, "Режим", mode);
        field(fields, row++, "Папка сервера", pathPicker(directory, true, false));
        field(fields, row++, "Java / программа", pathPicker(java, false, false));
        field(fields, row++, "Серверный JAR", pathPicker(jar, false, true));
        field(fields, row++, "RAM, МБ (Java)", ram);
        field(fields, row++, "JVM: один аргумент на строку", new JScrollPane(vm));
        field(fields, row++, "Запуск: один аргумент на строку", new JScrollPane(args));
        field(fields, row++, "Локальные настройки", local);
        field(fields, row++, "Порт (если включено выше)", port);
        field(fields, row++, "Команда остановки", stop);
        field(fields, row++, "Маркер готовности (можно пусто)", ready);
        field(fields, row++, "Подсказка", new JLabel("<html>JAR: Paper, Purpur, Vanilla, Fabric launcher и другие исполняемые JAR.<br>JAVA_ARGS: установленный Forge/NeoForge — аргументы из run.bat/run.sh.<br>COMMAND: программа + аргументы; Java/RAM/JVM автоматически не добавляются.<br>Пути с пробелами вводите без кавычек. Аргументы — по одному на строку.<br>Для другого ядра выбирайте отдельную папку; библиотеки не копируются.<br>У прокси отключите локальные настройки и задайте его команду остановки.</html>"));
        Runnable enable = () -> {
            boolean j = mode.getSelectedItem() != LaunchConfig.Mode.COMMAND;
            ram.setEnabled(j); vm.setEnabled(j); jar.setEnabled(mode.getSelectedItem() == LaunchConfig.Mode.JAR);
        };
        mode.addActionListener(e -> enable.run()); enable.run();
        while (JOptionPane.showConfirmDialog(frame, fields, "Ядро и запуск", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                ram.commitEdit(); port.commitEdit();
                LaunchConfig next = new LaunchConfig(Path.of(directory.getText()).toAbsolutePath().normalize(),
                    (LaunchConfig.Mode) mode.getSelectedItem(), java.getText().strip(), jar.getText().strip(), vm.getText(), args.getText(),
                    (Integer) ram.getValue(), (Integer) port.getValue(), local.isSelected(), stop.getText().strip(), ready.getText());
                next.command();
                ServerRunner nextRunner = new ServerRunner(next.directory(), this::log);
                next.save(home.resolve("launcher.properties")); config = next; runner = nextRunner;
                updateConnection(); log("Настройки сохранены. Папка сервера: " + config.directory()); return;
            } catch (Exception e) { JOptionPane.showMessageDialog(frame, e.getMessage(), "Настройки", JOptionPane.ERROR_MESSAGE); }
        }
    }
    private JPanel pathPicker(JTextField value, boolean directory, boolean jar) {
        JPanel p = new JPanel(new BorderLayout(6, 0)); p.add(value); JButton b = new JButton("…"); p.add(b, BorderLayout.EAST);
        b.addActionListener(e -> {
            JFileChooser c = jar ? chooser(false) : new JFileChooser();
            if (directory) c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) value.setText(c.getSelectedFile().getAbsolutePath());
        }); return p;
    }
    private static void field(JPanel panel, int row, String name, Component value) {
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = row;
        c.insets = new Insets(4, 6, 4, 6); c.anchor = GridBagConstraints.WEST; panel.add(new JLabel(name), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(value, c);
    }
    private void addFiles(boolean mods) {
        JFileChooser c = chooser(true);
        if (c.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File[] files = c.getSelectedFiles();
        background(() -> {
            stopped();
            for (File file : files) {
                try {
                    if (mods) { try (JarFile archive = new JarFile(file)) { archive.size(); } }
                    else log(ServerRunner.inspect(file.toPath()));
                    Path target = config.directory().resolve(mods ? "mods" : "plugins").resolve(file.getName());
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

