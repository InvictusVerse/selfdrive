<#
.SYNOPSIS
    Checks that this PC can build and run the Self-Driving Car Control System.

.DESCRIPTION
    Read-only check: it inspects Java, Git, network access and project files, then prints
    what is missing and how to fix it. Nothing on the PC is changed.

    Pass -Build to also run a full clean build and tests with the Maven wrapper.

.EXAMPLE
    scripts\check-environment.cmd
    scripts\check-environment.cmd -Build
#>
[CmdletBinding()]
param(
    [switch]$Build
)

# 'Continue' on purpose: Windows PowerShell 5.1 turns native stderr (java -version) into errors.
$ErrorActionPreference = 'Continue'
$RequiredJava = 21
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:Failures = 0
$script:Warnings = 0

function Write-Ok([string]$Message) {
    Write-Host "  [ OK ]  $Message" -ForegroundColor Green
}

function Write-Warning2([string]$Message, [string]$Fix) {
    Write-Host "  [WARN]  $Message" -ForegroundColor Yellow
    if ($Fix) { Write-Host "          Fix: $Fix" -ForegroundColor DarkGray }
    $script:Warnings++
}

function Write-Fail([string]$Message, [string]$Fix) {
    Write-Host "  [FAIL]  $Message" -ForegroundColor Red
    if ($Fix) { Write-Host "          Fix: $Fix" -ForegroundColor DarkGray }
    $script:Failures++
}

function Get-JavaMajorVersion([string]$JavaExe) {
    $output = & $JavaExe -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" }
    foreach ($line in $output) {
        if ($line -match 'java\.specification\.version\s*=\s*(\d+)') { return [int]$Matches[1] }
    }
    return $null
}

function Get-JavaVendor([string]$JavaExe) {
    $output = & $JavaExe -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" }
    foreach ($line in $output) {
        if ($line -match 'java\.vendor\s*=\s*(.+)$') { return $Matches[1].Trim() }
    }
    return 'unknown vendor'
}

Write-Host ''
Write-Host '  Self-Driving Car Control System - environment check' -ForegroundColor Cyan
Write-Host '  ----------------------------------------------------' -ForegroundColor Cyan
Write-Host ''

# 1. Java -------------------------------------------------------------------------------
Write-Host '  Java (required)' -ForegroundColor White
$javaFix = "Install Temurin JDK $RequiredJava from https://adoptium.net and tick 'Set JAVA_HOME' in the installer."
$javaExe = $null

if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $candidate) {
        $javaExe = $candidate
        Write-Ok "JAVA_HOME = $env:JAVA_HOME"
    } else {
        Write-Fail "JAVA_HOME points to '$env:JAVA_HOME' but bin\java.exe is not there." `
            'Point JAVA_HOME at a JDK folder (System Properties > Environment Variables), or remove it.'
    }
} else {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        $javaExe = $cmd.Source
        Write-Warning2 "JAVA_HOME is not set; using java from PATH ($javaExe)." `
            'Optional: set JAVA_HOME to your JDK folder so every tool uses the same Java.'
    } else {
        Write-Fail 'No Java found (JAVA_HOME not set and java is not on PATH).' $javaFix
    }
}

if ($javaExe) {
    $major = Get-JavaMajorVersion $javaExe
    $vendor = Get-JavaVendor $javaExe
    if ($null -eq $major) {
        Write-Fail "Could not read the Java version from $javaExe." $javaFix
    } elseif ($major -lt $RequiredJava) {
        Write-Fail "Java $major found ($vendor); Java $RequiredJava or newer is required." $javaFix
    } else {
        Write-Ok "Java $major ($vendor) - this is the Java the Maven wrapper will use."
        if ($major -ne $RequiredJava) {
            Write-Host "          Note: builds still target Java $RequiredJava, so Java $major is fine." -ForegroundColor DarkGray
        }
    }
}

# 2. Git --------------------------------------------------------------------------------
Write-Host ''
Write-Host '  Git (needed to clone / push)' -ForegroundColor White
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    Write-Ok ((& git --version) -join ' ')
} else {
    Write-Warning2 'Git is not installed.' 'Install from https://git-scm.com/download/win (not needed if you got the code as a ZIP).'
}

$gh = Get-Command gh -ErrorAction SilentlyContinue
if ($gh) {
    Write-Ok ("GitHub CLI " + ((& gh --version | Select-Object -First 1) -replace '^gh version\s*', ''))
} else {
    Write-Host '  [INFO]  GitHub CLI not installed (optional: https://cli.github.com).' -ForegroundColor DarkGray
}

# 3. Project files ----------------------------------------------------------------------
Write-Host ''
Write-Host '  Project files' -ForegroundColor White
foreach ($file in @('pom.xml', 'mvnw.cmd', '.mvn\wrapper\maven-wrapper.properties')) {
    if (Test-Path (Join-Path $ProjectRoot $file)) {
        Write-Ok $file
    } else {
        Write-Fail "$file is missing." 'Re-clone the repository or re-extract the full ZIP.'
    }
}

# 4. Network (first build downloads Maven + libraries) ---------------------------------
Write-Host ''
Write-Host '  Network' -ForegroundColor White
$m2Wrapper = Join-Path $env:USERPROFILE '.m2\wrapper'
try {
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -UseBasicParsing -Method Head -TimeoutSec 10 'https://repo.maven.apache.org/maven2/' | Out-Null
    Write-Ok 'Maven Central is reachable (needed for the first build only).'
} catch {
    if (Test-Path $m2Wrapper) {
        Write-Warning2 'Maven Central is not reachable, but a Maven download already exists in ~\.m2.' `
            'Offline builds work only if all libraries were downloaded before.'
    } else {
        Write-Fail 'Maven Central is not reachable and nothing is cached yet.' `
            'Connect to the internet for the first build (restricted networks or proxies can block it; try a phone hotspot).'
    }
}

# Summary -------------------------------------------------------------------------------
Write-Host ''
if ($script:Failures -gt 0) {
    Write-Host "  RESULT: $($script:Failures) problem(s) to fix, $($script:Warnings) warning(s)." -ForegroundColor Red
    Write-Host '  See SETUP.md for step-by-step help.' -ForegroundColor DarkGray
    Write-Host ''
    exit 1
}

Write-Host "  RESULT: ready to build ($($script:Warnings) warning(s))." -ForegroundColor Green
Write-Host ''

if ($Build) {
    Write-Host '  Running: mvnw.cmd clean verify' -ForegroundColor Cyan
    Push-Location $ProjectRoot
    try {
        & (Join-Path $ProjectRoot 'mvnw.cmd') -B -ntp clean verify
        $buildExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($buildExit -eq 0) {
        Write-Host ''
        Write-Host '  BUILD OK. Start the app with:  .\mvnw.cmd javafx:run' -ForegroundColor Green
        exit 0
    }
    Write-Host ''
    Write-Host "  BUILD FAILED (exit code $buildExit). Scroll up for the first [ERROR] line." -ForegroundColor Red
    exit $buildExit
}

Write-Host '  Next:  .\mvnw.cmd javafx:run     (or run with -Build to compile and test first)' -ForegroundColor DarkGray
Write-Host ''
exit 0
