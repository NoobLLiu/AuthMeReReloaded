# AI 记忆 — AuthMe 多账号身份切换（/lg）

> 本文件用于在新对话中恢复上下文。新对话开始时先读本文件，再按需查看 `git status` / `git log` 确认状态。

## 一、项目概况

- **项目**：AuthMeReloaded（Minecraft 登录/认证插件），当前为 Fork 版本 `5.7.0-FORK`
- **位置**：`/workspace`
- **构建**：Maven（`pom.xml`，Java + Spigot API + ProtocolLib 等依赖）。已成功构建，产物在 `target/`：
  - `AuthMe-5.7.0-FORK-Lite.jar` / `AuthMe-5.7.0-FORK-Universal.jar`（构建于 2026-09-12 04:13，与最新提交同时，含本次功能）
- **远程**：`origin/feat/agent-mail`（主开发分支），作者 NoobLLiu

## 二、Git 状态（截至 2026-09-12）

- **当前分支**：`trae/agent-0iHQBQ`
- **工作区**：干净，无未提交更改
- **分支领先 `origin/feat/agent-mail` 共 2 个提交（尚未合并）**：
  1. `cd073f1` feat: 研究实现Minecraft多账号身份切换 — 仅 `.trae-html-share-packages` 下 5 个 `*.html.zip` 重新打包（内容不变）
  2. `7fde343` feat: 研究实现Minecraft多账号身份切换 — **核心功能实现**，20 个文件，`+1424 / -4`
- `origin/feat/agent-mail` 当前 HEAD：`e0c9b60`（Merge pull request #1 from NoobLLiu/trae/agent-YXxVwV）

## 三、本次会话做了什么

1. 任务：生成合并差异摘要报告（`origin/feat/agent-mail...trae/agent-0iHQBQ`）
2. 用 `git diff origin/feat/agent-mail...trae/agent-0iHQBQ -- <file>` 逐文件分析了全部 25 个变更文件
3. 解压对比了 5 个 `.html.zip`（确认内部 HTML 模板内容一致，仅 zip 打包元数据变化）
4. 已向用户输出 Markdown 格式摘要报告（总体概述 + 文件级变更表格）

## 四、功能详情：/lg 个人登录信息与身份切换

为 AuthMe 新增"同邮箱多账号身份切换"能力：

- **命令**：`/lg`（已注册于 `plugin.yml`，Tab 补全已接入），仅已登录玩家可用，未登录提示 `NOT_LOGGED_IN`
- **GUI 菜单**（`IdentityMenuService` 构建，54 格背包界面）：
  - 显示当前账号（玩家头颅）、绑定邮箱（纸）、同邮箱下其他账号（头颅，区分 Java/基岩版）
  - 分页（每页 21 个账号槽位），翻页箭头与关闭按钮
  - 数据异步拉取（`DataSource.getAllAuthsByEmail`），主线程打开
- **切换流程**（`IdentitySwitchManager.initiateSwitch` 校验链）：
  - 校验：目标非自身、源账号已绑定邮箱、目标存在、目标与源同邮箱、目标不在线、无并发切换冲突
  - 通过后记录 `PendingSwitch`（3 分钟有效，`ExpiringMap`），关闭菜单并踢出玩家，提示重进
- **重连身份改写**：
  - 优先：Paper Profile API — `PreLoginIdentityListener` 在 `AsyncPlayerPreLoginEvent`（HIGHEST）反射调用 `getPlayerProfile/setPlayerProfile/setName/setId` 改写名字与 UUID
  - 回退：无 Paper API 时 `ProtocolLibService` 注册 `LoginStartRewriteAdapter`，改写客户端 Login Start 包（兼容 1.20.2+ profile 字段与旧版纯用户名；基岩身份在旧版协议下无法携带 UUID 会失败）
  - 仅当重连 IP 与发起切换 IP 一致时生效；目标在线则取消切换
- **自动登录**：`IdentityAutoLoginListener` 在 `PlayerJoinEvent`（MONITOR）延迟 1 秒，若命中自动登录授权则 `AuthMeApi.forceLogin` 并提示"身份已切换，已自动登录"
- **消息**：`MessageKey` 新增约 20 个 `identity.*` 枚举；`messages_en/zhcn/zhhk.yml` 各新增 23 条文案；`help_en/zhcn/zhhk.yml` 各新增 /lg 帮助条目

## 五、本次合并涉及的文件清单（25 个）

**新增（10）**：
- `src/main/java/fr/xephi/authme/command/executable/identity/IdentityMenuCommand.java`
- `src/main/java/fr/xephi/authme/identity/IdentityMenuHolder.java`
- `src/main/java/fr/xephi/authme/identity/IdentityMenuService.java`
- `src/main/java/fr/xephi/authme/identity/IdentitySwitchManager.java`
- `src/main/java/fr/xephi/authme/identity/PendingSwitch.java`
- `src/main/java/fr/xephi/authme/listener/IdentityAutoLoginListener.java`
- `src/main/java/fr/xephi/authme/listener/IdentityMenuClickListener.java`
- `src/main/java/fr/xephi/authme/listener/PreLoginIdentityListener.java`
- `src/main/java/fr/xephi/authme/listener/protocollib/LoginStartRewriteAdapter.java`

**修改（16）**：
- `AuthMe.java`（注册监听器、注入单例、lg 加入 Tab 补全）
- `CommandInitializer.java`（注册 /lg 基础命令）
- `ProtocolLibService.java`（按 Paper API 有无注册/卸载回退适配器）
- `MessageKey.java`（identity.* 消息枚举）
- `messages_en/zhcn/zhhk.yml`、`help_en/zhcn/zhhk.yml`、`plugin.yml`
- `.trae-html-share-packages/src/main/resources/` 下 5 个 `*.html.zip`（内容不变）

## 六、会话 2：UUID 修复（2026-09-12，未提交）

**用户反馈的 bug**：切换身份后目标账号名字正确，但 UUID 不是目标账号自己的（是重新生成的）。

**根因**：`MySQL.buildAuthFromResultSet` 读取了 `PLAYER_UUID` 列，但 **SQLite、H2、PostgreSQL 的 `buildAuthFromResultSet` 都没读 UUID** → 这些数据源下 `getAuth().getUuid()` 永远为 null → `resolveTargetUuid` 回退到 `computeOfflineUuid`（按名字重新生成的离线 UUID）。默认数据源 SQLite 必现。

**修复（4 处，工作区未提交，构建已通过 `mvn -DskipTests package`，产物 05:17）**：
1. `datasource/SQLite.java` — `buildAuthFromResultSet` 读取 `PLAYER_UUID`（`UuidUtils.parseUuidSafely`），新增 imports
2. `datasource/H2.java` — 同上
3. `datasource/PostgreSqlDataSource.java` — 同上
4. `identity/IdentityMenuService.java` — `createAccountItem` 的 lore 第一行新增 `UUID: <uuid>`（深灰色，与当前账号项格式一致），显示的就是 DB 中的注册 UUID

**UUID 数据流（已验证）**：注册时 `PlayerAuthBuilderHelper.createPlayerAuth` 存 `player.getUniqueId()` → `saveAuth` 写入 `AuthMeColumns.UUID` 列（建表/ALTER 保证列存在）→ 修复后 `getAuth` 读回 → 菜单显示 & `resolveTargetUuid` 直接使用 → Paper `setId` / ProtocolLib `WrappedGameProfile(uuid, name)` 原样传递。`CacheDataSource.getAuth` 委托 `source.getAuth`，同样受益。

**已知边界**：老账号若 DB 中 UUID 为 NULL（注册早于 UUID 列），仍回退离线 UUID；正常新注册账号都有 UUID。

## 七、后续可继续的工作（新会话候选）

1. **合并分支**：将 `trae/agent-0iHQBQ` 合并到 `origin/feat/agent-mail`（合并/创建 PR）
2. **功能验证**：在测试服验证 /lg 菜单、切换、重连改写与自动登录（含基岩版账号场景）
3. **潜在改进点**（未做，仅提示）：
   - 其余语言（如 zhtw/ja 等）的 help/messages 翻译尚未补充
   - 无 Paper API + 基岩目标的切换失败仅日志警告，无玩家提示
   - `PendingSwitch` 与自动登录均依赖 `ExpiringMap`，3 分钟窗口固定，未做成配置项
