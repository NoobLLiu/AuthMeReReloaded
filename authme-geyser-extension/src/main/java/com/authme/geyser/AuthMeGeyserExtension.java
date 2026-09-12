package com.authme.geyser;

import org.geysermc.geyser.api.extension.Extension;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Geyser Extension entry point for AuthMe identity switch support.
 * <p>
 * This extension intercepts Bedrock player logins at the Geyser level and applies pending
 * identity switches written by AuthMe's {@code IdentitySwitchManager}. When a Bedrock player
 * reconnects after initiating an identity switch, this extension modifies the Geyser session's
 * Java-side username and UUID so that Floodgate creates the Player with the target identity.
 * <p>
 * <b>Communication mechanism:</b> AuthMe writes pending switch files to
 * {@code plugins/AuthMe/geyser-pending-switches/{xuid}.properties}. This extension reads
 * and consumes those files on reconnection.
 */
public class AuthMeGeyserExtension implements Extension {

    private static final long CLEANUP_INTERVAL_MINUTES = 2;

    private PendingSwitchStore pendingSwitchStore;
    private IdentitySwitchListener identitySwitchListener;
    private ScheduledExecutorService cleanupScheduler;

    @Override
    public void onEnable() {
        Logger logger = logger();
        Path serverRoot = resolveServerRoot();

        logger.info("AuthMe Geyser Extension: enabling...");

        // Initialize the shared store
        pendingSwitchStore = new PendingSwitchStore(serverRoot, logger);

        // Register the identity switch event listener
        identitySwitchListener = new IdentitySwitchListener(pendingSwitchStore, logger);
        eventBus().register(this, identitySwitchListener);

        // Schedule periodic cleanup of expired switch files
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "authme-geyser-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(
            pendingSwitchStore::cleanupExpired,
            CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES
        );

        logger.info("AuthMe Geyser Extension: enabled. Listening for Bedrock identity switches.");
    }

    @Override
    public void onDisable() {
        Logger logger = logger();
        logger.info("AuthMe Geyser Extension: disabling...");

        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
        }

        logger.info("AuthMe Geyser Extension: disabled.");
    }

    /**
     * Resolves the server root directory. This is the Minecraft server's working directory
     * where plugins/ folder is located.
     */
    private Path resolveServerRoot() {
        // Try to get the server root from the Geyser extension data folder
        // The extension data folder is typically: {server}/extensions/authme-geyser/
        // We need to go up to the server root
        Path extensionDataFolder = dataFolder();
        if (extensionDataFolder != null) {
            // extensionDataFolder = {server}/extensions/authme-geyser/
            // Go up two levels to reach {server}/
            Path extensionsDir = extensionDataFolder.getParent();
            if (extensionsDir != null) {
                Path serverRoot = extensionsDir.getParent();
                if (serverRoot != null) {
                    return serverRoot;
                }
            }
        }

        // Fallback: use current working directory
        return Path.of(System.getProperty("user.dir", "."));
    }
}
