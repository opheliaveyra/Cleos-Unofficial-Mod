# 手机真机测试操作手册（给接手的人）

> 我在新窗口里接手时也卡过这里：**不知道怎么在手机上点/截图/测试**。这份就是为此写的。
> 全部用 **adb**，不需要开任何 GUI 工具。

## 0. 一分钟上手

```powershell
$adb = 'C:\Users\15536\.dsh\tools\android-sdk\platform-tools\adb.exe'
& $adb devices                       # 看设备在不在
& $adb -s <序列号> shell screencap -p /sdcard/x.png   # 截图到手机
& $adb -s <序列号> pull /sdcard/x.png C:\Users\15536\.dsh\work\dev\x.png   # 拉回电脑
# 然后用 read_image 看 x.png
```

**截图必须用 `screencap -p` 到文件再 `pull`，不要 `exec-out >`**（二进制会被 PowerShell 搞坏）。

## 1. 环境

- adb：`C:\Users\15536\.dsh\tools\android-sdk\platform-tools\adb.exe`
- 每个命令前加：`$env:TMP = 'C:\Users\15536\.dsh\temp'; $env:TEMP = 'C:\Users\15536\.dsh\temp'`（否则 adb 可能因临时目录权限起不来）
- 设备：
  - 荣耀 **YAL-AL50**（Android 10），序列号 `NDYDU19830006567`
  - 红米 **23076RA4BC**（Android 14/16），序列号 `5aab6582aec6` / `10CG2C1GCA005J7`（换过机，**以 `adb devices` 实际为准**）
- **序列号每次可能变**，先 `adb devices`，别硬编码

## 2. 安装与启动

```powershell
& $adb -s <序列号> install -r 'C:\Users\15536\.dsh\work\dist\CLEOS-<版本>-mod.apk'
& $adb -s <序列号> shell am start -n com.cleo.cleos/.MainActivity
& $adb -s <序列号> shell am force-stop com.cleo.cleos   # 先杀掉再启动，保证是新的
```

**红米/小米** 如果报 `INSTALL_FAILED_USER_RESTRICTED`：开发者选项里开「USB 安装」；或者 `adb push` 到 `/sdcard/Download` 让她手动点。

## 3. 看界面 / 拿控件坐标

```powershell
& $adb -s <序列号> shell uiautomator dump /sdcard/ui.xml
& $adb -s <序列号> pull /sdcard/ui.xml C:\Users\15536\.dsh\work\dev\ui.xml
```

ui.xml 里每个节点有 `text="..." bounds="[左,上][右,下]"` —— **bounds 是实际像素坐标**，
点中心：`(左+右)/2, (上+下)/2`。

**注意换算**：如果你先看了缩小的预览图（比如 838×1862）再换算到实际（1260×2800），
**别用预览图的视觉位置**，直接用 dump 出来的 bounds（它们已经是实际坐标）。

## 4. 点击 / 滑动 / 输入

```powershell
& $adb -s <序列号> shell input tap <x> <y>
& $adb -s <序列号> shell input swipe <x1> <y1> <x2> <y2> <毫秒>
& $adb -s <序列号> shell input keyevent KEYCODE_BACK
& $adb -s <序列号> shell input text "hello"     # 只支持 ASCII
```

**中文输入**：`input text` 不支持中文。手机如果是**讯飞输入法**（九键），可以 `input text "nihao"`
出候选再点第一个；或者用**剪贴板**方案。最稳的是让她自己打字。

## 5. 判断"点到了没有"

每次操作后**截图 + dump**，别连点。判断标准：
- 截图变了 → 可能成功
- `ui.xml` 里没有你预期的元素 → 没点到/页面没开
- 找不到元素时，先截图看当前在哪个页面（**很容易停在错误的页面**）

## 6. 常见坑

| 坑 | 表现 | 处理 |
|---|---|---|
| `device offline` | 命令全报 offline | `adb kill-server; adb start-server`，等几秒再 `devices` |
| `no devices/emulators found` | 同上 | 同上；确认线插好、手机开了 USB 调试 |
| `INSTALL_FAILED_USER_RESTRICTED` | 装不上 | 开「USB 安装」或 push 到 Download 手动装 |
| 坐标点空 | 页面没变 | 用 dump 的 bounds；别用预览图估 |
| 误触 | 点了别的按钮/页面 | 截图确认当前页；`KEYCODE_BACK` 回退 |
| 返回栈乱 | 回到奇怪的页面 | 多按几次 BACK，或 `am start` 重开 |
| 中文输入 | `input text` 无效 | 换拼音/剪贴板 |
| 截图后看不见 | 文件不存在 | 先确认 `screencap` 成功（`ls -l /sdcard/x.png`） |

## 7. 一个完整的"装 + 截图 + 看"脚本

```powershell
$adb = 'C:\Users\15536\.dsh\tools\android-sdk\platform-tools\adb.exe'
$env:TMP = 'C:\Users\15536\.dsh\temp'; $env:TEMP = 'C:\Users\15536\.dsh\temp'
$dev = (& $adb devices | Select-String '\sdevice$' | Select-Object -First 1).ToString().Split("`t")[0]
& $adb -s $dev install -r 'C:\Users\15536\.dsh\work\dist\CLEOS-0.1.91-mod.apk'
& $adb -s $dev shell am force-stop com.cleo.cleos
& $adb -s $dev shell am start -n com.cleo.cleos/.MainActivity
Start-Sleep -Seconds 6
& $adb -s $dev shell screencap -p /sdcard/shot.png
& $adb -s $dev pull /sdcard/shot.png C:\Users\15536\.dsh\work\dev\shot.png
```

然后 `read_image` 看 `C:\Users\15536\.dsh\work\dev\shot.png`。

## 8. 判断"能不能算测过"

- **真机上看到的**才算测过；没有真机截图 = 没测过
- 每次交付：装到设备 → 打开 → 操作 → 截图 → 存档
- 两台设备都测更好；只测一台要说明是哪台
