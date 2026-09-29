<#
.SYNOPSIS
    Builds a self-contained Windows app image: dist\SelfDrivingCarControlSystem\SelfDrivingCarControlSystem.exe
    with its own Java runtime, so the PC it runs on needs nothing installed.

.DESCRIPTION
    1. Builds the app with Maven (tests included unless -SkipTests) and copies its libraries.
    2. Works out which Java modules are needed (jdeps) and lets jpackage build a trimmed runtime.
    3. Copies the car model from assets\models\car if one is installed there (it is not in the
       repository; without it the built-in model is used).

    Needs JDK 21 or later with jpackage (included in the JDK). Data (the H2 database) is kept in
    %LOCALAPPDATA%\SelfDrive\data, not in the app folder.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\windows\package.ps1 -SkipTests
#>
param(
    [switch]$SkipTests,
    [string]$Dest = "dist"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
Set-Location $root

function Find-Tool([string]$name) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\$name.exe")) { return "$env:JAVA_HOME\bin\$name.exe" }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "$name not found. Install JDK 21 or later and set JAVA_HOME."
}
$jpackage = Find-Tool "jpackage"
$jdeps = Find-Tool "jdeps"

# Version from the pom (jpackage wants plain numbers: 0.4.0-SNAPSHOT -> 0.4.0).
[xml]$pom = Get-Content "$root\pom.xml"
$version = ($pom.project.version -replace '-.*$', '')
$name = "SelfDrivingCarControlSystem"
$lib = "$root\target\package\lib"

Write-Host "Building version $version..."
if (Test-Path "$root\target\package") { Remove-Item -Recurse -Force "$root\target\package" }
$mvn = @("-B", "-ntp", "package", "dependency:copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=target/package/lib")
if ($SkipTests) { $mvn += "-DskipTests" }
& "$root\mvnw.cmd" @mvn
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
$jar = Get-ChildItem "$root\target" -Filter "selfdrive-*.jar" | Where-Object { $_.Name -notmatch "sources|javadoc" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $jar.FullName "$lib\selfdrive.jar"

# Java modules the app and its libraries use, plus a few they load by reflection.
$classPath = (Get-ChildItem $lib -Filter *.jar | Where-Object { $_.Name -ne "selfdrive.jar" } | ForEach-Object { $_.FullName }) -join ";"
$found = & $jdeps --ignore-missing-deps --print-module-deps --multi-release 21 --class-path $classPath "$lib\selfdrive.jar" 2>$null
if ($LASTEXITCODE -ne 0 -or -not $found) { $found = "java.base,java.desktop,java.logging,java.sql" }
$modules = (($found.Trim() -split ",") + @("jdk.unsupported", "java.naming", "java.management", "jdk.crypto.ec", "jdk.localedata") |
    Sort-Object -Unique) -join ","
Write-Host "Runtime modules: $modules"

$out = "$root\$Dest"
if (Test-Path "$out\$name") { Remove-Item -Recurse -Force "$out\$name" }
$args = @(
    "--type", "app-image",
    "--name", $name,
    "--app-version", $version,
    "--vendor", "SelfDrive",
    "--description", "Self-driving car control system",
    "--input", $lib,
    "--main-jar", "selfdrive.jar",
    "--main-class", "com.selfdriving.Main",
    "--add-modules", $modules,
    "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
    "--java-options", "-Xmx3g",
    "--dest", $out
)
if (Get-ChildItem "$root\assets\models\car" -Include *.glb, *.gltf, *.zip -Recurse -ErrorAction SilentlyContinue) {
    Write-Host "Including the car model from assets\models\car"
    $args += @("--app-content", "$root\assets")
}
& $jpackage @args
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

$exe = "$out\$name\$name.exe"
$size = [math]::Round(((Get-ChildItem "$out\$name" -Recurse | Measure-Object Length -Sum).Sum / 1MB), 0)
Write-Host "Done: $exe ($size MB). Copy the whole $name folder to install it."
