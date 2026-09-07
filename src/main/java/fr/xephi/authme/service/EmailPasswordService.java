package fr.xephi.authme.service;

import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.security.crypts.HashedPassword;
import fr.xephi.authme.util.Utils;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Service implementing the v2 account rule that the password follows the email address:
 * the same email may be bound by multiple accounts, and all accounts sharing an email
 * share the same password.
 *
 * <p>Whenever an account binds an email that other accounts already use, the password
 * already associated with that email is adopted. Whenever a password is changed for one
 * account, the new password is propagated to all accounts bound to the same email.</p>
 */
public class EmailPasswordService {

    @Inject
    private DataSource dataSource;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private BukkitService bukkitService;

    @Inject
    private CommonService commonService;

    EmailPasswordService() {
    }

    /**
     * Returns the password hash associated with the given email address, i.e. the password
     * of any account already bound to it. Note that all accounts sharing an email are kept
     * in sync, so any of them yields the same result.
     *
     * @param email the email address to look up
     * @return the password hash of the email, or {@code null} if no account with that
     *         email has a password
     */
    public HashedPassword findPasswordByEmail(String email) {
        if (Utils.isEmailEmpty(email)) {
            return null;
        }
        List<String> names = dataSource.getAllAuthsByEmail(email);
        for (String name : names) {
            HashedPassword password = dataSource.getPassword(name);
            if (password != null && password.getHash() != null && !password.getHash().isEmpty()) {
                return password;
            }
        }
        return null;
    }

    /**
     * Propagates the given password to every account bound to the email address, so that
     * all accounts sharing the email keep the same password. The account of the given
     * player name is skipped (the caller is expected to have persisted it already), and
     * any other affected player currently online is notified.
     *
     * @param email the email address whose accounts should receive the new password
     * @param password the password to set on all accounts bound to the email
     * @param skipName the name of the account to skip, or {@code null} to update all
     */
    public void syncPasswordToEmail(String email, HashedPassword password, String skipName) {
        if (Utils.isEmailEmpty(email)) {
            return;
        }
        String skip = skipName == null ? null : skipName.toLowerCase(Locale.ROOT);
        for (String name : dataSource.getAllAuthsByEmail(email)) {
            if (name.equals(skip)) {
                continue;
            }
            dataSource.updatePassword(name, password);
            PlayerAuth cachedAuth = playerCache.getAuth(name);
            if (cachedAuth != null) {
                cachedAuth.setPassword(password);
                playerCache.updatePlayer(cachedAuth);
            }
            Player onlinePlayer = bukkitService.getPlayerExact(name);
            if (onlinePlayer != null) {
                commonService.send(onlinePlayer, MessageKey.EMAIL_PASSWORD_SYNCED);
            }
        }
    }
}
