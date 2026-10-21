# 签名与升级方案（先确认，再动）

日期：2026-10-21。**本文件只做确认，没有改任何签名配置。**

## 一、你主力机上装的那个包，签名是什么

从设备上把 `base.apk` 拉下来，用 `apksigner` 读的：

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256: aa665f898d22be54e6f80165a4f3c2630192acf2c49f86c4f8e53eb9068bcb55
Signer #1 certificate SHA-1:   ae282c9048ac5917ba6ae56fdad232e037c3df0d
```

名字里带 "Debug" 是因为证书的 CN 沿用了 Android 默认的 debug 证书名，**但这不是每次构建随机生成的那个 debug.keystore**，而是一份固定的 keystore。

证据：`keystore.properties` 里写的是
```
storeFile=C:/Users/15536/.dsh/work/signing/cleos-test.keystore
keyAlias=androiddebugkey
```
而且是 `build.gradle.kts` 里 `signingConfigs.cleo` 在生效（不是 `signingConfig = debug`）。

## 二、keystore 还在吗、备份可不可靠

| 位置 | 大小 | 文件 SHA-256 |
|---|---|---|
| `work/signing/cleos-test.keystore`（正在用的） | 3 KB | `500A491C318B6BCE826026DFBE628DE71971EF448B4A0FFDA5CD90F0E6D10B99` |
| `work/dist/签名备份/cleos-test-signing-debug.keystore`（备份） | 3 KB | `500A491C318B6BCE826026DFBE628DE71971EF448B4A0FFDA5CD90F0E6D10B99` |

**两份完全相同**（文件哈希一致）。备份那份用 `keytool` 打开验证过，密码对、证书读得出来，SHA-256 与设备上的包一致。

| 项 | 值 |
|---|---|
| 别名 | `androiddebugkey` |
| storePassword | `android` |
| keyPassword | `android`（推测同 storePassword，已验证能读） |
| 证书 SHA-256 | `AA:66:5F:89:8D:22:BE:54:E6:F8:01:65:A4:F3:C2:63:01:92:AC:F2:C4:9F:86:C4:F8:E5:3E:B9:06:8B:CB:55` |

## 三、能不能保留现有签名直接覆盖升级

**能，而且这是风险最低的路。**

只要同时满足：
1. 包的签名证书和现在装的一样（现在这份 keystore 就能保证）
2. `versionCode` 比现在大（现在 267，下一版 268）

系统就会直接覆盖安装，**不要求卸载，角色 / 聊天记录 / 设置全部原地保留**。

不需要换签名，不需要卸载，不需要导出导入。

## 四、真正要防的不是签名，是「keystore 文件丢了」

签名不会自己变，风险在于这个 3KB 文件哪天没了（换机器、清目录、重装系统）。

丢了会怎样：以后签出来的包和已装的对不上 → 只能卸载重装 → 数据没了。

所以要做的最小动作：
1. **再备份一份到仓库外面**（夸克网盘 / 邮箱 / U 盘都行）—— 现在只有本机两份，本机坏了就全没了
2. 把上面那张表（别名、密码、指纹）单独存一份
3. 我可以在 `dist` 里放一个 `签名说明.txt`，密码写不写由你定

这两件做完，「数据不能丢 + 以后更新方便」就都成立了。

## 五、万一哪天必须换签名，怎么保住数据

App 自带「设置 › 数据与备份」：**导出、恢复、导入记忆**。路线是：

1. 旧版里：数据与备份 → 导出（导成文件，存到手机外部或电脑）
2. 用新签名的包装上（这时必须卸载旧的，因为签名不同装不上去）
3. 新版里：数据与备份 → 恢复 → 选刚才那个文件

**代价**：要手动走一遍导出/恢复；没导过的本地东西（比如临时缓存）会丢。
**所以能不换就不换。**

## 六、结论与建议（等你点头再动）

| 问题 | 答案 |
|---|---|
| 1. 当前签名证书 | `CN=Android Debug`，SHA-256 `aa66…cb55`（固定 keystore，不是随机 debug） |
| 2. keystore 在不在 | 在，还有一份完全相同备份，密码能开 |
| 3. 能否保留签名直接覆盖升级 | **能**，versionCode 递增即可，不用卸载、数据不动 |
| 4. 必须换签名时怎么保数据 | 应用内「数据与备份」导出 → 卸载 → 新包装 → 恢复 |

**建议方案（风险最低）**：
- **不换签名**，继续用 `cleos-test.keystore`
- 把备份再放一份到仓库外面（这一步只有你能做，因为它要离开这台机器）
- 确认后我再：① 用它构建 0.1.96 ② 你建 Release ③ 我把更新安装那四个场景跑完

在这之前我不会动签名配置，也不会做任何需要卸载的事。
