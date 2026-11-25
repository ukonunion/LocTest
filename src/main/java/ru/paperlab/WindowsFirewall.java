package ru.paperlab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Only manages rules belonging to the selected server directory. */
public final class WindowsFirewall {
    static String group(Path directory) {
        String path = directory.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        return "PaperLab-" + UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)); // preserve ownership of pre-rename rules
    }
    public static String script(Path directory, List<NetworkConfig.Port> ports, boolean remove) {
        String group = group(directory);
        StringBuilder s = new StringBuilder("$ErrorActionPreference='Stop'\ntry {\n$group='").append(group).append("'\n");
        if (remove) {
            s.append("Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Group -eq '").append(group)
                .append("' } | Remove-NetFirewallRule -ErrorAction Stop\n");
        } else {
            if (ports.isEmpty()) throw new IllegalArgumentException("Добавьте хотя бы один порт");
            if (ports.size() > 64) throw new IllegalArgumentException("Максимум 64 правила");
            s.append("$ports = @(\n");
            for (NetworkConfig.Port port : ports) s.append("'").append(port.protocol()).append(':').append(port.number()).append("'\n");
            s.append("""
                )
                $wanted = @()
                foreach ($entry in $ports) {
                  $protocol,$port = $entry.Split(':')
                  $name = $group + '-' + $protocol + '-' + $port
                  $wanted += $name
                  $existing = Get-NetFirewallRule -PolicyStore PersistentStore -Name $name -ErrorAction SilentlyContinue
                  if ($existing) {
                    if ($existing.Group -ne $group) { throw 'Rule name belongs to another group' }
                    $existing | Remove-NetFirewallRule -ErrorAction Stop
                  }
                  New-NetFirewallRule -PolicyStore PersistentStore -Name $name -DisplayName ('LocTest '+$protocol+' '+$port) -Group $group -Direction Inbound -Action Allow -Enabled True -Profile Private,Domain -RemoteAddress LocalSubnet -Protocol $protocol -LocalPort $port | Out-Null
                }
                """);
            s.append("Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Group -eq '").append(group)
                .append("' -and $_.Name -notin $wanted } | Remove-NetFirewallRule -ErrorAction Stop\n");
        }
        return s.append("exit 0\n} catch { exit 1 }\n").toString();
    }
    static String encoded(String script) { return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)); }
    static String elevation(String script) {
        // User text is never interpolated into shell code; the inner script contains validated ports and a UUID only.
        return "$ErrorActionPreference='Stop'; try { $p = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -WindowStyle Hidden -Verb RunAs -Wait -PassThru -ArgumentList '-NoProfile -NonInteractive -EncodedCommand "
            + encoded(script) + "'; exit $p.ExitCode } catch { exit 2 }";
    }
    public static void apply(Path directory, List<NetworkConfig.Port> ports, boolean remove) throws IOException, InterruptedException {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IOException("Эта функция доступна только в Windows");
        String windows = System.getenv("SystemRoot");
        if (windows == null) throw new IOException("Не найден каталог Windows");
        Path ps = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Process child = new ProcessBuilder(ps.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded(elevation(script(directory, ports, remove))))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // UAC is interactive. Let the user respond; no arbitrary timeout can report a still-running operation as failed.
        int exit = child.waitFor();
        if (exit == 2) throw new IOException("Запрос администратора отменён или повышение прав не удалось");
        if (exit != 0) throw new IOException("Windows не применила все правила (код " + exit + "). Возможно частичное изменение. Проверьте брандмауэр или удалите правила кнопкой в панели.");
    }
}
