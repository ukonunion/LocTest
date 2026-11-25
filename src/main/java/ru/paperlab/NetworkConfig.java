package ru.paperlab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Per-server settings; defaults preserve the previous loopback-only behavior. */
public record NetworkConfig(boolean lan, String ports) {
    public enum Protocol { TCP, UDP }
    public record Port(Protocol protocol, int number) {
        public Port {
            Objects.requireNonNull(protocol);
            if (number < 1 || number > 65535) throw new IllegalArgumentException("Порт должен быть от 1 до 65535");
        }
        @Override public String toString() { return protocol + " " + number; }
    }
    public static List<Port> parse(String text) {
        Set<Port> ports = new LinkedHashSet<>();
        int line = 0;
        for (String raw : text.lines().toList()) {
            line++; String value = raw.strip(); if (value.isEmpty()) continue;
            String[] parts = value.split("\\s+");
            if (parts.length != 2 || !parts[1].matches("[0-9]{1,5}"))
                throw new IllegalArgumentException("Строка " + line + ": введите TCP 25575 или UDP 24454");
            try { ports.add(new Port(Protocol.valueOf(parts[0].toUpperCase(Locale.ROOT)), Integer.parseInt(parts[1]))); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Строка " + line + ": нужен TCP/UDP и порт 1–65535"); }
        }
        if (ports.size() > 64) throw new IllegalArgumentException("Максимум 64 правила");
        return List.copyOf(ports);
    }
    public String normalized() { return String.join("\n", parse(ports).stream().map(Port::toString).toList()); }
    public void save(Path directory) throws IOException {
        Properties p = new Properties(); p.setProperty("lan", "" + lan); p.setProperty("ports", normalized());
        try (var w = Files.newBufferedWriter(directory.resolve("paperlab-network.properties"))) { p.store(w, "LocTest LAN ports"); }
    }
    public static NetworkConfig load(Path directory, int mainPort) throws IOException {
        Path file = directory.resolve("paperlab-network.properties");
        if (!Files.exists(file)) return new NetworkConfig(false, "TCP " + mainPort);
        Properties p = new Properties(); try (var r = Files.newBufferedReader(file)) { p.load(r); }
        NetworkConfig result = new NetworkConfig(Boolean.parseBoolean(p.getProperty("lan", "false")), p.getProperty("ports", "TCP " + mainPort));
        try { result.normalized(); } catch (IllegalArgumentException e) { throw new IOException("Некорректный paperlab-network.properties: " + e.getMessage(), e); }
        return result;
    }
}

