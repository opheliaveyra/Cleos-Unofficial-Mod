# 小游戏中心 P0 实现报告

执行：沈晏执 / ChatGPT Work。2026-10-19。

## 状态

已提交实现代码和单元测试，**尚未完成编译、自动测试运行与真机验收**。本分支供代码审查，不应当作已验证安装包发布。

基线：`dbef0a1ca8edf1987cb2aa5809e02cd9578d808c`，目标分支 `feature/chat-file-upload-and-hold-to-talk`。独立施工分支 `work/games-p0`。施工前、提交前核对远端基线未变化；无法判断 DSH 本机尚未推送的修改。

## 完成的实现

- 主页记忆与收藏之间的小游戏中心入口，底栏数量不变。
- 原生 Compose 小游戏中心、井字棋页、真实 CompanionEntity 角色选择（头像/名字/当前 TA 优先），取消无 API 请求。
- 明示“邀请确认即开始”，未实现或伪造 TA 接受/拒绝邀请。
- 用户 X 先手，TA O 后手；棋盘由历史重放，规则与胜负由本地决定。
- 严格解析单个 0..8 数字；占用格和非法输出不落子；非法输出最多自动重试一次，网络错误/超时由用户主动重试。
- 使用现有 ChatClient、统一 Feature.Chat 路由、SecretStore、用量回调，purpose 为 `game_tic_tac_toe`；不调用聊天历史或工具。
- 当前源码已采用全局聊天模型路由，游戏沿用这条路由，UI 明说使用聊天模型；没有退回 TA 身上的旧 endpoint 字段。
- 请求安装唯一 ticket，提交时核对 sessionId、revision、O 回合、结果、TA 资料与模型配置；Mutex 串行修改存档。重新开局取消旧请求，旧请求 finally 也不能清除新请求标记。
- 每次模型请求 60 秒超时；同回合至多两次非法输出尝试。输出累计上限 256 字符，不生成替代落子。
- 独立 Room 数据库 `cleos-games.db`，单局 JSON 存档；不改主库 schema，避免 DSH 并行迁移冲突。不使用破坏性迁移。
- 单局立即持久化；打开页面不自动付费请求。进程重启后 O 回合显示“继续 TA 回合”，由用户点击继续。正常离页时请求可以在 App 作用域完成。
- TA 创建时间随棋局保存以防身份复用；设置页恢复/撤销恢复备份前取消请求并清理旧棋局。

## 与任务书的差异 / 限制

- 持久化采用独立 Room 数据库而非主库新增表；当前只保存一局。
- 游戏存档暂不加入已有 ZIP 备份，界面有明确说明。恢复或撤销恢复备份会清理当前游戏，即使随后备份导入失败也不会保留这一局。
- pending request 是进程内 ticket，重启后不自动恢复网络请求或声称请求仍在运行；保留棋盘并提供显式继续。
- 没有模拟对手模式，所有 O 落子都来自真实模型；缺少模型时保留局面并提示配置/重试。
- 真机、截图、真实 Provider 调用、离线/超时/双击/进程强杀交互尚未执行。尚未测得用量日志实值。
- 未改版本号、发布 Release、合并目标分支，未触碰文件上传、HTML 预览、远程连接逻辑。

## 验证记录

已执行：`git diff --check`，通过。

尝试执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
./gradlew :app:testDebugUnitTest :app:assembleRelease --console=plain
```

均在 Gradle wrapper 下载 `gradle-9.7.1-bin.zip` 阶段失败：`java.net.SocketException: Network is unreachable`。未进入 Kotlin 编译或测试阶段。不得将下述测试写成“通过”。

新增 `TicTacToeTest`：8 条胜利线与两种棋子、平局、越界/占用/错回合、终局禁落、严格模型解析、旧 session/revision/重复响应、损坏历史、JSON 序列化还原，以及枚举全部合法完整对局（期望 X 胜 131184、O 胜 77904、平局 46080）。这些是待运行的 Kotlin 测试，不是已运行结果。

## 真机验收路径

1. 在有 Android SDK 37 和项目依赖的环境运行以上单元测试及构建；失败时先修复编译问题，勿直接发布。
2. 主页 → 记忆下方“小游戏中心” → 井字棋。确认底栏仍为四项、返回正常。
3. 打开角色列表，确认真实头像/名字和当前 TA 默认选择；取消后确认没有新增 API 用量。
4. 邀请并开始；确认空棋盘、X 先手，邀请本身不发请求。选择不同 TA 重测。
5. 配置聊天模型并点一格。请求中连续点击格子不得多落；O 完成后用量记录应有 `game_tic_tac_toe`，归属受邀角色。
6. 模型返回占用格、9、文字或 JSON 时应拒绝，不得由本地算法代下；两次非法输出后应显示重试。
7. 断网、缺密钥、超时后棋盘保留 X，点击重试不会重复 X。配置修复后可继续 O 回合。
8. O 请求中返回主页再进入；重新开局确认后更换 TA。旧回复不得写入新局。
9. X/O 横竖斜获胜与平局后无法继续点格；重新邀请后得到空棋盘。
10. 退出/杀进程/重启后还原棋盘；若原来停在 O 回合，只有点击继续才发请求。
11. 删除受邀 TA 或更改模型/TA 资料时给出反馈，不用旧响应落子。恢复/撤销恢复 ZIP 后旧局被清理。
12. 回归朋友圈邀请（资格、评论选择、重复邀请）、主页导航、聊天与备份恢复。

## 提交与改动文件

- `c3ab1c7219f64658b4dfee8f4f227bc0a462de85`：纯规则与会话模型、Kotlin 单元测试。
- `010242ac51c5279ad0b1d2f3e5f88533347be489`：Room 游戏存档、进程级协调器、AppContainer 接入、备份恢复清局。
- `484ab65e02b215fb15bec7a8484d710d6b0b66db`：主页入口、导航回调、原生页面与角色邀请。

新增：`games/TicTacToe.kt`、`games/GameStore.kt`、`games/Games.kt`、`ui/games/GamesScreen.kt`、`test/.../games/TicTacToeTest.kt`。
修改：`CleosApp.kt`、`ui/CleosNavHost.kt`、`ui/MainScreen.kt`、`ui/home/HomeScreen.kt`、`ui/settings/SettingsViewModel.kt`。本报告位于 `docs/GAMES_P0_REPORT.md`。

保留原项目全部署名与许可；上述新增代码作为二改贡献，不将原作者实现标为原创。
