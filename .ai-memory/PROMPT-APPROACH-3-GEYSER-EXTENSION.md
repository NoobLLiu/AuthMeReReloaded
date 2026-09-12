# 方案三提示词：通过 Geyser 扩展层拦截实现基岩版身份切换

## 你要做什么

在 AuthMe 插件中，为基岩版玩家（通过 Geyser+Floodgate 连接）实现身份切换功能。目前 Java 玩家的身份切换已正常工作，但基岩版玩家切换后重连仍是原始身份。本方案通过 Geyser 扩展（Extension）在 Geyser 层面修改玩家的 Java 侧身份。

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

## 方案三：Geyser 扩展层拦截

### 原理

Geyser 是将 Bedrock 协议翻译为 Java 协议的代理层。它有扩展 API（Geyser Extension API），允许插件在 Geyser 内部拦截和修改各种事件，包括玩家登录过程。如果在 Geyser 层面修改了玩家的 Java 侧名字和 UUID，那么 Floodgate 接收到的就已经是目标身份，Player 对象会以正确的身份创建。

这个方案的优势是从**源头**解决问题——在数据进入 Floodgate/Bukkit 之前就修改好。

### 实现架构

需要两个组件：
1. **Geyser 扩展**（独立 jar，部署在 Geyser 的 `extensions/` 目录下）
2. **AuthMe 插件端的协调逻辑**（修改 AuthMe 代码，与 Geyser 扩展通信）

### Geyser 扩展端

#### 依赖
- `org.geysermc.geyser:api:2.4.3-SNAPSHOT`（或与服务器匹配的版本）
- `org.geysermc.floodgate:api:2.2.3-SNAPSHOT`

#### 需要拦截的事件
- `org.geysermc.geyser.api.event.bedrock.SessionLoginEvent`（或等效的登录事件）
- 或使用 Geyser 的 `GeyserSessionPostLoginEvent` / `GeyserSessionInitializeEvent`

#### 核心逻辑
```
1. 监听 Geyser 登录事件
2. 检查该玩家是否有待处理的身份切换（通过共享存储，见下文）
3. 如果有，修改 session 的：
   - username（Java 侧显示名）
   - uuid（Java 侧 UUID）
   - xuid → uuid 映射（如果 Floodgate 维护了这个映射）
4. Floodgate 接收到的已经是目标身份，Player 以目标身份创建
```

#### 组件间通信
AuthMe 和 Geyser 扩展需要共享 PendingSwitch 信息。可选方案：
- **方案 A：共享数据库/文件** — AuthMe 将 PendingSwitch 写入一个共享文件或数据库表，Geyser 扩展读取
- **方案 B：插件消息通道** — 使用 Bukkit Plugin Messaging Channel 或 Geyser 扩展的消息 API 通信
- **方案 C：Redis/内存共享** — 如果服务器使用 Redis，两个组件通过 Redis 通信
- **方案 D：Geyser 扩展 API 注册** — AuthMe 通过 Geyser Extension API 注册一个回调，Geyser 扩展在登录时调用

推荐**方案 D**（最直接）或**方案 A**（最简单）。

### AuthMe 插件端

#### 修改
1. 添加 Geyser Extension API 依赖（`provided` scope）
2. 在 `IdentitySwitchManager.initiateSwitch()` 中，如果玩家是 Floodgate 玩家：
   - 通过共享机制记录 PendingSwitch 信息（名字、目标 UUID、IP 等）
3. Geyser 扩展完成身份修改后，AuthMe 端的 `IdentitySwitchJoinListener` 检测到目标身份加入，消费 switch 并自动登录

### 关键文件

| 文件 | 作用 |
|------|------|
| **新建** Geyser 扩展项目 | 独立 Maven 项目，生成 jar 部署到 `extensions/` |
| `src/main/java/fr/xephi/authme/identity/IdentitySwitchManager.java` | 添加 Floodgate 玩家的 PendingSwitch 共享逻辑 |
| `src/main/java/fr/xephi/authme/listener/PreLoginIdentityListener.java` | 可能需要跳过 Floodgate 玩家（让 Geyser 扩展处理） |
| `src/main/java/fr/xephi/authme/listener/IdentitySwitchJoinListener.java` | 已实现，确认兼容即可 |
| `pom.xml` | 添加 Geyser Extension API 依赖（provided） |

### Geyser 扩展项目结构

```
authme-geyser-extension/
├── pom.xml
├── src/main/java/
│   └── com/authme/geyser/
│       ├── AuthMeGeyserExtension.java      # 扩展入口
│       ├── IdentitySwitchListener.java      # 监听 Geyser 登录事件
│       └── PendingSwitchStore.java          # 共享 PendingSwitch 存储
└── src/main/resources/
    └── geyser-extension.yml                 # Geyser 扩展元数据
```

### 预期行为

- 基岩版玩家 `BE_XingYES6396` 通过 /lg 切换到 Java 账号 `Xing_YES`
- AuthMe 记录 PendingSwitch 到共享存储
- 玩家断开连接，重连
- Geyser 扩展在 Geyser 登录阶段读取 PendingSwitch，修改 session 的 username 为 `Xing_YES`、uuid 为目标 UUID
- Floodgate 接收到修改后的身份，Player 以 `Xing_YES` 身份加入
- AuthMe 的 `IdentitySwitchJoinListener` 消费 switch，`IdentityAutoLoginListener` 自动登录

### 注意事项

- Geyser 扩展需要单独构建和部署（部署到 Geyser 的 `extensions/` 目录），增加运维步骤
- Geyser Extension API 版本需要与服务器的 Geyser 版本匹配
- 如果 Geyser 和 AuthMe 不在同一台机器上（BungeeCord/Waterfall 场景），共享机制需要网络通信
- 基岩版皮肤和 XUID 映射需要正确处理，避免切换后皮肤异常
- 测试时需要同时部署 Geyser 扩展和 AuthMe 插件
