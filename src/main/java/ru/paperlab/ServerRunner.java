package ru.paperlab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarFile;

public final class ServerRunner {
    public enum State { STOPPED, STARTING, RUNNING, READY, STOPPING, FAILED }
    private volatile State state = State.STOPPED;
    private Process process;
    private BufferedWriter input;
    private final Path home;
    private final Consumer<String> output;
    private final List<String> evidence = Collections.synchronizedList(new ArrayList<>());
    private volatile Instant started;
    private volatile Integer exitCode;
    private volatile boolean reachedReady;
    private String stopCommand = "stop";
    private String readyMarker = "Done (";
    private String launchDescription = "";

    public ServerRunner(Path home, Consumer<String> output) throws IOException {
        this.home = home.toAbsolutePath(); this.output = output;
        Files.createDirectories(home.resolve("plugins"));
        Files.createDirectories(home.resolve("mods"));
    }
    public State state() { return state; }
    // Keep the session busy until the log reader has drained and finalized state.
    public synchronized boolean running() { return process != null; }
    public synchronized void start(int memoryMb) throws IOException {
        LaunchConfig d = LaunchConfig.defaults(home);
        start(new LaunchConfig(home, d.mode(), d.executable(), d.jar(), "", "nogui", memoryMb,
            d.port(), true, "stop", "Done ("));
    }
    public synchronized void start(LaunchConfig config) throws IOException {
        if (running()) throw new IOException("Сервер уже запущен");
        if (!home.normalize().equals(config.directory().toAbsolutePath().normalize())) throw new IOException("Папка запуска не совпадает");
        List<String> command = config.command();
        Properties eula = new Properties();
        if (Files.exists(home.resolve("eula.txt"))) try (var r = Files.newBufferedReader(home.resolve("eula.txt"))) { eula.load(r); }
        if (!"true".equals(eula.getProperty("eula"))) throw new IOException("Прочитайте и примите Minecraft EULA в панели");
        // Proxies and nonstandard cores can opt out of Minecraft server.properties.
        if (config.localSettings()) {
        Properties p = new Properties();
        Path propertiesFile = home.resolve("server.properties");
        if (Files.exists(propertiesFile)) try (var r = Files.newBufferedReader(propertiesFile)) { p.load(r); }
        p.setProperty("server-ip", "127.0.0.1"); p.setProperty("server-port", "" + config.port());
        p.setProperty("enable-rcon", "false"); p.setProperty("enable-query", "false");
        p.putIfAbsent("online-mode", "true"); p.putIfAbsent("view-distance", "6");
        p.putIfAbsent("simulation-distance", "4"); p.putIfAbsent("motd", "LocTest");
        try (var w = Files.newBufferedWriter(propertiesFile)) { p.store(w, "LocTest local test server"); }
        }
        stopCommand = config.stopCommand(); readyMarker = config.readyMarker();
        launchDescription = "Режим: " + config.mode() + ", Java/программа: " + config.executable() + ", JAR: " + config.jar();
        launch(command);
    }
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
    synchronized void launch(List<String> command) throws IOException {
        if (running()) throw new IOException("Сервер уже запущен");
        evidence.clear(); exitCode = null; reachedReady = false; started = Instant.now(); state = State.STARTING;
        final Process child;
        try { child = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).start(); }
        catch (IOException e) { state = State.FAILED; throw e; }
        process = child;
        if (readyMarker.isBlank()) state = State.RUNNING;
        input = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("paperlab-output").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                 var log = Files.newBufferedWriter(home.resolve("session.log"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.write(line); log.newLine(); log.flush();
                    observe(line); output.accept(line);
                }
            } catch (IOException e) { output.accept("Ошибка чтения лога: " + e); }
            try {
                int code = child.waitFor();
                synchronized (this) {
                    exitCode = code;
                    state = code == 0 && (reachedReady || state == State.STOPPING || state == State.RUNNING) ? State.STOPPED : State.FAILED;
                    input = null;
                    process = null;
                }
                output.accept("Процесс сервера завершён, код " + code);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
    void observe(String line) {
        if (!readyMarker.isBlank() && line.contains(readyMarker)) {
            reachedReady = true;
            if (state == State.STARTING) state = State.READY;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || lower.contains("exception") || lower.contains("warn") || lower.contains("enabling ") || lower.contains("disabling ")) {
            synchronized (evidence) { if (evidence.size() < 5000) evidence.add(line); }
        }
    }
    public synchronized void command(String command) throws IOException {
        if (!running() || input == null) throw new IOException("Сервер не запущен");
        if (command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) throw new IOException("Введите одну команду без / в начале");
        input.write(command); input.newLine(); input.flush();
    }
    public synchronized void stop() throws IOException {
        if (!running()) return;
        command(stopCommand); state = State.STOPPING;
    }
    public void close() {
        Process child;
        synchronized (this) { child = process; try { stop(); } catch (IOException ignored) {} }
        if (child == null) return;
        try { if (!child.waitFor(30, TimeUnit.SECONDS)) { child.destroy(); if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly(); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); child.destroy(); }
    }
    public static String inspect(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var descriptor = jar.getJarEntry("paper-plugin.yml");
            if (descriptor == null) descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null) descriptor = jar.getJarEntry("bungee.yml");
            if (descriptor == null) descriptor = jar.getJarEntry("velocity-plugin.json");
            if (descriptor == null) throw new IOException(path.getFileName() + ": нет дескриптора Bukkit/Paper/Bungee/Velocity");
            try (var in = jar.getInputStream(descriptor)) {
                byte[] bytes = in.readNBytes(65537);
                if (bytes.length > 65536) throw new IOException("Слишком большой дескриптор: " + path.getFileName());
                return path.getFileName() + " [" + descriptor.getName() + "]\n" + new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }
    public String inventory() throws IOException {
        StringBuilder b = new StringBuilder("Дескрипторы плагинов (не проверка совместимости):\n");
        try (var files = Files.list(home.resolve("plugins"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                try { b.append(inspect(file)); } catch (IOException e) { b.append("ОШИБКА: ").append(e.getMessage()); }
                b.append("\n\n");
            }
        }
        return b.toString();
    }
    public Path report() throws IOException {
        StringBuilder b = new StringBuilder("LocTest — отчёт о запуске\n");
        b.append("Дата: ").append(Instant.now()).append("\nЗапуск: ").append(started)
            .append("\n").append(launchDescription).append("\nПапка: ").append(home)
            .append("\nСостояние: ").append(state).append("\nНайден маркер готовности: ").append(reachedReady)
            .append("\nКод выхода: ").append(exitCode).append("\n\n");
        b.append("Готовность сервера не доказывает исправность всех плагинов.\n")
            .append("Проверьте команды, события, права и зависимости в игре.\nВерсию ядра смотрите в логе запуска.\n\n")
            .append(inventory()).append("\nПредупреждения и сообщения плагинов (до 5000 строк):\n");
        synchronized (evidence) { evidence.forEach(s -> b.append(s).append('\n')); }
        Path report = home.resolve("report.txt"); Files.writeString(report, b); return report;
    }
}

