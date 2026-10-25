<#
.SYNOPSIS
    Offline build for Factory Gauge Order Fix - no Gradle, no access to maven.neoforged.net.

.DESCRIPTION
    Compiles and packages the mod using the jars of an already-installed NeoForge 1.21.1 instance
    plus the JDK 21 bundled with the Minecraft runtime. Useful on machines where the NeoForge maven
    is unreachable (DNS poisoning / SNI blocking).

    Required local files (all present in any installed NeoForge 1.21.1 + Create instance):
      <MinecraftHome>\runtime\*\bin\javac.exe                JDK 21
      <MinecraftHome>\libraries\net\minecraft\client\1.21.1-*-srg.jar
      <MinecraftHome>\libraries\net\neoforged\neoforge\<ver>\neoforge-<ver>-{client,universal}.jar
      <MinecraftHome>\libraries\net\neoforged\{fancymodloader,bus,mergetool}\...
      <MinecraftHome>\libraries\{guava,slf4j,mixin,commons-lang3,fastutil}
      <instance>\mods\create-1.21.1-*.jar

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-offline.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-offline.ps1 `
        -MinecraftHome 'C:\Users\me\AppData\Roaming\.minecraft' -NeoForgeVersion '21.1.219'
#>
param(
    [string]$MinecraftHome = 'E:\Minecraft\.minecraft',
    [string]$CreateJar = '',
    [string]$NeoForgeVersion = '',
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'

# ---------- locate a JDK >= 21 (launchers keep it under .minecraft\runtime) ----------
if (-not $JavaHome) {
    $runtimes = @(
        (Join-Path $MinecraftHome 'runtime'),
        (Join-Path $env:APPDATA '.minecraft\runtime')
    ) | Where-Object { Test-Path -LiteralPath $_ }

    $best = $null
    $bestMajor = 0
    foreach ($rt in $runtimes) {
        foreach ($d in (Get-ChildItem -LiteralPath $rt -Directory -ErrorAction SilentlyContinue)) {
            $jc = Join-Path $d.FullName 'bin\javac.exe'
            if (-not (Test-Path -LiteralPath $jc)) { continue }
            $verText = ''
            try { $verText = (& $jc -version 2>&1 | Out-String) } catch { continue }
            if ($verText -match 'javac\s+(\d+)') {
                $major = [int]$Matches[1]
                if ($major -ge 21 -and $major -gt $bestMajor) { $bestMajor = $major; $best = $d.FullName }
            }
        }
        if ($best) { break }
    }
    if ($best) { $JavaHome = $best } else { $JavaHome = Join-Path $MinecraftHome 'runtime\java-runtime-delta' }
}

$JAVAC = Join-Path $JavaHome 'bin\javac.exe'
$JAR = Join-Path $JavaHome 'bin\jar.exe'
if (-not (Test-Path -LiteralPath $JAVAC)) { throw "javac not found: $JAVAC (pass -JavaHome)" }

$lib = Join-Path $MinecraftHome 'libraries'

function Find-Jar([string]$pattern) {
    $hit = Get-ChildItem -LiteralPath $lib -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $hit) { throw "dependency not found in ${lib}: $pattern" }
    return $hit.FullName
}

# ---------- NeoForge version ----------
if (-not $NeoForgeVersion) {
    $verDir = Get-ChildItem -LiteralPath (Join-Path $lib 'net\neoforged\neoforge') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $verDir) { throw "no NeoForge installation found under $lib\net\neoforged\neoforge" }
    $NeoForgeVersion = $verDir.Name
}

# ---------- Create jar (compile-time only) ----------
if (-not $CreateJar) {
    $hit = Get-ChildItem -LiteralPath (Join-Path $MinecraftHome 'versions') -Recurse -Depth 3 -Filter 'create-1.21.1-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $hit) { throw "Create 1.21.1 jar not found under $MinecraftHome\versions\*\mods (pass -CreateJar)" }
    $CreateJar = $hit.FullName
}

# ---------- Minecraft client (official mappings, produced by NeoForm) ----------
$mcClient = Get-ChildItem -LiteralPath (Join-Path $lib 'net\minecraft\client') -Recurse -Filter 'client-1.21.1-*-srg.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $mcClient) { throw "Minecraft 1.21.1 client jar not found under $lib\net\minecraft\client" }

$cp = @(
    (Join-Path $lib "net\neoforged\neoforge\$NeoForgeVersion\neoforge-$NeoForgeVersion-client.jar"),
    (Join-Path $lib "net\neoforged\neoforge\$NeoForgeVersion\neoforge-$NeoForgeVersion-universal.jar"),
    $mcClient.FullName,
    (Find-Jar 'loader-*.jar'),
    (Find-Jar 'bus-*.jar'),
    (Find-Jar 'mergetool-*-api.jar'),
    (Find-Jar 'mixin-*.jar'),
    (Find-Jar 'guava-*.jar'),
    (Find-Jar 'slf4j-api-*.jar'),
    (Find-Jar 'commons-lang3-*.jar'),
    (Find-Jar 'fastutil-*.jar'),
    $CreateJar
)
$missing = $cp | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { throw "missing classpath jars:`n$($missing -join "`n")" }

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$res = Join-Path $root 'src\main\resources'
$out = Join-Path $root 'build\classes'
$libsOut = Join-Path $root 'build\libs'
Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out, $libsOut | Out-Null

"JDK      : $JavaHome"
"NeoForge : $NeoForgeVersion"
"Create   : $CreateJar"
"MC       : $($mcClient.Name)"
""

$sources = Get-ChildItem -LiteralPath $src -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
"compiling $($sources.Count) files ..."
& $JAVAC -encoding UTF-8 --release 21 -proc:none -Xlint:-options -cp ($cp -join ';') -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
"javac OK"

# ---------- resources (expand ${...} placeholders) ----------
$props = @{ 'mod_id' = 'factorygaugeorder'; 'mod_version' = '1.0.0'; 'mod_name' = 'Factory Gauge Order Fix' }
Get-ChildItem -LiteralPath $res -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($res.Length + 1)
    $target = Join-Path $out $rel
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    if ($_.Extension -in @('.toml', '.json', '.mcmeta')) {
        $text = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
        foreach ($k in $props.Keys) { $text = $text.Replace('${' + $k + '}', $props[$k]) }
        [System.IO.File]::WriteAllText($target, $text, (New-Object System.Text.UTF8Encoding($false)))
    } else {
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }
}
"resources copied"

# ---------- package ----------
$jarPath = Join-Path $libsOut 'factorygaugeorder-1.0.0.jar'
Remove-Item -LiteralPath $jarPath -Force -ErrorAction SilentlyContinue
& $JAR --create --file $jarPath -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
"built: $jarPath ($([math]::Round((Get-Item -LiteralPath $jarPath).Length / 1KB, 1)) KB)"
