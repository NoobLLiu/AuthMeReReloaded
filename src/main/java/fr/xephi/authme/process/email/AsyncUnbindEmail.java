package fr.xephi.authme.process.email;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.process.AsynchronousProcess;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.util.Utils;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.Locale;

/**
 * Async task to unbind an account from the email address of the calling player.
 *
 * <p>In the v2 account system one email address may be bound by multiple accounts.
 * The calling player may remove another account from their email group; the unbound
 * account keeps its password but no longer shares the email's password. Unbinding
 * the account the player is currently logged in as is not allowed.</p>
 */
public class AsyncUnbindEmail implements AsynchronousProcess {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AsyncUnbindEmail.class);

    @Inject
    private CommonService service;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private DataSource dataSource;

    AsyncUnbindEmail() {
    }

    /**
     * Unbinds the given account from the email address of the calling player.
     *
     * @param player the player performing the unbind
     * @param accountName the name of the account to unbind
     */
    public void unbind(Player player, String accountName) {
        String playerName = player.getName().toLowerCase(Locale.ROOT);
        if (!playerCache.isAuthenticated(playerName)) {
            sendUnloggedMessage(player);
            return;
        }
        String targetName = accountName.toLowerCase(Locale.ROOT);
        if (targetName.equals(playerName)) {
            service.send(player, MessageKey.EMAIL_UNBIND_OWN_ACCOUNT);
            return;
        }

        PlayerAuth auth = playerCache.getAuth(playerName);
        if (auth == null || Utils.isEmailEmpty(auth.getEmail())) {
            service.send(player, MessageKey.SHOW_NO_EMAIL);
            return;
        }
        String email = auth.getEmail();

        PlayerAuth targetAuth = dataSource.getAuth(targetName);
        if (targetAuth == null) {
            service.send(player, MessageKey.UNKNOWN_USER);
            return;
        }
        if (!email.equalsIgnoreCase(targetAuth.getEmail())) {
            service.send(player, MessageKey.EMAIL_UNBIND_NOT_BOUND, targetName);
            return;
        }

        // Persist the placeholder default: on read it is converted back to null
        targetAuth.setEmail(PlayerAuth.DB_EMAIL_DEFAULT);
        boolean saved = dataSource.updateEmail(targetAuth);
        if (saved) {
            PlayerAuth cachedAuth = playerCache.getAuth(targetName);
            if (cachedAuth != null) {
                cachedAuth.setEmail(null);
                playerCache.updatePlayer(cachedAuth);
            }
            service.send(player, MessageKey.EMAIL_UNBIND_SUCCESS, targetName);
            logger.info("Account '" + targetName + "' has been unbound from the email address of '" + playerName + "'");
        } else {
            logger.warning("Could not unbind account '" + targetName + "' from the email address of '" + playerName + "'");
            service.send(player, MessageKey.ERROR);
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
