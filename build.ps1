# [修复 2026-10-21] 原脚本硬编码 "F:\mtr-ante\gradlew.bat"（工程实际在
# F:\vibe coding工程\mtr-ante），且超时 2 秒过短，必然失败。
# 改为脚本目录相对路径 + 走 Z: 挂载规避中文路径 mojibake + 正常超时。
# 本文件必须以 UTF-8 带 BOM 保存（PowerShell 5.1 对无 BOM 脚本按 GBK 解码）。
#
# [修复 2026-10-21 第二次] 不再用 gradlew：
#   Gradle wrapper 下载 gradle-8.11-bin.zip 会撞上 JVM TLS 中间盒
#   （AEADBadTagException: Tag mismatch）—— 直连 piston-data 同类问题。
#   改用本机已有的 gradle 8.11 分发（sfx\gradle-8.11 junction）。
#   参数：build.ps1 [mcVersion] [gradleTask]
#     mcVersion 默认 1.20.1
#     gradleTask 默认 build

$projRoot = $PSScriptRoot
if (-not $projRoot) { $projRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }

$mcVersion = '1.20.1'
$gradleTask = 'build'
if ($args.Count -ge 1 -and $args[0]) { $mcVersion = [string]$args[0] }
if ($args.Count -ge 2 -and $args[1]) { $gradleTask = [string]$args[1] }

# 中文路径 mojibake 规避：subst 一个纯 ASCII 盘符
subst V: /d 2>$null | Out-Null
subst V: "$projRoot" | Out-Null
if (-not (Test-Path 'V:\')) { Write-Host 'subst failed: V: mount error'; exit 1 }

# 找 gradle 8.11：优先 sfx\gradle-8.11（junction -> wrapper 缓存 dists），
# 其次系统 PATH 上的 gradle。
$gradleBat = $null
$sfxGradle = 'F:\vibe coding工程\sfx\gradle-8.11\bin\gradle.bat'
if (Test-Path $sfxGradle) { $gradleBat = $sfxGradle }
else {
	$cmd = Get-Command gradle.bat -ErrorAction SilentlyContinue
	if ($cmd) { $gradleBat = $cmd.Source }
}
if (-not $gradleBat) {
	Write-Host 'gradle not found: 期望 F:\vibe coding工程\sfx\gradle-8.11\bin\gradle.bat'
	Write-Host '（不要用 gradlew —— wrapper 下载会被 TLS 中间盒截断）'
	subst V: /d 2>$null | Out-Null
	exit 1
}
Write-Host "[build.ps1] gradle = $gradleBat"
Write-Host "[build.ps1] mcVersion = $mcVersion  task = $gradleTask"

try {
	Push-Location 'V:\'
	& $gradleBat $gradleTask "-PbuildVersion=$mcVersion" --warning-mode all --no-daemon
	$code = $LASTEXITCODE
} finally {
	Pop-Location
	subst V: /d 2>$null | Out-Null
}
exit $code
