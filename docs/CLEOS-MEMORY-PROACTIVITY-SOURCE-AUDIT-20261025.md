# Cleos 源码复核 · 记忆与主动性（2026-10-25）

本轮为源码审计，不代表功能已改造或真机验证。审计分支：feature/chat-file-upload-and-hold-to-talk。

## 源码证据
1. `app/src/main/java/com/cleo/cleos/ai/Memory.kt`
   - `MemoryKinds` 五类：profile、interest、recent、rapport、self。
   - `PER_KIND=10`、`DETAILS=30`、摘要120字符、细节300字符。
   - `MemoryDigest.forChat` 注入主题摘要，要求模型用 memory open 看细节，禁止从摘要猜。
   - recent 带更新时间，pinned 记忆明确不能改删。
   - `forLetter` 细节预算16000字符。
2. `app/src/main/java/com/cleo/cleos/ai/Prompt.kt`
   - 系统提示词将 persona、工具规则、表情、记忆和 recap 分层拼装，变化项靠后以维护缓存前缀。
   - 已有 `note_for_later` 规则：只记录具体待跟进事项，不记“看看回没回”等骚扰性内容。
   - 有主动搭话提示词路径；调度实现、通知权限和触发逻辑仍需单独追踪。
3. `app/src/main/java/com/cleo/cleos/data/db/TokenUsage.kt`
   - 数据表已有 purpose（chat/group_chat/recap/memory/diary/vision/video/tool/feed/letter/probe）、scope、model、provider、source、cached、reasoning。
   - 数据结构支持后台用量，但并不证明各请求路径均写入；README 明确说摘要/记忆/日记等后台路径尚未完整记录。

## 更新后的结论
- 不要移植 Mem0 的完整数据模型。Cleos 已有分层记忆、按需细节、固定分类、用户锁定和摘要机制。
- 优先审计记忆更新是否做重复检测、冲突标注、时效清理，随后只增补缺口。
- 优先补齐后台请求的 TokenUsage 写入路径，并验证失败请求的 source=missing 与统计一致性。
- 不要另造主动消息调度器。先定位 note_for_later 的保存/触发/去重，以及主动搭话调度、通知权限、频率与静默时段。
- 保护 prompt 缓存顺序、角色隔离、原版默认主题、紫雾手账主题隔离和现有备份格式。

## 津渡的最小下一步
A. 找出全部 `TokenUsageEntity` 构造和 `usageDao.add` 调用，做目的类型覆盖表，优先补 recap/memory 的遗漏；补单测。
B. 找出 `note_for_later` 及主动搭话调度的存储与触发点，列出重启重复提醒、静默时间、频率限制的现状。
C. 找出 `MemoryBook` 的 remember/update 与 recap 生成/注入路径，确认是否有冲突处理，先写测试再改。
D. 只做一个可回滚的增量提交，提供编译/测试结果；未真机验证必须显式标注。

此文件是源码证据和施工任务，不是完成施工的证明。
