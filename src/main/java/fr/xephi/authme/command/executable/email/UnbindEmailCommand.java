package fr.xephi.authme.command.executable.email;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Command to unbind an account from the email address of the calling player.
 *
 * <p>In the v2 account system one email address may be bound by multiple accounts;
 * this command removes the email binding from another account of the player's
 * email group. The unbound account keeps its password but no longer shares the
 * email's password.</p>
 */
public class UnbindEmailCommand extends PlayerCommand {

    @Inject
    private Management management;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        management.performUnbindEmail(player, arguments.get(0));
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_EMAIL_UNBIND;
    }
}
