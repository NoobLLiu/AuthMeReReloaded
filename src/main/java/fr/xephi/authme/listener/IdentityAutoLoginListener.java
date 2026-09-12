package fr.xephi.authme.listener;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.identity.IdentitySwitchManager;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.service.BukkitService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import javax.inject.Inject;
import java.util.Locale;

/**
 * Automatically logs in a player whose identity was rewritten on reconnection (identity
 * switch feature), provided the reconnection came from the same address the switch was
 * initiated from.
 */
public class IdentityAutoLoginListener implements Listener {

    private final AuthMeApi authmeApi = AuthMeApi.getInstance();
    private final IdentitySwitchManager identitySwitchManager;
    private final Messages messages;
    private final BukkitService bukkitService;

    @Inject
    IdentityAutoLoginListener(IdentitySwitchManager identitySwitchManager, Messages messages,
                              BukkitService bukkitService) {
        this.identitySwitchManager = identitySwitchManager;
        this.messages = messages;
        this.bukkitService = bukkitService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        bukkitService.runTaskLater(player, () -> {
            if (!player.isOnline() || authmeApi.isAuthenticated(player)
                || !authmeApi.isRegistered(name)) {
                return;
            }
            String ip = player.getAddress() == null || player.getAddress().getAddress() == null
                ? null
                : player.getAddress().getAddress().getHostAddress();
            if (identitySwitchManager.consumeAutoLogin(name.toLowerCase(Locale.ROOT), ip)) {
                authmeApi.forceLogin(player, true);
                messages.send(player, MessageKey.IDENTITY_SWITCHED_AUTO_LOGGED_IN);
            }
        }, 20L);
    }
}
