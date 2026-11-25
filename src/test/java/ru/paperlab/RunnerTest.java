package ru.paperlab;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;

public final class RunnerTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (args[0].equals("fail")) { System.out.println("ERROR Could not load plugin"); System.exit(2); }
            System.out.println("[Server thread/INFO]: Done (0.3s)! For help, type \"help\"");
            try (var in = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) { String line = in.readLine(); if (line == null || line.equals("stop")) break; }
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
            System.out.println("PASS: readiness, stop, restart, failure report, duplicate start, command validation, plugin descriptors");
        } finally { r.close(); }
    }
    private static void await(ServerRunner r, ServerRunner.State target) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (r.state() != target && System.nanoTime() < end) Thread.sleep(20);
        if (r.state() != target) throw new AssertionError("Expected " + target + ", got " + r.state());
    }
}

