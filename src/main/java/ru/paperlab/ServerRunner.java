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
    public enum State { STOPPED, STARTING, READY, STOPPING, FAILED }
    private volatile State state = State.STOPPED;
    private Process process;
    private BufferedWriter input;
    private final Path home;
    private final Consumer<String> output;
    private final List<String> evidence = Collections.synchronizedList(new ArrayList<>());
    private volatile Instant started;
    private volatile Integer exitCode;
    private volatile boolean reachedReady;

    public ServerRunner(Path home, Consumer<String> output) throws IOException {
        this.home = home.toAbsolutePath(); this.output = output;
        Files.createDirectories(home.resolve("plugins"));
    }
    public State state() { return state; }
    // Keep the session busy until the log reader has drained and finalized state.
    public synchronized boolean running() { return process != null; }
    public synchronized void start(int memoryMb) throws IOException {
        if (running()) throw new IOException("Сервер уже запущен");
        if (memoryMb < 1024 || memoryMb > 32768) throw new IOException("RAM: от 1024 до 32768 МБ");
        Path jar = home.resolve("paper.jar");
        if (!Files.isRegularFile(jar)) throw new IOException("Сначала выберите JAR Paper 26.3");
        Properties eula = new Properties();
        if (Files.exists(home.resolve("eula.txt"))) try (var r = Files.newBufferedReader(home.resolve("eula.txt"))) { eula.load(r); }
        if (!"true".equals(eula.getProperty("eula"))) throw new IOException("Прочитайте и примите Minecraft EULA в панели");
        // Keep user settings, but enforce a local-only listener on every start.
        Properties p = new Properties();
        Path config = home.resolve("server.properties");
        if (Files.exists(config)) try (var r = Files.newBufferedReader(config)) { p.load(r); }
        p.setProperty("server-ip", "127.0.0.1"); p.setProperty("server-port", "25575");
        p.setProperty("enable-rcon", "false"); p.setProperty("enable-query", "false");
        p.putIfAbsent("online-mode", "true"); p.putIfAbsent("view-distance", "6");
        p.putIfAbsent("simulation-distance", "4"); p.putIfAbsent("motd", "LocTest 26.3");
        try (var w = Files.newBufferedWriter(config)) { p.store(w, "LocTest local test server"); }
        launch(List.of(javaExecutable(), "-Xms512M", "-Xmx" + memoryMb + "M", "-jar", jar.toString(), "--nogui"));
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
                    state = code == 0 && (reachedReady || state == State.STOPPING) ? State.STOPPED : State.FAILED;
                    input = null;
                    process = null;
                }
                output.accept("Процесс Paper завершён, код " + code);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
    void observe(String line) {
        if (line.contains("Done (") && line.contains("For help")) {
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
        command("stop"); state = State.STOPPING;
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
            if (descriptor == null) throw new IOException(path.getFileName() + ": нет plugin.yml или paper-plugin.yml");
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
            .append("\nСостояние: ").append(state).append("\nPaper готовился успешно: ").append(reachedReady)
            .append("\nКод выхода: ").append(exitCode).append("\n\n");
        b.append("Готовность сервера не доказывает исправность всех плагинов.\n")
            .append("Проверьте команды, события, права и зависимости в игре.\nВерсию Paper сверяйте по команде version.\n\n")
            .append(inventory()).append("\nПредупреждения и сообщения плагинов (до 5000 строк):\n");
        synchronized (evidence) { evidence.forEach(s -> b.append(s).append('\n')); }
        Path report = home.resolve("report.txt"); Files.writeString(report, b); return report;
    }
}

