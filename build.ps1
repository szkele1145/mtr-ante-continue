# [修复 2026-10-21] 原脚本硬编码 "F:\mtr-ante\gradlew.bat"（工程实际在
# F:\vibe coding工程\mtr-ante），且超时 2 秒过短，必然失败。
# 改为脚本目录相对路径 + 走 Z: 挂载规避中文路径 mojibake + 正常超时。
# 本文件必须以 UTF-8 带 BOM 保存（PowerShell 5.1 对无 BOM 脚本按 GBK 解码）。

$projRoot = $PSScriptRoot
if (-not $projRoot) { $projRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }

# 中文路径 mojibake 规避：subst 一个纯 ASCII 盘符
subst V: /d 2>$null | Out-Null
subst V: "$projRoot" | Out-Null
if (-not (Test-Path 'V:\')) { Write-Host 'subst failed: V: mount error'; exit 1 }

try {
	Push-Location 'V:\'
	& 'V:\gradlew.bat' build '-PbuildVersion=1.18.2' --warning-mode all --no-daemon
	$code = $LASTEXITCODE
} finally {
	Pop-Location
	subst V: /d 2>$null | Out-Null
}
exit $code
