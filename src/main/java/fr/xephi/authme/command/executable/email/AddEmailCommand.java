package fr.xephi.authme.command.executable.email;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Command for adding an email to an account.
 *
 * <p>Single-argument flow: issues {@code /email add <email>}; a verification
 * code is sent to the new address and the player completes the binding with
 * {@code /email confirm <code>}. An email address is not a secret and
 * therefore does not need to be confirmed by repetition.</p>
 */
public class AddEmailCommand extends PlayerCommand {

    @Inject
    private Management management;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        // Validation and verification code dispatch handled by the async task
        management.performAddEmail(player, arguments.get(0));
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_ADD_EMAIL;
    }
}
