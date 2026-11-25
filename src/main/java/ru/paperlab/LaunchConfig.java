package ru.paperlab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Arguments are one-per-line, never parsed as shell text. Paths can contain spaces. */
public record LaunchConfig(Path directory, Mode mode, String executable, String jar,
                           String vmArgs, String arguments, int memoryMb, int port,
                           boolean localSettings, String stopCommand, String readyMarker) {
    public enum Mode { JAR, JAVA_ARGS, COMMAND }
    public static LaunchConfig defaults(Path home) {
        return new LaunchConfig(home, Mode.JAR, ServerRunner.javaExecutable(),
            Files.exists(home.resolve("paper.jar")) ? "paper.jar" : "server.jar",
            "", "nogui", 2048, 25575, true, "stop", "Done (");
    }
    public static List<String> lines(String value) {
        return value.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
    public List<String> command() throws IOException {
        if (executable.isBlank()) throw new IOException("Укажите Java или исполняемый файл");
        if (memoryMb < 512 || memoryMb > 131072) throw new IOException("RAM: от 512 до 131072 МБ");
        if (port < 1 || port > 65535) throw new IOException("Порт: от 1 до 65535");
        if (stopCommand.isBlank() || stopCommand.contains("\n") || stopCommand.contains("\r")) throw new IOException("Команда остановки должна занимать одну строку");
        List<String> result = new ArrayList<>(); result.add(executable);
        if (mode != Mode.COMMAND) {
            result.add("-Xms512M"); result.add("-Xmx" + memoryMb + "M"); result.addAll(lines(vmArgs));
        }
        if (mode == Mode.JAR) {
            if (jar.isBlank()) throw new IOException("Выберите серверный JAR");
            Path path = directory.resolve(jar).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) throw new IOException("Нет серверного JAR: " + path);
            try (JarFile archive = new JarFile(path.toFile())) {
                if (archive.getManifest() == null || archive.getManifest().getMainAttributes().getValue("Main-Class") == null)
                    throw new IOException("JAR не исполняемый. Для Forge/NeoForge выберите JAVA_ARGS после установки сервера.");
            }
            result.add("-jar"); result.add(path.toString());
        }
        result.addAll(lines(arguments));
        if (mode == Mode.JAVA_ARGS && lines(arguments).isEmpty()) throw new IOException("Укажите аргументы запуска Java, например @libraries/.../win_args.txt");
        return List.copyOf(result);
    }
    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("directory", directory.toAbsolutePath().toString()); p.setProperty("mode", mode.name());
        p.setProperty("executable", executable); p.setProperty("jar", jar); p.setProperty("vmArgs", vmArgs);
        p.setProperty("arguments", arguments); p.setProperty("memoryMb", "" + memoryMb); p.setProperty("port", "" + port);
        p.setProperty("localSettings", "" + localSettings); p.setProperty("stopCommand", stopCommand); p.setProperty("readyMarker", readyMarker);
        try (var w = Files.newBufferedWriter(file)) { p.store(w, "LocTest launcher"); }
    }
    public static LaunchConfig load(Path file, Path home) throws IOException {
        LaunchConfig d = defaults(home);
        if (!Files.exists(file)) return d;
        Properties p = new Properties(); try (var r = Files.newBufferedReader(file)) { p.load(r); }
        try {
            return new LaunchConfig(Path.of(p.getProperty("directory", home.toString())),
                Mode.valueOf(p.getProperty("mode", "JAR")), p.getProperty("executable", d.executable),
                p.getProperty("jar", d.jar), p.getProperty("vmArgs", ""), p.getProperty("arguments", "nogui"),
                Integer.parseInt(p.getProperty("memoryMb", "2048")), Integer.parseInt(p.getProperty("port", "25575")),
                Boolean.parseBoolean(p.getProperty("localSettings", "true")), p.getProperty("stopCommand", "stop"), p.getProperty("readyMarker", "Done ("));
        } catch (IllegalArgumentException e) { throw new IOException("Некорректный launcher.properties: " + e.getMessage(), e); }
    }
}
