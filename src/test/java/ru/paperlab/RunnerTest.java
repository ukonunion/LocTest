package ru.paperlab;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;

public final class RunnerTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (args[0].equals("fail")) { System.out.println("ERROR Could not load plugin"); System.exit(2); }
            boolean custom = args[0].equals("custom");
            if (custom && !args[1].equals("space ; & $()")) throw new AssertionError("Arguments damaged");
            System.out.println(custom ? "CUSTOM ONLINE" : "[Server thread/INFO]: Done (0.3s)! For help, type \"help\"");
            try (var in = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) { String line = in.readLine(); if (line == null || line.equals(custom ? "halt" : "stop")) break; }
            }
            return;
        }
        Path home = Files.createTempDirectory("paperlab-test-");
        ServerRunner r = new ServerRunner(home, s -> {});
        List<String> cp = new ArrayList<>();
        for (String part : System.getProperty("java.class.path").split(File.pathSeparator)) cp.add(Path.of(part).toAbsolutePath().toString());
        var command = List.of(ServerRunner.javaExecutable(), "-cp", String.join(File.pathSeparator, cp), RunnerTest.class.getName(), "ok");
        try {
            try { r.start(2048); throw new AssertionError("Missing jar accepted"); } catch (IOException expected) {}
            r.launch(command); await(r, ServerRunner.State.READY);
            try { r.launch(command); throw new AssertionError("Double start accepted"); } catch (IOException expected) {}
            try { r.command("say a\nstop"); throw new AssertionError("Multiline accepted"); } catch (IOException expected) {}
            r.command("plugins"); r.stop(); await(r, ServerRunner.State.STOPPED);
            r.launch(List.of(ServerRunner.javaExecutable(), "-cp", String.join(File.pathSeparator, cp), RunnerTest.class.getName(), "fail"));
            await(r, ServerRunner.State.FAILED);
            if (!Files.readString(r.report()).contains("Could not load plugin")) throw new AssertionError("Missing error evidence");
            Path plugin = home.resolve("plugins/test.jar");
            try (var out = new JarOutputStream(Files.newOutputStream(plugin))) {
                out.putNextEntry(new JarEntry("paper-plugin.yml")); out.write("name: Test\nversion: 1\nmain: test.Test\n".getBytes()); out.closeEntry();
            }
            if (!r.inventory().contains("name: Test")) throw new AssertionError("Descriptor missing");
            Path invalid = home.resolve("invalid.jar");
            try (var out = new JarOutputStream(Files.newOutputStream(invalid))) {}
            try { ServerRunner.inspect(invalid); throw new AssertionError("Invalid plugin accepted"); } catch (IOException expected) {}
            testModes(home, r, cp);
            System.out.println("PASS: readiness, stop, restart, failure report, duplicate start, command validation, plugin descriptors");
        } finally { r.close(); }
    }
    private static void testModes(Path home, ServerRunner r, List<String> cp) throws Exception {
        Path jar = home.resolve("server with spaces.jar");
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", RunnerTest.class.getName());
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("ru/paperlab/RunnerTest.class"));
            try (var in = RunnerTest.class.getResourceAsStream("RunnerTest.class")) { in.transferTo(out); }
            out.closeEntry();
        }
        LaunchConfig jarConfig = new LaunchConfig(home, LaunchConfig.Mode.JAR, ServerRunner.javaExecutable(),
            jar.getFileName().toString(), "", "ok", 512, 25576, true, "stop", "Done (");
        try { r.start(jarConfig); throw new AssertionError("Missing EULA accepted"); } catch (IOException expected) {}
        Files.writeString(home.resolve("eula.txt"), "eula=true\n");
        r.start(jarConfig); await(r, ServerRunner.State.READY);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (r.telemetry().isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
        Properties heap = r.telemetry();
        if (Long.parseLong(heap.getProperty("max", "-1")) != 512L * 1024 * 1024 || Long.parseLong(heap.getProperty("used", "-1")) <= 0)
            throw new AssertionError("Real server JVM heap telemetry missing");
        r.stop(); await(r, ServerRunner.State.STOPPED);
        if (!r.telemetry().isEmpty()) throw new AssertionError("Stale heap after stop");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(home.resolve("server.properties"))) { properties.load(reader); }
        if (!"25576".equals(properties.getProperty("server-port")) || !"127.0.0.1".equals(properties.getProperty("server-ip"))) throw new AssertionError("Local settings not applied");
        new NetworkConfig(true, "TCP 25576\nUDP 24454").save(home);
        r.start(jarConfig); await(r, ServerRunner.State.READY); r.stop(); await(r, ServerRunner.State.STOPPED);
        try (var reader = Files.newBufferedReader(home.resolve("server.properties"))) { properties.load(reader); }
        if (!"".equals(properties.getProperty("server-ip"))) throw new AssertionError("LAN bind not applied");
        new NetworkConfig(false, "TCP 25576").save(home);
        r.start(jarConfig); await(r, ServerRunner.State.READY); r.stop(); await(r, ServerRunner.State.STOPPED);
        try (var reader = Files.newBufferedReader(home.resolve("server.properties"))) { properties.load(reader); }
        if (!"127.0.0.1".equals(properties.getProperty("server-ip"))) throw new AssertionError("Loopback bind not restored");
        Path argsFile = home.resolve("args with spaces.txt");
        // Forward slashes are portable Java argument-file paths, including Windows drive letters.
        Files.writeString(argsFile, "-cp\n\"" + String.join(File.pathSeparator, cp).replace('\\', '/') + "\"\n" + RunnerTest.class.getName());
        LaunchConfig javaConfig = new LaunchConfig(home, LaunchConfig.Mode.JAVA_ARGS, ServerRunner.javaExecutable(),
            "", "-Dpaperlab.test=true", "@" + argsFile + "\ncustom\nspace ; & $()", 512, 25575, false, "halt", "CUSTOM ONLINE");
        Path settings = home.resolve("launcher.properties"); javaConfig.save(settings);
        if (!javaConfig.equals(LaunchConfig.load(settings, home))) throw new AssertionError("Settings round trip failed");
        String original = Files.readString(home.resolve("server.properties"));
        r.start(javaConfig); await(r, ServerRunner.State.READY); r.stop(); await(r, ServerRunner.State.STOPPED);
        if (!original.equals(Files.readString(home.resolve("server.properties")))) throw new AssertionError("Unrequested settings change");
        LaunchConfig raw = new LaunchConfig(home, LaunchConfig.Mode.COMMAND, ServerRunner.javaExecutable(), "", "IGNORED", 
            "-cp\n" + String.join(File.pathSeparator, cp) + "\n" + RunnerTest.class.getName() + "\ncustom\nspace ; & $()",
            512, 25575, false, "halt", "");
        if (raw.command().contains("-Xmx512M") || raw.command().contains("IGNORED")) throw new AssertionError("COMMAND injected JVM args");
        r.start(raw); await(r, ServerRunner.State.RUNNING); r.stop(); await(r, ServerRunner.State.STOPPED);
        System.out.println("PASS: executable JAR, Java @argfile, raw command, paths with spaces, literal arguments, EULA, custom stop/readiness, config persistence, local settings opt-out");
    }
    private static void await(ServerRunner r, ServerRunner.State target) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (r.state() != target && System.nanoTime() < end) Thread.sleep(20);
        if (r.state() != target) throw new AssertionError("Expected " + target + ", got " + r.state());
    }
}
