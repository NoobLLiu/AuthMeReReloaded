package fr.xephi.authme.process.email;

import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.AsynchronousProcess;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.util.Utils;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Async task to list the accounts bound to the email address of the calling player.
 *
 * <p>In the v2 account system one email address may be bound by multiple accounts,
 * which all share the same password (see {@link fr.xephi.authme.service.EmailPasswordService}).
 * This task queries the database for all accounts using the player's email address.</p>
 */
public class AsyncViewEmailAccounts implements AsynchronousProcess {

    @Inject
    private CommonService service;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private DataSource dataSource;

    AsyncViewEmailAccounts() {
    }

    /**
     * Lists all accounts bound to the email address of the given player.
     *
     * @param player the player whose email accounts are requested
     */
    public void viewAccounts(Player player) {
        String playerName = player.getName().toLowerCase(Locale.ROOT);
        if (!playerCache.isAuthenticated(playerName)) {
            sendUnloggedMessage(player);
            return;
        }
        PlayerAuth auth = playerCache.getAuth(playerName);
        if (auth == null || Utils.isEmailEmpty(auth.getEmail())) {
            service.send(player, MessageKey.SHOW_NO_EMAIL);
            return;
        }

        List<String> accounts = dataSource.getAllAuthsByEmail(auth.getEmail());
        if (accounts.isEmpty()) {
            service.send(player, MessageKey.EMAIL_ACCOUNTS_NONE);
        } else {
            service.send(player, MessageKey.EMAIL_ACCOUNTS_LIST,
                String.valueOf(accounts.size()), String.join(", ", accounts));
        }
    }

    private void sendUnloggedMessage(Player player) {
        if (dataSource.isAuthAvailable(player.getName())) {
            service.send(player, MessageKey.LOGIN_MESSAGE);
        } else {
            service.send(player, MessageKey.REGISTER_MESSAGE);
        }
    }
}
