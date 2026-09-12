package fr.xephi.authme.service;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.limbo.LimboMessageType;
import fr.xephi.authme.data.limbo.LimboPlayer;
import fr.xephi.authme.data.limbo.LimboPlayerState;
import fr.xephi.authme.data.limbo.LimboService;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.security.PasswordSecurity;
import fr.xephi.authme.security.crypts.HashedPassword;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.Locale;

/**
 * Handles migrations of the account schema. Each account stores a schema version; accounts
 * from before this system exists (v1 accounts) have no version and are migrated on their
 * first successful login after this plugin version is installed.
 * <p>
 * To add a future migration (e.g. v2 to v3), increase {@link #TARGET_SCHEMA_VERSION} and
 * extend {@link #handlePendingMigration} with the steps required to bring an account of an
 * older version up to date.
 * <p>
 * Since v2 the password follows the email address: all accounts bound to the same email
 * share the same password (see {@link EmailPasswordService}). Consequently a v1 account
 * migrating to v2 with a fresh email must set a new password, while an email that already
 * has a password is adopted as is.
 */
public class AccountMigrationService {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AccountMigrationService.class);

    /** The account schema version written by this plugin version. */
    public static final int TARGET_SCHEMA_VERSION = 2;

    @Inject
    private DataSource dataSource;

    @Inject
    private CommonService service;

    @Inject
    private LimboService limboService;

    @Inject
    private EmailPasswordService emailPasswordService;

    @Inject
    private PasswordSecurity passwordSecurity;

    AccountMigrationService() {
    }

    /**
     * Returns whether the given account still needs to be migrated to the current schema version.
     *
     * @param auth the account to check
     * @return true if the account requires a migration, false otherwise
     */
    public boolean isMigrationPending(PlayerAuth auth) {
        return auth.getSchemaVersion() == null || auth.getSchemaVersion() < TARGET_SCHEMA_VERSION;
    }

    /**
     * Returns whether the player is currently awaiting the binding of an email address
     * for the account migration (i.e. the email binding step, before any password step).
     *
     * @param player the player to check
     * @return true if the player must still bind an email address
     */
    public boolean isAwaitingEmailBinding(Player player) {
        LimboPlayer limbo = limboService.getLimboPlayer(player.getName());
        return limbo != null && limbo.getState() == LimboPlayerState.EMAIL_REQUIRED;
    }

    /**
     * Returns whether the player is currently held in the limbo state for an account
     * migration, either awaiting the email binding or the new password afterwards.
     *
     * @param player the player to check
     * @return true if the player is in the email migration limbo
     */
    public boolean isInMigrationLimbo(Player player) {
        LimboPlayer limbo = limboService.getLimboPlayer(player.getName());
        return limbo != null && (limbo.getState() == LimboPlayerState.EMAIL_REQUIRED
            || limbo.getState() == LimboPlayerState.MIGRATION_PASSWORD_REQUIRED);
    }

    /**
     * Returns whether the player has confirmed a migration email and now only needs to set
     * the new password to complete the migration.
     *
     * @param player the player to check
     * @return true if the player awaits the migration password
     */
    public boolean isAwaitingPasswordSet(Player player) {
        LimboPlayer limbo = limboService.getLimboPlayer(player.getName());
        return limbo != null && limbo.getState() == LimboPlayerState.MIGRATION_PASSWORD_REQUIRED;
    }

    /**
     * Intercepts a successful login of an account pending migration. The player remains
     * unauthenticated and is guided to bind an email address before being allowed to play.
     * If the player had already confirmed an email but not yet set the new password, the
     * password step is kept as the current goal.
     *
     * @param player the player whose login is intercepted
     * @param auth the account pending migration
     */
    public void handlePendingMigration(Player player, PlayerAuth auth) {
        String name = player.getName().toLowerCase(Locale.ROOT);
        logger.info("Requiring email binding to migrate account '" + name + "' from schema version "
            + auth.getSchemaVersion() + " to " + TARGET_SCHEMA_VERSION);

        LimboPlayer limbo = limboService.getLimboPlayer(name);
        if (limbo == null) {
            // Resumed sessions perform the login before a limbo player is created upon joining
            limboService.createLimboPlayer(player, true);
            limbo = limboService.getLimboPlayer(name);
        }
        boolean passwordPending = limbo != null && limbo.getState() == LimboPlayerState.MIGRATION_PASSWORD_REQUIRED;
        if (limbo != null) {
            limbo.setState(passwordPending
                ? LimboPlayerState.MIGRATION_PASSWORD_REQUIRED : LimboPlayerState.EMAIL_REQUIRED);
            limboService.resetMessageTask(player,
                passwordPending ? LimboMessageType.PASSWORD_MIGRATION : LimboMessageType.EMAIL_MIGRATION);
            limboService.resetTimeoutTask(player);
        }
        service.send(player, MessageKey.LOGIN_SUCCESS);
        service.send(player, passwordPending
            ? MessageKey.EMAIL_MIGRATION_PASSWORD_REQUIRED : MessageKey.EMAIL_MIGRATION_REQUIRED);
    }

    /**
     * Handles a migration email whose verification code was confirmed correctly. If the
     * email already has a password (other accounts are bound to it), the password is
     * adopted and the migration completes immediately; otherwise the player is asked to
     * set a new password to finish the migration.
     *
     * @param player the player whose migration email was confirmed
     * @param email the confirmed email address
     * @return the migrated PlayerAuth upon immediate completion, or {@code null} if the
     *         player must still set a new password
     */
    public PlayerAuth handleMigrationEmailConfirmed(Player player, String email) {
        HashedPassword emailPassword = emailPasswordService.findPasswordByEmail(email);
        if (emailPassword != null) {
            // The email already has a password: adopt it, no new password needed
            PlayerAuth auth = completeEmailMigration(player, email);
            if (auth == null) {
                service.send(player, MessageKey.ERROR);
            }
            return auth;
        }

        // Fresh email: the migration completes once a new password has been set.
        // The email stays in the pending change cache until then.
        LimboPlayer limbo = limboService.getLimboPlayer(player.getName());
        if (limbo != null) {
            limbo.setState(LimboPlayerState.MIGRATION_PASSWORD_REQUIRED);
            limboService.resetMessageTask(player, LimboMessageType.PASSWORD_MIGRATION);
            limboService.resetTimeoutTask(player);
        }
        service.send(player, MessageKey.EMAIL_MIGRATION_PASSWORD_REQUIRED);
        return null;
    }

    /**
     * Completes the migration of an account by persisting the newly bound email address and
     * advancing the account's schema version to {@link #TARGET_SCHEMA_VERSION}. The
     * password of the email is adopted if the email is already in use by other accounts.
     *
     * @param player the player whose account is migrated
     * @param email the email address to bind to the account
     * @return the updated PlayerAuth upon success, or null if the migration could not be persisted
     */
    public PlayerAuth completeEmailMigration(Player player, String email) {
        String name = player.getName().toLowerCase(Locale.ROOT);
        PlayerAuth auth = dataSource.getAuth(name);
        if (auth == null) {
            logger.warning("Cannot complete email migration: no account found for '" + name + "'");
            return null;
        }

        // Look up the email's password before re-binding this account: other accounts
        // bound to the email may already have a password to adopt
        HashedPassword emailPassword = emailPasswordService.findPasswordByEmail(email);

        auth.setEmail(email);
        auth.setUuid(player.getUniqueId());
        auth.setSchemaVersion(TARGET_SCHEMA_VERSION);
        boolean emailSaved = dataSource.updateEmail(auth);
        boolean versionSaved = dataSource.updateSchemaVersion(auth);
        if (!emailSaved || !versionSaved) {
            logger.warning("Failed to persist email migration for '" + name + "'");
            return null;
        }
        // Record the UUID the account logs in with, so the identity switch feature can
        // hand out the account's own UUID instead of a regenerated one
        if (!dataSource.updateUuid(auth)) {
            logger.warning("Failed to save the UUID of the migrated account '" + name + "'");
        }

        if (emailPassword != null) {
            // The password follows the email: adopt the email's existing password
            auth.setPassword(emailPassword);
            if (dataSource.updatePassword(auth)) {
                emailPasswordService.syncPasswordToEmail(email, emailPassword, name);
                service.send(player, MessageKey.EMAIL_PASSWORD_ADOPTED);
            }
        }

        logger.info("Account '" + name + "' has been migrated to schema version " + TARGET_SCHEMA_VERSION);
        service.send(player, MessageKey.EMAIL_MIGRATION_COMPLETE);
        return auth;
    }

    /**
     * Completes the migration of an account by persisting the confirmed email address, the
     * newly chosen password and the current schema version. The new password is propagated
     * to every account bound to the email.
     *
     * @param player the player whose account is migrated
     * @param email the email address confirmed by the player
     * @param password the new password (in clear text)
     * @return the updated PlayerAuth upon success, or null if the migration could not be persisted
     */
    public PlayerAuth completePasswordMigration(Player player, String email, String password) {
        String name = player.getName().toLowerCase(Locale.ROOT);
        PlayerAuth auth = dataSource.getAuth(name);
        if (auth == null) {
            logger.warning("Cannot complete password migration: no account found for '" + name + "'");
            return null;
        }

        HashedPassword hashedPassword = passwordSecurity.computeHash(password, name);
        auth.setEmail(email);
        auth.setPassword(hashedPassword);
        auth.setUuid(player.getUniqueId());
        auth.setSchemaVersion(TARGET_SCHEMA_VERSION);

        boolean emailSaved = dataSource.updateEmail(auth);
        boolean passwordSaved = dataSource.updatePassword(auth);
        boolean versionSaved = dataSource.updateSchemaVersion(auth);
        if (!emailSaved || !passwordSaved || !versionSaved) {
            logger.warning("Failed to persist password migration for '" + name + "'");
            return null;
        }
        // Record the UUID the account logs in with, so the identity switch feature can
        // hand out the account's own UUID instead of a regenerated one
        if (!dataSource.updateUuid(auth)) {
            logger.warning("Failed to save the UUID of the migrated account '" + name + "'");
        }
        // The password follows the email: propagate it to the other accounts bound to it
        emailPasswordService.syncPasswordToEmail(email, hashedPassword, name);

        logger.info("Account '" + name + "' has been migrated to schema version " + TARGET_SCHEMA_VERSION);
        service.send(player, MessageKey.EMAIL_MIGRATION_COMPLETE);
        service.send(player, MessageKey.PASSWORD_CHANGED_SUCCESS);
        return auth;
    }
}
