package fr.xephi.authme.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 邮箱绑定确认完成事件：玩家通过 {@code /email confirm} 成功持久化邮箱后触发。
 * <p>与 {@link EmailChangedEvent}（发起绑定/修改时触发）不同，本事件在邮箱真正写入数据库后触发，
 * 可用于通知其他插件（如数据整合插件）向网站后端同步"该玩家已完成邮箱绑定"。</p>
 */
public class EmailConfirmedEvent extends CustomEvent {

    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final String email;

    /**
     * 构造器
     *
     * @param player 完成邮箱绑定的玩家
     * @param email  已确认绑定的邮箱地址
     */
    public EmailConfirmedEvent(Player player, String email) {
        super(false);
        this.player = player;
        this.email = email;
    }

    /**
     * 获取完成绑定的玩家
     *
     * @return 玩家
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * 获取已确认绑定的邮箱地址
     *
     * @return 邮箱地址
     */
    public String getEmail() {
        return email;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    /**
     * 返回处理器列表，{@link Event} 所需
     *
     * @return HandlerList
     */
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
