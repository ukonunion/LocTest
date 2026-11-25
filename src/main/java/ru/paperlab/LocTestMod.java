package ru.paperlab;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;

/** A separate JVM avoids Minecraft's headless AWT configuration. */
public final class LocTestMod implements ClientModInitializer {
    @Override public void onInitializeClient() {
        try {
            Path jar = Path.of(LocTestMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path home = FabricLoader.getInstance().getGameDir().resolve("paperlab");
            java.nio.file.Files.createDirectories(home);
            new ProcessBuilder(javaExecutable(), "-Djava.awt.headless=false", "-jar", jar.toString(), home.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("panel.log").toFile())).start();
        } catch (Exception e) {
            System.err.println("[LocTest] Не удалось открыть панель: " + e);
        }
    }
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
}

