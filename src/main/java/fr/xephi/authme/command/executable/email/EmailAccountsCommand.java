package fr.xephi.authme.command.executable.email;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Command to list all accounts bound to the email address of the calling player.
 *
 * <p>In the v2 account system one email address may be bound by multiple accounts;
 * this command shows which accounts share the player's email.</p>
 */
public class EmailAccountsCommand extends PlayerCommand {

    @Inject
    private Management management;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        management.performViewEmailAccounts(player);
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_EMAIL_ACCOUNTS;
    }
}
