<#
.SYNOPSIS
    一键发布 Cleos 二改版到 GitHub Releases。

.DESCRIPTION
    构建正式 APK -> 检查签名 -> 生成校验文件 -> 用 gh 建 tag 和 Release -> 验证附件。

    每一步不过就停下：版本号读不到、工作区脏、Tag/Release 已存在、
    构建失败、签名证书对不上、发布后附件缺失 —— 都不会带着问题发出去。

    默认**预演**：只检查和构建，不创建任何 Release。
    真要发布加 -Publish，而且还会再问一次。

.PARAMETER Publish
    真的创建 Release。

.PARAMETER Yes
    跳过交互确认（不建议）。

.PARAMETER Notes
    更新说明文件。不给就从上次 tag 到 HEAD 的提交自动生成。

.EXAMPLE
    .\release.ps1            # 预演
    .\release.ps1 -Publish   # 真的发
#>
[CmdletBinding()]
param(
    [switch]$Publish,
    [switch]$Yes,
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"
$Repo = "opheliaveyra/Cleos-Unofficial-Mod"
$Project = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$GradleBat = "C:\Users\15536\.dsh\work\gradle-home\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat"
$ApkSigner = "C:\Users\15536\.dsh\tools\android-sdk\build-tools\36.0.0\apksigner.bat"
$JavaHome = "C:\Users\15536\.dsh\tools\jdk\jdk-21.0.12.1+1"
$Gh = "C:\Program Files\GitHub CLI\gh.exe"

# 现在手机上装的那个包的证书指纹。换签名 = 已装用户覆盖安装会失败，所以是硬门槛。
$ExpectedCert = "aa665f898d22be54e6f80165a4f3c2630192acf2c49f86c4f8e53eb9068bcb55"

function Step($n, $text) { Write-Host ""; Write-Host ("[" + $n + "] " + $text) -ForegroundColor Cyan }
function Ok($text) { Write-Host ("    OK   " + $text) -ForegroundColor Green }
function Warn($text) { Write-Host ("    !!   " + $text) -ForegroundColor Yellow }
function Die($text) { Write-Host ""; Write-Host ("停在这里：" + $text) -ForegroundColor Red; exit 1 }

$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = "C:\Users\15536\.dsh\work\gradle-home"
$env:TMP = "C:\Users\15536\.dsh\work\tmp"
$env:TEMP = "C:\Users\15536\.dsh\work\tmp"
$env:ANDROID_HOME = "C:\Users\15536\.dsh\tools\android-sdk"
$env:ANDROID_SDK_HOME = "C:\Users\15536\.dsh\work\android-user"
$env:LOCALAPPDATA = "C:\Users\15536\.dsh\work\localappdata"
$env:PATH = "$JavaHome\bin;$env:PATH"

Set-Location $Project
$mode = if ($Publish) { "正式发布" } else { "预演（不会创建 Release）" }
Write-Host ("Cleos 二改版 · 发布流程 —— " + $mode) -ForegroundColor White

Step 1 "检查版本号和 versionCode"
$gradleFile = Join-Path $Project "app\build.gradle.kts"
if (-not (Test-Path $gradleFile)) { Die "找不到 app/build.gradle.kts" }
$content = Get-Content $gradleFile -Raw
$vm = [regex]::Match($content, "versionName\s*=\s*""([^""]+)""")
$vc = [regex]::Match($content, "versionCode\s*=\s*(\d+)")
if (-not $vm.Success) { Die "读不到 versionName" }
if (-not $vc.Success) { Die "读不到 versionCode" }
$versionName = $vm.Groups[1].Value
$versionCode = [int]$vc.Groups[1].Value
Ok ("versionName = " + $versionName)
Ok ("versionCode = " + $versionCode)
$Tag = "v" + ($versionName -replace "-mod$", "")
Ok ("Tag 会是 " + $Tag)

Step 2 "检查工作区"
$dirty = git status --porcelain
if ($dirty) {
    Warn "有未提交的改动："
    $dirty | Select-Object -First 10 | ForEach-Object { Write-Host ("         " + $_) }
    Die "先提交或清理，再发布。发出去的必须能对应到一个提交。"
}
$head = git rev-parse --short HEAD
Ok ("工作区干净，HEAD = " + $head)

Step 3 "检查 Tag 和 Release 是否已经存在"
if (-not (Test-Path $Gh)) { Die ("找不到 gh：" + $Gh) }
$ghLoggedIn = $false
$prev = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $Gh auth status 2>$null | Out-Null
$ErrorActionPreference = $prev
if ($LASTEXITCODE -eq 0) { $ghLoggedIn = $true }
if (-not $ghLoggedIn) {
    if ($Publish) {
        Die "gh 还没登录。先在这台机器上跑一次 gh auth login（授权存在本机，脚本不碰 token）。"
    }
    Warn "gh 还没登录 —— 预演可以继续，但正式发布会停在这一步"
} else {
    Ok "gh 已登录"
    $ErrorActionPreference = "Continue"
    & $Gh release view $Tag --repo $Repo --json tagName 2>$null | Out-Null
    $seen = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($seen -eq 0) {
        Die ("Release " + $Tag + " 已经存在。不会覆盖 —— 要发新版本就先把 versionName/versionCode 往上加。")
    }
}
$existingTag = git ls-remote --tags mine $Tag 2>$null
if ($existingTag) {
    Die ("远端已经有 tag " + $Tag + " 了。先处理掉它，或者换个版本号。")
}
Ok "Tag 和 Release 都还不存在"

Step 4 "构建 release APK"
$apkPath = Join-Path $Project "app\build\outputs\apk\release\app-release.apk"
if (Test-Path $apkPath) { Remove-Item $apkPath -Force }
$gradleArgs = @(":app:assembleRelease", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process")
$buildLog = Join-Path $env:TEMP "cleos-release-build.log"
& $GradleBat @gradleArgs 2>&1 | Tee-Object -FilePath $buildLog | Out-Null
if ($LASTEXITCODE -ne 0) {
    Get-Content $buildLog -Tail 20 | ForEach-Object { Write-Host ("         " + $_) }
    Die "构建失败，看上面的 gradle 输出。"
}
if (-not (Test-Path $apkPath)) { Die ("构建说成功了，但没找到 " + $apkPath) }
$apkSize = (Get-Item $apkPath).Length
Ok ("APK 出来了：" + [math]::Round($apkSize / 1MB, 1) + " MB")

Step 5 "校验签名"
if (-not (Test-Path $ApkSigner)) { Die ("找不到 apksigner：" + $ApkSigner) }
$certOut = & $ApkSigner verify --print-certs $apkPath 2>&1
$certLine = $certOut | Select-String "SHA-256 digest" | Select-Object -First 1
if (-not $certLine) { Die ("读不到签名证书：" + ($certOut -join " ")) }
$cert = ($certLine.Line -split ":", 2)[1].Trim()
if ($cert -ne $ExpectedCert) {
    Write-Host ("         实际：" + $cert) -ForegroundColor Red
    Write-Host ("         预期：" + $ExpectedCert) -ForegroundColor Red
    Die "签名证书对不上。发出去之后，已经装了 Cleos 的人会覆盖安装失败。"
}
Ok "签名证书和现在手机上装的一致"
Ok ("证书 SHA-256 = " + $cert.Substring(0, 16) + "...")

Step 6 "生成 APK 和校验文件"
$distDir = "C:\Users\15536\.dsh\work\dist"
if (-not (Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir | Out-Null }
$apkName = "CLEOS-" + $versionName + ".apk"
$distApk = Join-Path $distDir $apkName
Copy-Item $apkPath $distApk -Force
$sha = (Get-FileHash $distApk -Algorithm SHA256).Hash.ToLower()
$shaFile = Join-Path $distDir ($apkName + ".sha256")
Set-Content -Path $shaFile -Value ($sha + "  " + $apkName) -Encoding ASCII -NoNewline
Ok $apkName
Ok ("SHA-256 = " + $sha)
Ok ($apkName + ".sha256")

Step 7 "生成更新说明"
$body = ""
if ($Notes -and (Test-Path $Notes)) {
    $body = Get-Content $Notes -Raw
    Ok ("用指定的说明文件：" + $Notes)
} else {
    $lastTag = git describe --tags --abbrev=0 2>$null
    if ($LASTEXITCODE -ne 0) { $lastTag = $null }
    if ($lastTag) {
        $log = git log ($lastTag + "..HEAD") --pretty=format:"- %s" --no-merges
        $body = $versionName + "`n`n这一版：`n`n" + $log
        Ok ("从 " + $lastTag + " 到 HEAD 的提交生成说明")
    } else {
        $log = git log -20 --pretty=format:"- %s" --no-merges
        $body = $versionName + "`n`n这一版：`n`n" + $log
        Ok "没有旧 tag，用最近 20 条提交生成说明"
    }
}
$body = $body + "`n`n安装包：" + $apkName + "`nSHA-256：" + $sha
$notesFile = Join-Path $env:TEMP "cleos-release-notes.md"
Set-Content -Path $notesFile -Value $body -Encoding UTF8
Write-Host ""
Write-Host "    ---- 更新说明预览 ----" -ForegroundColor DarkGray
$body -split "`n" | Select-Object -First 12 | ForEach-Object { Write-Host ("    " + $_) -ForegroundColor DarkGray }
Write-Host "    ----------------------" -ForegroundColor DarkGray

if (-not $Publish) {
    Write-Host ""
    Write-Host "预演结束：检查通过，构建成功，但**没有创建任何 Release**。" -ForegroundColor Yellow
    Write-Host "确认要发就加 -Publish 再跑一次。" -ForegroundColor Yellow
    exit 0
}

Step 8 "发布前最后确认"
Write-Host ""
Write-Host ("    版本：" + $versionName + "（versionCode " + $versionCode + "）")
Write-Host ("    Tag：" + $Tag)
Write-Host ("    APK：" + $apkName + "（" + [math]::Round($apkSize / 1MB, 1) + " MB）")
Write-Host ("    仓库：" + $Repo)
Write-Host "    这是公开的正式 Release，所有人都会看到并能下载。" -ForegroundColor Yellow
Write-Host ""
if (-not $Yes) {
    $answer = Read-Host "确认发布就输入 yes"
    if ($answer -ne "yes") { Write-Host "没发。"; exit 0 }
}

Step 9 "创建 Release 并上传"
& $Gh release create $Tag $distApk $shaFile --repo $Repo --title $versionName --notes-file $notesFile --latest 2>&1 | ForEach-Object { Write-Host ("         " + $_) }
if ($LASTEXITCODE -ne 0) { Die "gh release create 失败（看上面输出）。" }
Ok "Release 已创建"

Step 10 "检查附件和下载链接"
Start-Sleep -Seconds 3
$json = & $Gh release view $Tag --repo $Repo --json url,isPrerelease,assets 2>&1
if ($LASTEXITCODE -ne 0) { Die "创建后读不到这个 Release，去网页上看一眼。" }
$rel = $json | ConvertFrom-Json
Ok ("Release 地址：" + $rel.url)
if ($rel.isPrerelease) { Warn "这个 Release 被标成了 prerelease —— App 的更新检查会跳过它！" } else { Ok "不是 prerelease（App 能识别）" }
$assetNames = $rel.assets | ForEach-Object { $_.name }
foreach ($need in @($apkName, ($apkName + ".sha256"))) {
    if ($assetNames -contains $need) { Ok ("附件在：" + $need) } else { Die ("附件缺失：" + $need) }
}
$apkAsset = $rel.assets | Where-Object { $_.name -eq $apkName } | Select-Object -First 1
if ($apkAsset.size -ne $apkSize) {
    Die ("上传后附件大小不对（本地 " + $apkSize + "，远端 " + $apkAsset.size + "）")
}
Ok ("附件大小一致（" + $apkAsset.size + " 字节）")
try {
    $resp = Invoke-WebRequest -Uri $apkAsset.url -Method Head -MaximumRedirection 5 -TimeoutSec 30 -UseBasicParsing
    Ok ("下载链接可用（HTTP " + [int]$resp.StatusCode + "）")
} catch {
    Warn ("下载链接探测失败：" + $_.Exception.Message + " —— 去网页上手动点一下试试")
}

Write-Host ""
Write-Host ("发布完成：" + $versionName + " -> " + $rel.url) -ForegroundColor Green
Write-Host "手机上点「检查更新」应该能看到这个版本。" -ForegroundColor Green