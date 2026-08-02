# AuthMe 玩家邮箱绑定 API 导出接口说明

本文档介绍如何在其他插件中使用 AuthMeReloaded (Fork 版本) 提供的 API 来获取玩家的邮箱绑定状态。

## 获取 API 实例

在您的插件中，可以通过 `AuthMeApi.getInstance()` 获取 API 实例：

```java
import fr.xephi.authme.api.v3.AuthMeApi;

AuthMeApi authMeApi = AuthMeApi.getInstance();
if (authMeApi != null) {
    // 使用 API
}
```

## 导出接口方法

以下是本次新增的与邮箱绑定状态相关的 API 方法：

### 1. 获取已绑定邮箱
返回玩家当前已绑定的邮箱地址。

*   **方法**: `String getEmail(String playerName)`
*   **返回值**: 邮箱地址字符串；如果玩家未绑定邮箱或玩家不存在，则返回 `null`。

### 2. 检查是否已绑定邮箱
快速判断玩家是否已经绑定了邮箱。

*   **方法**: `boolean hasEmail(String playerName)`
*   **返回值**: `true` 表示已绑定，`false` 表示未绑定。

### 3. 检查是否有待确认的邮箱变更
判断玩家当前是否正在进行邮箱绑定或修改流程（即已发送验证码但尚未输入 `/email confirm`）。

*   **方法**: `boolean hasPendingEmailChange(String playerName)`
*   **返回值**: `true` 表示存在待确认的变更，`false` 表示没有。

### 4. 获取待确认的邮箱地址
获取玩家正在尝试绑定但尚未确认的新邮箱地址。

*   **方法**: `String getPendingEmail(String playerName)`
*   **返回值**: 待确认的邮箱地址；如果没有待确认的变更，则返回 `null`。

### 5. 检查邮箱是否已被占用
检查某个邮箱地址是否已被服务器上的任何账号绑定。

*   **方法**: `boolean isEmailUsed(String email)`
*   **返回值**: `true` 表示已被占用，`false` 表示可用。

## 代码示例

### 示例：检查玩家是否可以参加需要邮箱绑定的活动

```java
public boolean canJoinEvent(Player player) {
    AuthMeApi api = AuthMeApi.getInstance();
    if (api == null) return false;

    String name = player.getName();
    
    // 1. 检查是否已经绑定
    if (api.hasEmail(name)) {
        return true;
    }
    
    // 2. 检查是否正在绑定流程中
    if (api.hasPendingEmailChange(name)) {
        player.sendMessage("请先完成邮箱验证确认！");
    } else {
        player.sendMessage("请先使用 /email add <邮箱> 绑定邮箱！");
    }
    
    return false;
}
```

## 注意事项

1.  **异步调用**: `getEmail` 和 `isEmailUsed` 可能会涉及数据库查询，建议在异步任务中调用以避免阻塞主线程。
2.  **大小写**: 玩家名处理在 API 内部已实现大小写不敏感（统一转小写处理），但建议传入原始玩家名。
3.  **待确认状态**: 待确认的邮箱变更在内存中缓存，有效期为 10 分钟。重启服务器会导致该状态丢失（玩家需重新发起绑定请求）。
