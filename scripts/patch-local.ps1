# Patches the TikTok APK in the repo root with the freshly built bundle.
# Output: build\tmp-patch\tiktok-adskip.apk (signed with morphe-data\morphe.keystore, same key
# as the TikTok already on the phone, so it installs over it without losing data).
param(
    [string]$Out = 'build\tmp-patch\tiktok-adskip.apk'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $apk = Get-ChildItem -LiteralPath $repoRoot -Filter 'com.zhiliaoapp.musically_*_apkmirror.com.apk' |
        Where-Object { $_.Name -notlike '*.patched.apk' } | Select-Object -First 1
    if (-not $apk) { throw 'No TikTok APK in the repo root' }
    $mpp = Get-ChildItem patches\build\libs\*.mpp | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    New-Item -ItemType Directory -Force build\tmp-patch | Out-Null
    if (Test-Path build\tmp-patch\tmp) { Remove-Item -Recurse -Force build\tmp-patch\tmp }
    $args = @('-Xmx6g', '-jar', 'morphe-cli.jar', 'patch',
        '-p', $mpp.FullName,
        '-e', 'Skip ads by sound',
        '-o', $Out,
        '-t', 'build\tmp-patch\tmp',
        '-r', 'build\tmp-patch\result.json',
        $apk.FullName)
    & java @args *> build\tmp-patch\patch.log
    $code = $LASTEXITCODE
    Get-Content build\tmp-patch\patch.log -Tail 20
    if ($code -ne 0) { throw "Patching failed ($code), see build\tmp-patch\patch.log" }
    if (Test-Path build\tmp-patch\tmp) { Remove-Item -Recurse -Force build\tmp-patch\tmp }
    Get-Item $Out | Select-Object FullName, Length, LastWriteTime
} finally {
    Pop-Location
}
