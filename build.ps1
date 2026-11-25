param([string]$LoaderJar = "")
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
foreach ($name in @('classes','tests','agent')) {
    $target = Join-Path $PSScriptRoot "build\$name"
    if (Test-Path -LiteralPath $target) {
        $resolved = (Resolve-Path -LiteralPath $target).Path
        $expected = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "build\$name"))
        if ($resolved -ne $expected) { throw 'Unexpected build directory' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
New-Item -ItemType Directory -Force build,build\classes,build\deps,build\tests | Out-Null
if (-not $LoaderJar) {
    $LoaderJar = Join-Path $PSScriptRoot 'build\deps\fabric-loader-0.20.1.jar'
    if (-not (Test-Path -LiteralPath $LoaderJar)) {
        Invoke-WebRequest 'https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar' -OutFile $LoaderJar
    }
}
$sources = @(Get-ChildItem src\main\java -Filter *.java -Recurse | ForEach-Object FullName)
New-Item -ItemType Directory -Force build\agent | Out-Null
& javac --release 8 -encoding UTF-8 -d build\agent src\agent\java\ru\loctest\TelemetryAgent.java
if ($LASTEXITCODE -ne 0) { throw 'Agent compilation failed' }
"Premain-Class: ru.loctest.TelemetryAgent`n" | Set-Content build\agent.mf -Encoding ascii
& jar --create --file build\classes\telemetry-agent.jar --manifest build\agent.mf -C build\agent .
if ($LASTEXITCODE -ne 0) { throw 'Agent packaging failed' }
& javac --release 25 -encoding UTF-8 -cp $LoaderJar -d build\classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item src\main\resources\fabric.mod.json build\classes\fabric.mod.json -Force
& jar --create --file loctest-1.0+26.3.jar --main-class ru.paperlab.Panel -C build\classes .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
$tests = @(Get-ChildItem src\test\java -Filter *.java -Recurse | ForEach-Object FullName)
& javac --release 25 -encoding UTF-8 -cp build\classes -d build\tests $tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp 'build\classes;build\tests' ru.paperlab.RunnerTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
& java -cp 'build\classes;build\tests' ru.paperlab.NetworkTest
if ($LASTEXITCODE -ne 0) { throw 'Network tests failed' }
Write-Host 'Built and tested: loctest-1.0+26.3.jar'
& java '-Djava.awt.headless=true' -cp 'build\classes;build\tests' ru.paperlab.MonitorTest
if ($LASTEXITCODE -ne 0) { throw 'Monitor tests failed' }
} finally { Pop-Location }
