# 方案一提示词：通过 Floodgate API 直接修改基岩玩家身份

## 你要做什么

在 AuthMe 插件中，为基岩版玩家（通过 Geyser+Floodgate 连接）实现身份切换功能。目前 Java 玩家的身份切换已正常工作，但基岩版玩家切换后重连仍是原始身份。

## 项目概况

- **项目**：AuthMeReloaded Fork（5.7.0-FORK），Minecraft 登录/认证插件
- **位置**：`/workspace`
- **构建**：Maven，`mvn -DskipTests package`
- **分支**：`trae/agent-0iHQBQ`（基于 `origin/feat/agent-mail`）

## 已实现的功能

`/lg` 命令打开 GUI 菜单，显示当前账号、绑定邮箱、同邮箱下所有账号。选择账号后断开连接，3 分钟内重连即以目标账号身份上线并自动登录。

身份切换的核心流程：
1. `IdentitySwitchManager.initiateSwitch()` — 校验切换请求，记录 `PendingSwitch`（3 分钟有效）
2. 玩家被踢出，提示重新进入
3. 重连时 `LoginStartRewriteAdapter`（ProtocolLib PacketAdapter，MONITOR 优先级）改写 Login Start 包的 name+UUID
4. `PreLoginIdentityListener`（`AsyncPlayerPreLoginEvent`，HIGHEST）通过 Paper Profile API 改写 profile
5. `IdentitySwitchJoinListener`（`PlayerJoinEvent`，MONITOR，20 tick 延迟）验证实际加入的身份，消费 PendingSwitch 并标记自动登录
6. `IdentityAutoLoginListener` 执行 forceLogin

## 基岩版的问题

对于通过 Geyser+Floodgate 连接的基岩版玩家：
- 步骤 3 的包重写被 Floodgate 覆盖（Floodgate 在自己的 PacketAdapter 中从 GeyserSession 重新设置名字）
- 步骤 4 的 Paper Profile API 修改被 Floodgate 忽略（Floodgate 从 GeyserSession 创建 Player 对象，不使用 AsyncPlayerPreLoginEvent 的 profile）
- 结果：Player 以原始基岩版身份加入，身份切换失败
- 步骤 5 检测到名字不匹配，保持 PendingSwitch 活跃，发送 `bedrock_unsupported` 提示

## 方案一：通过 Floodgate API 直接修改玩家身份

### 原理

Floodgate 内部维护了 `FloodgatePlayer` 对象，存储基岩玩家的 Java 侧名字（通常带 "BE_" 前缀）和 UUID（`00000000-0000-0000-xxxx-xxxxxxxxxxxx` 格式）。Player 创建时，Floodgate 从这个对象读取身份信息。如果在 Player 创建之前修改这个对象，Floodgate 就会用修改后的身份创建 Player。

### 实现步骤

1. **添加 Floodgate API 依赖**（`pom.xml`，`<scope>provided</scope>`）
   - groupId: `org.geysermc.floodgate`, artifactId: `api`, version: `2.2.3-SNAPSHOT`（或与服务器匹配的版本）

2. **修改 `PreLoginIdentityListener`**：
   - 在 `AsyncPlayerPreLoginEvent`（建议 LOWEST 优先级，或现有 HIGHEST 但在 Paper profile 改写之前）
   - 检测到 PendingSwitch 后，判断是否为 Floodgate 玩家：`FloodgateApi.getInstance().isFloodgatePlayer(event.getUniqueId())`
   - 如果是，获取 `FloodgatePlayer` 对象
   - 通过反射修改其内部的 `username` 字段为目标账号名字，`javaUniqueId` 字段为目标账号 UUID
   - Paper profile 改写仍然执行（作为 Java 玩家的兜底）

3. **确保 `IdentitySwitchJoinListener` 正确处理**：
   - 基岩版玩家修改 Floodgate 身份后，应以目标账号身份加入
   - `consumePendingSwitchByTarget(nameLower)` 应能找到并消费 switch
   - 自动登录应正常触发

4. **处理回退**：
   - 如果 Floodgate API 不可用（类找不到），跳过 Floodgate 特殊处理，回退到现有逻辑
   - 用 try-catch ClassNotFoundException 保护 Floodgate API 调用

### 关键文件

| 文件 | 作用 |
|------|------|
| `src/main/java/fr/xephi/authme/listener/PreLoginIdentityListener.java` | 主要修改文件，添加 Floodgate 特殊处理 |
| `src/main/java/fr/xephi/authme/listener/protocollib/LoginStartRewriteAdapter.java` | 包级别重写（可能需要调整优先级或禁用，避免与 Floodgate 处理冲突） |
| `src/main/java/fr/xephi/authme/listener/IdentitySwitchJoinListener.java` | PlayerJoinEvent 验证（已实现，确认兼容即可） |
| `src/main/java/fr/xephi/authme/identity/IdentitySwitchManager.java` | PendingSwitch 管理（已实现，无需修改） |
| `pom.xml` | 添加 Floodgate API 依赖 |

### 预期行为

- 基岩版玩家 `BE_XingYES6396` 通过 /lg 切换到 Java 账号 `Xing_YES`
- 断开连接，重连
- `PreLoginIdentityListener` 检测到 Floodgate 玩家，通过 API 修改其 `username` 为 `Xing_YES`，`javaUniqueId` 为目标 UUID
- Player 以 `Xing_YES` 身份加入，UUID 为目标账号的 UUID
- `IdentitySwitchJoinListener` 消费 switch，`IdentityAutoLoginListener` 自动登录
- 服务器日志应显示玩家以 `Xing_YES` 身份加入（不是 `BE_XingYES6396`）

### 注意事项

- Floodgate API 是 `provided` 依赖，运行时由服务器的 Floodgate 插件提供
- 反射访问 Floodgate 内部字段需要处理字段名变化（建议做容错，字段名不对时 log warning 并跳过）
- 基岩→基岩切换（同 Floodgate UUID，不同 Java 名字）也应支持
- 需要确认 Floodgate 版本：服务器用的是 Floodgate-standalone 还是 Geyser 内置 Floodgate
