package com.authme.geyser;

import org.geysermc.event.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.connection.GeyserClientInitializeEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Listens for Geyser client initialization events and applies pending identity switches
 * for Bedrock players reconnecting after an AuthMe identity switch.
 * <p>
 * When a Bedrock player connects, this listener checks for a pending switch (written by
 * AuthMe's {@code IdentitySwitchManager}). If found, it modifies the Geyser session's
 * username and UUID so that Floodgate creates the Player with the target identity.
 */
public class IdentitySwitchListener {

    private final PendingSwitchStore store;
    private final Logger logger;

    public IdentitySwitchListener(PendingSwitchStore store, Logger logger) {
        this.store = store;
        this.logger = logger;
    }

    @Subscribe
    public void onClientInitialize(GeyserClientInitializeEvent event) {
        try {
            handleClientInitialize(event);
        } catch (Exception e) {
            logger.error("Error in AuthMe identity switch listener: {}", e.getMessage(), e);
        }
    }

    private void handleClientInitialize(GeyserClientInitializeEvent event) {
        GeyserConnection connection = event.connection();
        if (connection == null) {
            return;
        }

        // Extract the XUID from the connection
        String xuid = extractXuid(connection);
        if (xuid == null) {
            return;
        }

        // Check for a pending identity switch
        PendingSwitchStore.PendingSwitchData pending = store.getAndConsume(xuid);
        if (pending == null) {
            return;
        }

        String originalName = safeGetName(connection);
        logger.info("AuthMe identity switch: applying Bedrock switch for XUID '{}' "
            + "(original: '{}', target: '{}', uuid: {})",
            xuid, originalName, pending.getTargetName(), pending.getTargetUuid());

        // Modify the Geyser session to use the target identity
        boolean sessionModified = modifySessionIdentity(connection, pending);

        // Modify the Floodgate player data (if Floodgate is available)
        boolean floodgateModified = modifyFloodgatePlayer(xuid, pending);

        if (sessionModified || floodgateModified) {
            logger.info("AuthMe identity switch: successfully modified Bedrock session "
                + "'{}' -> '{}' (session={}, floodgate={})",
                originalName, pending.getTargetName(), sessionModified, floodgateModified);
        } else {
            logger.warn("AuthMe identity switch: could not modify session or FloodgatePlayer "
                + "for XUID '{}'. The identity switch may not work.", xuid);
        }
    }

    /**
     * Extracts the Xbox User ID from the Geyser connection.
     */
    private String extractXuid(GeyserConnection connection) {
        // Try getting XUID via FloodgateApi first
        try {
            UUID uuid = connection.uuid();
            if (uuid != null) {
                Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object api = floodgateApiClass.getMethod("getInstance").invoke(null);
                Object player = floodgateApiClass.getMethod("getPlayer", UUID.class).invoke(api, uuid);
                if (player != null) {
                    String xuid = (String) player.getClass().getMethod("getXuid").invoke(player);
                    if (xuid != null && !xuid.isEmpty()) {
                        return xuid;
                    }
                }
            }
        } catch (Exception ignored) {
            // Floodgate not available or player not found
        }

        // Try getting XUID from the GeyserSession's internal state
        try {
            Object session = connection;
            // GeyserConnection at runtime is typically a GeyserSession
            Field xuidField = findField(session.getClass(), "xuid", "xboxUid");
            if (xuidField != null) {
                xuidField.setAccessible(true);
                Object xuidValue = xuidField.get(session);
                if (xuidValue instanceof String xuid && !xuid.isEmpty()) {
                    return xuid;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * Modifies the GeyserSession's username and UUID via reflection.
     */
    private boolean modifySessionIdentity(GeyserConnection connection,
                                           PendingSwitchStore.PendingSwitchData data) {
        boolean modified = false;
        try {
            // Modify the username
            Field nameField = findField(connection.getClass(), "username", "name", "javaUsername");
            if (nameField != null) {
                nameField.setAccessible(true);
                nameField.set(connection, data.getTargetName());
                modified = true;
            }

            // Modify the UUID
            Field uuidField = findField(connection.getClass(), "uuid", "javaUuid", "profileId");
            if (uuidField != null) {
                uuidField.setAccessible(true);
                uuidField.set(connection, data.getTargetUuid());
                modified = true;
            }
        } catch (Exception e) {
            logger.warn("Could not modify GeyserSession identity via reflection: {}", e.getMessage());
        }
        return modified;
    }

    /**
     * Modifies the FloodgatePlayer's username and UUID, so that Floodgate's Bukkit-side
     * handler creates the Player with the target identity.
     */
    private boolean modifyFloodgatePlayer(String xuid, PendingSwitchStore.PendingSwitchData data) {
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgateApiClass.getMethod("getInstance").invoke(null);

            // Look up by XUID through iteration of all players
            java.util.Collection<?> players =
                (java.util.Collection<?>) floodgateApiClass.getMethod("getPlayers").invoke(api);
            for (Object player : players) {
                String playerXuid = (String) player.getClass().getMethod("getXuid").invoke(player);
                if (xuid.equals(playerXuid)) {
                    // Found the FloodgatePlayer - modify its fields
                    Field usernameField = findField(player.getClass(), "correctUsername", "username");
                    if (usernameField != null) {
                        usernameField.setAccessible(true);
                        usernameField.set(player, data.getTargetName());
                    }

                    Field uuidField = findField(player.getClass(), "correctUniqueId", "uniqueId", "uuid");
                    if (uuidField != null) {
                        uuidField.setAccessible(true);
                        uuidField.set(player, data.getTargetUuid());
                    }

                    logger.debug("Modified FloodgatePlayer for XUID '{}': name='{}', uuid={}",
                        xuid, data.getTargetName(), data.getTargetUuid());
                    return true;
                }
            }
        } catch (ClassNotFoundException e) {
            // Floodgate is not installed
            logger.debug("Floodgate API not available for player modification");
        } catch (Exception e) {
            logger.warn("Could not modify FloodgatePlayer: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Safely gets the name from a GeyserConnection without throwing.
     */
    private String safeGetName(GeyserConnection connection) {
        try {
            return connection.name();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    /**
     * Searches for a field in the given class and its superclasses by trying multiple names.
     */
    private static Field findField(Class<?> clazz, String... names) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (String name : names) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
