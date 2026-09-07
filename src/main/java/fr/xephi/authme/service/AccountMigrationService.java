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
     * Returns whether the player is currently held in the limbo state for binding an email
     * as part of an account migration.
     *
     * @param player the player to check
     * @return true if the player awaits an email binding
     */
    public boolean isAwaitingEmailBinding(Player player) {
        LimboPlayer limbo = limboService.getLimboPlayer(player.getName());
        return limbo != null && limbo.getState() == LimboPlayerState.EMAIL_REQUIRED;
    }

    /**
     * Intercepts a successful login of an account pending migration. The player remains
     * unauthenticated and is guided to bind an email address before being allowed to play.
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
        if (limbo != null) {
            limbo.setState(LimboPlayerState.EMAIL_REQUIRED);
            limboService.resetMessageTask(player, LimboMessageType.EMAIL_MIGRATION);
            limboService.resetTimeoutTask(player);
        }
        service.send(player, MessageKey.LOGIN_SUCCESS);
        service.send(player, MessageKey.EMAIL_MIGRATION_REQUIRED);
    }

    /**
     * Completes the migration of an account by persisting the newly bound email address and
     * advancing the account's schema version to {@link #TARGET_SCHEMA_VERSION}.
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

        auth.setEmail(email);
        auth.setSchemaVersion(TARGET_SCHEMA_VERSION);
        boolean emailSaved = dataSource.updateEmail(auth);
        boolean versionSaved = dataSource.updateSchemaVersion(auth);
        if (!emailSaved || !versionSaved) {
            logger.warning("Failed to persist email migration for '" + name + "'");
            return null;
        }

        logger.info("Account '" + name + "' has been migrated to schema version " + TARGET_SCHEMA_VERSION);
        service.send(player, MessageKey.EMAIL_MIGRATION_COMPLETE);
        return auth;
    }
}
