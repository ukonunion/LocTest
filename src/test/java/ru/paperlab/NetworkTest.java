package ru.paperlab;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class NetworkTest {
    public static void main(String[] args) throws Exception {
        var ports = NetworkConfig.parse("tcp 25575\nUDP 24454\nTCP 25575\nUDP 25575\n");
        check(ports.size() == 3, "Deduplication must preserve protocol");
        for (String bad : List.of("TCP 0", "UDP 65536", "TCP 12;exit", "TCP 1-10", "ANY 42", "TCP 12 13", "UDP -1")) {
            try { NetworkConfig.parse(bad); throw new AssertionError("Accepted " + bad); } catch (IllegalArgumentException expected) {}
        }
        Path home = Files.createTempDirectory("paperlab-network-test-");
        check(!NetworkConfig.load(home, 25575).lan(), "Legacy config must remain loopback");
        NetworkConfig config = new NetworkConfig(true, "TCP 25575\nUDP 24454"); config.save(home);
        check(config.equals(NetworkConfig.load(home, 25575)), "Persistence failed");
        String script = WindowsFirewall.script(home, NetworkConfig.parse(config.ports()), false);
        check(script.contains("-Profile Private,Domain -RemoteAddress LocalSubnet"), "Scope missing");
        check(!script.contains("-Profile Any"), "Overbroad profiles");
        Path strange = home.resolve("name ' & $()");
        check(!WindowsFirewall.script(strange, ports, false).contains("name ' & $()"), "Directory interpolated into shell");
        check(!WindowsFirewall.group(home).equals(WindowsFirewall.group(strange)), "Groups collide");
        String remove = WindowsFirewall.script(home, List.of(), true);
        check(!remove.contains("New-NetFirewallRule"), "Removal creates rules");
        var maximum = new ArrayList<NetworkConfig.Port>();
        for (int i = 0; i < 64; i++) maximum.add(new NetworkConfig.Port(NetworkConfig.Protocol.TCP, 65000 + i));
        check(WindowsFirewall.encoded(WindowsFirewall.elevation(WindowsFirewall.script(home, maximum, false))).length() < 30000,
            "Windows command line length exceeded");
        if (System.getProperty("os.name").startsWith("Windows")) {
            String group = WindowsFirewall.group(home);
            String mocks = """
                $script:created = @(); $script:removed = @()
                $script:rules = @(
                  [pscustomobject]@{Name='GROUP-TCP-25575'; Group='GROUP'},
                  [pscustomobject]@{Name='GROUP-TCP-12345'; Group='GROUP'},
                  [pscustomobject]@{Name='ForeignRule'; Group='OtherApplication'}
                )
                function Get-NetFirewallRule { param($PolicyStore,$Name,$ErrorAction)
                  if ($Name) { $script:rules | Where-Object { $_.Name -eq $Name } } else { $script:rules }
                }
                function Remove-NetFirewallRule { param([Parameter(ValueFromPipeline=$true)]$InputObject)
                  process { $script:removed += $InputObject.Name }
                }
                function New-NetFirewallRule {
                  param($PolicyStore,$Name,$DisplayName,$Group,$Direction,$Action,$Enabled,$Profile,$RemoteAddress,$Protocol,$LocalPort)
                  if ($Direction -ne 'Inbound' -or $Action -ne 'Allow' -or $RemoteAddress -ne 'LocalSubnet' -or
                      ($Profile -join ',') -ne 'Private,Domain' -or $PolicyStore -ne 'PersistentStore') { throw 'Wrong scope' }
                  $script:created += $Name
                }
                """.replace("GROUP", group);
            String verifyApply = "if ($script:created.Count -ne 2 -or $script:removed.Count -ne 2 -or $script:removed -contains 'ForeignRule') { throw 'Wrong changes' }; exit 0";
            runPowerShell(mocks + script.replace("exit 0", verifyApply));
            runPowerShell(mocks + remove.replace("exit 0", "if ($script:created.Count -ne 0 -or $script:removed.Count -ne 2 -or $script:removed -contains 'ForeignRule') { throw 'Wrong removal' }; exit 0"));
            String outer = WindowsFirewall.elevation(script);
            String parseOnly = "$t=$null; $e=$null; [System.Management.Automation.Language.Parser]::ParseInput([Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('"
                + WindowsFirewall.encoded(outer) + "')),[ref]$t,[ref]$e) | Out-Null; if ($e.Count) { $e | Out-String | Write-Output; exit 1 }; exit 0";
            runPowerShell(parseOnly);
        }
        System.out.println("PASS: ports validation, deduplication, persistence, scope, isolated rule groups, PowerShell mocked apply/remove and elevation syntax; no firewall changes");
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void runPowerShell(String code) throws Exception {
        Path ps = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Path file = Files.createTempFile("paperlab-mock-test-", ".ps1");
        Files.writeString(file, code);
        Process process = new ProcessBuilder(ps.toString(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("PowerShell test timeout"); }
        String output = new String(process.getInputStream().readAllBytes());
        Files.deleteIfExists(file);
        if (process.exitValue() != 0) throw new AssertionError("Mocked script failed: " + output);
    }
}

