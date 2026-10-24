# 津渡施工交接包 · Cleos 用量补全与记忆主动性守护（2026-10-25）

## 交接边界
这是 Aster 的第5次 GitHub 提交，交接完成后才由津渡开始业务代码施工。此前5次均为审计/方案提交，**没有宣称代码功能已经完成**。
目标仓库：opheliaveyra/Cleos-Unofficial-Mod
目标分支：feature/chat-file-upload-and-hold-to-talk
原则：不改原作者默认主题、不覆盖紫雾手账素材、不破坏已有数据和权限机制。

## P0：补后台 Token 用量
现有 `TokenUsageEntity`、`UsageTag` 和客户端流式统计能力，优先复用，不建第二张表。

已核对调用点：
- `ai/ChatRepository.kt` 普通聊天(约930/1702)、视频(1568)、视觉(1617)已有 usageTag；保持行为。
- `ai/GroupChatRepository.kt` 约472已有 UsageTag；验证角色 speaker 归属。
- `ai/Recap.kt` 约217：摘要模型调用未显式传 usageTag，优先补 purpose=recap。
- `ai/FeedAi.kt` 约115/140/233/310：逐个检查用途、归属和标签，补 purpose=feed 或精确用途。
- `ai/Letters.kt` 约399：补 purpose=letter。
- 扫描所有其余 `client.stream` 调用，列出 memory/diary/probe/tool 等覆盖表，不遗漏后台路径。

施工前核对 `UsageTag` 的字段、客户端写入逻辑、成功/失败/取消语义；不能因未显式传参就武断推断完全无统计。
验收：成功、失败、取消、重试；提供 usage 和不提供 usage；scope=ta/group/global 正确；后台统计不重复；不记 Key/正文/附件；统计页面可见；清除逻辑有效；现有聊天无回归。

## P1：记忆系统，仅补缺口
`ai/Memory.kt` 已有 profile/interest/recent/rapport/self 五类，单类10主题、单主题30细节；chat 只注入摘要，细节需 open；recent 日期、pinned 保护已存在。
`ai/Recap.kt` 已有滚动前情提要、按用户消息边界折叠、并发修改保护。
因此不要移植 Mem0 全套架构。先补重复记忆/冲突与时效的测试，再评估最小增量。任何记忆写入都不得绕过 pinned 或角色隔离。不要改变 prompt 缓存友好的排列。

## P2：主动消息，只做查漏补缺
`ai/Later.kt` 已有 WorkManager、MAX_WAITING=4、UNANSWERED_MAX=2、SOON_MINUTES=1、RETRY_MINUTES=10、过期宽限；`ChatRepository.kt` 根据 ta.proactive 决定 Later 工具可用性。
不要重做调度器、频率熔断或 note_for_later。检查静默时段、唯一工作标识、重启恢复、通知权限、电池优化，提供实际缺口证据再提改动。
安卓真机的通知/后台调度行为必须单列验证，不可用单测冒充真机。

## 建议执行顺序
1. 津渡先给出调用点清单与拟修改文件，不要同时重构记忆和主动消息。
2. 做 P0 最小可回滚改动并提交，运行单测与构建；记录实际 commit SHA。
3. 给玖玖可安装的测试包前，先备份现有数据；不得要求先卸载原版或二改版。
4. P1/P2 必须先提交缺口审计与测试设计，经玖玖确认再实施。

## 禁止事项
- 不加入不明许可证的第三方代码、不依赖云端 Mem0 托管服务。
- 不在日志、报告、截图中泄露 API Key、消息正文和私密日记。
- 不修改默认主题、不混用紫雾手账/水晶图标。
- 不为了统计改变聊天回复内容或模型路由。
- 不擅自发布正式版本、不把未经真机验证的功能标成已验证。

## 津渡交付格式
- 变更文件与原因
- GitHub commit SHA
- 单元测试与构建结果（具体命令、成功/失败）
- 模拟器/真机验证结果及未验证项
- Token 统计覆盖表（purpose / 调用点 / scope / 是否记录）
- 已知风险和回滚方式

## Aster 前置审计文件
1. `docs/CLEOS-GITHUB-CANDIDATES-AUDIT-20261025.md`
2. `docs/CLEOS-MEMORY-PROACTIVITY-SOURCE-AUDIT-20261025.md`
3. `docs/CLEOS-LATER-RECAP-SOURCE-AUDIT-20261025.md`
4. `docs/CLEOS-TOKEN-USAGE-CALLSITE-AUDIT-20261025.md`

验收人：玖玖。Aster 负责审核交付，津渡负责施工。此交接包不构成用户授权自动发布 APK。
