# Local build without GitHub Packages access.
# Requires morphe-patcher 2.0.0 and morphe-patches-gradle-plugin 1.3.4 in ~/.m2
# (publishToMavenLocal from C:\Users\MidTano\Documents\morphe-src\*).
# The settings plugin calls .get() on GITHUB_ACTOR/GITHUB_TOKEN, so they must be set;
# placeholder values are fine because everything resolves from mavenLocal and public repos.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    if (-not $env:GITHUB_ACTOR) { $env:GITHUB_ACTOR = 'local' }
    if (-not $env:GITHUB_TOKEN) { $env:GITHUB_TOKEN = 'none' }
    New-Item -ItemType Directory -Force build | Out-Null
    cmd /c "gradlew.bat :patches:buildAndroid -PallowMavenLocal=true --dependency-verification lenient --no-daemon --console=plain > build\patches-build.log 2>&1"
    if ($LASTEXITCODE -ne 0) {
        Get-Content build\patches-build.log -Tail 40
        throw "Build failed, see build\patches-build.log"
    }
    Get-ChildItem patches\build\libs\*.mpp | Select-Object Name, Length, LastWriteTime
} finally {
    Pop-Location
}
