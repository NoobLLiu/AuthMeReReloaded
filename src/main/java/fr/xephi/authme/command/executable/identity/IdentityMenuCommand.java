package fr.xephi.authme.command.executable.identity;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.identity.IdentityMenuService;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Opens the identity menu (/lg) showing the current account, its bound email address and
 * the other accounts registered under the same email, which can be switched to.
 */
public class IdentityMenuCommand extends PlayerCommand {

    @Inject
    private IdentityMenuService identityMenuService;
    @Inject
    private PlayerCache playerCache;
    @Inject
    private Messages messages;

    @Override
    protected void runCommand(Player player, List<String> arguments) {
        if (!playerCache.isAuthenticated(player.getName())) {
            messages.send(player, MessageKey.NOT_LOGGED_IN);
            return;
        }
        identityMenuService.open(player);
    }
}
