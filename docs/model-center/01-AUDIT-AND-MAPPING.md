# 模型中心整合 · 审计与映射（改动前）

日期：2026-10-21。分支 `feat/lavender-scrapbook-local`。

## 一、现在三个入口分别在做什么

| 入口 | 文件 | 实际内容 |
|---|---|---|
| 「模型」 | `ui/settings/TaPages.kt:199` `ModelPage` | ① 全局默认聊天模型（从 registry 的 `defaultConfigId` 读，弹窗选）② 「打电话、发语音时换个模型」开关 ③ **旧式语音模型编辑器** `ModelProfilesEditor + ServiceChips + ConnectionFields`（独立 baseUrl/model/key，和 registry 不通用）④ 一段解释三种数据结构的说明文字 |
| 「服务商」 | `ui/settings/ModelRegistryPages.kt:165` `ProvidersPage` | 服务商列表（名字、地址、类型、名下模型数）+ 「测试连接」+ 添加/编辑/删除服务商（弹窗里填地址、类型、Key） |
| 「模型配置」 | `ui/settings/ModelRegistryPages.kt:337` `ModelConfigsPage` | 所有模型配置的**平铺列表** + 每条上的「测看图」「测看视频」两个按钮 + 编辑/删除 + 批量添加 |

## 二、数据层现状（两套并存）

### 新的（registry）
`data/ModelRegistry.kt`：
- `Provider(id, name, baseUrl, kind)` —— Key 按地址存在 SecretStore
- `ModelConfig(id, providerId, model, name, temperature, maxTokens, capabilities)`
- `FeatureRoute(configId, overrides)` —— 按功能绑配置
- `ModelRegistryData(providers, configs, routes, defaultConfigId)`
- `Feature` 枚举（`data/db/Entities.kt:155`）：**Chat / Recap / Memory / Title / Feed / Letters / Groups / Vision / Video** —— **没有语音**

### 旧的（语音专用）
`data/ModelProfiles.kt` 的 `ModelProfile`：独立的 `baseUrl` + `model` + `directImage/directVideo`，
由 `vm.spokenOn` + `vm.spoken` 驱动，走 `ModelProfilesEditor` 那套编辑器。

**结论：语音是这个「三入口重复」问题的根源之一** —— 它既不在服务商体系里，也不在功能绑定里，只能从「模型」页进。

## 三、映射：哪些合并、哪些移动、哪些保留

### 合并进「模型中心」首页（只留三件事）

| 原位置 | 新位置 | 说明 |
|---|---|---|
| 「模型」页的全局默认 + 「换一条」弹窗 | **首页 · 当前使用的模型** | 直接显示当前全局模型，点一下换 |
| 「服务商」页的列表 + 添加服务商 | **首页 · 服务商与模型管理** | 按服务商分组，点服务商进详情 |
| 「模型配置」页的平铺列表 | **按服务商分组**（不再平铺） | 「服务商 → 模型」层级 |
| 「模型」页的语音开关 + 旧编辑器 | **首页 · 按用途选择模型**（保留旧配置兼容） | 见第四节 |

### 移到「服务商详情」页

| 原位置 | 新位置 |
|---|---|
| 服务商行上的「测试连接」 | 服务商详情顶部 |
| 服务商编辑弹窗（地址/类型/Key） | 服务商详情 |
| 「模型配置」里属于该服务商的条目 | 服务商详情内的模型列表 |
| 「批量添加模型」「手动添加模型」 | 服务商详情 |

### 移到「模型详情」页

| 原位置 | 新位置 |
|---|---|
| 每条模型配置上的「测看图」「测看视频」 | **模型详情页**（不再铺在列表上） |
| 编辑弹窗里的参数（temperature / maxTokens） | 模型详情 |
| 能力标记（`capabilities`）和「未验证」提示 | 模型详情 |

### 保留独立

| 项 | 原因 |
|---|---|
| 全局默认模型 | 每天都要换，必须一步可达 |
| 按用途分配页 | 9 个 Feature + 语音，集中一页，不散落 |
| 服务商 / 模型详情 | 层级关系，不能塞回首页 |

## 四、语音模型迁移方案（旧配置不丢）

1. `Feature` 枚举**新增** `Spoken("语音/电话", …)`。
2. 解析顺序（`ModelRoutes` 里加）：
   - 新：`routes["Spoken"]` 有配置 → 用它
   - 旧：`spokenOn && 旧 ModelProfile 有效` → 用旧的
   - 都无 → 跟随全局默认
3. 旧配置**不删不改**：`spokenOn / vm.spoken / SecretStore 里的 key` 原样保留，只是不再作为主入口。
4. UI：用途页里「语音/电话」一行，可以选 registry 里的模型；下面挂一条「沿用旧配置」的入口（可展开编辑），迁完能给个「确认迁到新配置」的按钮。
5. Key 不丢：Key 在 SecretStore 按 baseUrl 存；迁移只改引用，不写、不清 Key。

## 五、目标页面结构

```
设置
└ 模型中心
   ├ 当前使用的模型        （① 一步切换）
   ├ 服务商与模型管理
   │   └ 服务商详情
   │       └ 模型详情      （参数 / 能力 / 测试）
   └ 按用途选择模型        （9 个 Feature + 语音）
```

设置首页把「模型」「服务商」「模型配置」三行**合成一行**「模型中心」。

## 六、工作量（如实）

- 新增页面：模型中心首页、服务商详情、模型详情、用途分配 —— 4 个
- 改数据层：Feature 加 Spoken、路由加语音解析、旧配置兼容
- 改设置路由：三行合一行，保留旧路径做重定向（防止有人从旧入口进来）
- 真机验证 8 项

这不是「三个入口包一个壳」，原来平铺的 9 条模型和 4 个测试按钮都会挪到层级里。


---

# 实施记录（2026-10-21）

## 改了什么

| 文件 | 改动 |
|---|---|
| `data/db/Entities.kt` | `Feature` 加 `Spoken("语音/电话")` |
| `ai/ChatRepository.kt` | `spokenOrChat` 加中间层：旧配置 > 模型中心「语音/电话」> 全局默认 |
| `ui/settings/ModelCenterPage.kt` | **新增**：模型中心首页 / 服务商详情 / 模型详情 / 按用途，四个视图在一个页面里切换 |
| `ui/settings/SpokenModelPage.kt` | **新增**：旧的 TA 专属语音模型配置，原样搬过来（配置没动） |
| `ui/settings/SettingsPages.kt` | 枚举加 `ModelCenter` / `SpokenModel`；设置首页三行合成一行 |
| `ui/settings/SettingsScreen.kt` | 旧三个入口（Model/Providers/ModelConfigs）全部指向模型中心 |
| `ui/settings/ModelRegistryPages.kt` | `ProviderDialog` / `ConfigDialog` / 若干小组件改 internal，给模型中心复用 |
| `ui/settings/TaPages.kt` | `ServiceChips` / `ConnectionFields` / `ListRow` 改 internal |

## 语音这条怎么处理的

旧配置**完全保留**：
- `spokenOn`、`vm.spoken`、SecretStore 里的 Key 一个没动
- 入口从「模型」页挪到「按用途 › 语音/电话」点进去
- 解析顺序：**旧配置优先**（老用户行为不变）→ 模型中心「语音/电话」绑定 → 全局默认

所以：
- 升级上来的老用户：电话还是用他原来那条
- 想把语音换成模型中心里某条：先去把旧配置关掉，再去「按用途」里给语音选

## 验证到哪一步

| 项 | 状态 |
|---|---|
| 设置首页三入口合成「模型中心」一行 | ✅ 真机（02） |
| 模型中心首页：当前模型 + 服务商分组 + 按用途 | ✅ 真机（03/04） |
| 按用途页：9 个功能 + 语音/电话 | ✅ 真机（05） |
| 语音行显示旧配置状态、点进旧配置页 | ✅ 代码接入，**未真机点验** |
| 服务商详情 / 模型详情 | ✅ 代码已写，**未真机点验**（点偏了一次） |
| 首次添加服务商和模型 | ⏸ 未验（怕动到她现有 4 家 13 个模型的数据） |
| 已有用户直接切模型 | ⏸ 未验（同上） |
| 旧设置 / 加密 Key 不丢 | ✅ 没碰 SecretStore，配置读写逻辑没动 |
| 默认主题 / 紫雾主题 | ⏸ 未验这一版 |

## 没做完的

1. 服务商详情、模型详情、语音配置页的真机点击验证
2. 首次添加流程（新建服务商 → 加模型）的完整走查
3. 旧「模型」页里那段 `FeatureRoutesPage` 现在不可达了，应该删掉（还在 TaPages.kt 里）
