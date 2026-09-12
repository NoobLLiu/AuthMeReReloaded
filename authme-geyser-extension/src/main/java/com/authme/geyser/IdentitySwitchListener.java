package com.authme.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Listens for Geyser session login events and applies pending identity switches
 * for Bedrock players reconnecting after an AuthMe identity switch.
 * <p>
 * When a Bedrock player connects, this listener checks for a pending switch (written by
 * AuthMe's {@code IdentitySwitchManager}). If found, it modifies the Geyser session's
 * username and UUID so that Floodgate creates the Player with the target identity.
 */
public class IdentitySwitchListener {

    private final PendingSwitchStore store;
    private final ExtensionLogger logger;

    public IdentitySwitchListener(PendingSwitchStore store, ExtensionLogger logger) {
        this.store = store;
        this.logger = logger;
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        try {
            handleSessionLogin(event);
        } catch (Exception e) {
            logger.error("Error in AuthMe identity switch listener: " + e.getMessage(), e);
        }
    }

    private void handleSessionLogin(SessionLoginEvent event) {
        GeyserConnection connection = event.connection();
        if (connection == null) {
            return;
        }

        // Get the XUID directly from the Connection API
        String xuid = connection.xuid();
        if (xuid == null || xuid.isEmpty()) {
            return;
        }

        // Check for a pending identity switch
        PendingSwitchStore.PendingSwitchData pending = store.getAndConsume(xuid);
        if (pending == null) {
            return;
        }

        String originalName = connection.javaUsername();
        logger.info("AuthMe identity switch: applying Bedrock switch for XUID '"
            + xuid + "' (original: '" + originalName + "', target: '"
            + pending.getTargetName() + "', uuid: " + pending.getTargetUuid() + ")");

        // Modify the Geyser session to use the target identity
        boolean sessionModified = modifySessionIdentity(connection, pending);

        // Modify the Floodgate player data (if Floodgate is available)
        boolean floodgateModified = modifyFloodgatePlayer(connection, xuid, pending);

        if (sessionModified || floodgateModified) {
            logger.info("AuthMe identity switch: successfully modified Bedrock session '"
                + originalName + "' -> '" + pending.getTargetName()
                + "' (session=" + sessionModified + ", floodgate=" + floodgateModified + ")");
        } else {
            logger.warning("AuthMe identity switch: could not modify session or FloodgatePlayer "
                + "for XUID '" + xuid + "'. The identity switch may not work.");
        }
    }

    /**
     * Modifies the GeyserSession's javaUsername and javaUuid via reflection.
     */
    private boolean modifySessionIdentity(GeyserConnection connection,
                                           PendingSwitchStore.PendingSwitchData data) {
        boolean modified = false;
        try {
            // Modify the java username
            Field nameField = findField(connection.getClass(), "javaUsername", "username", "name");
            if (nameField != null) {
                nameField.setAccessible(true);
                nameField.set(connection, data.getTargetName());
                modified = true;
            }

            // Modify the java UUID
            Field uuidField = findField(connection.getClass(), "javaUuid", "uuid", "profileId");
            if (uuidField != null) {
                uuidField.setAccessible(true);
                uuidField.set(connection, data.getTargetUuid());
                modified = true;
            }
        } catch (Exception e) {
            logger.warning("Could not modify GeyserSession identity via reflection: " + e.getMessage());
        }
        return modified;
    }

    /**
     * Modifies the FloodgatePlayer's username and UUID, so that Floodgate's Bukkit-side
     * handler creates the Player with the target identity.
     * <p>
     * Tries multiple strategies to locate the FloodgatePlayer:
     * 1. From the GeyserSession's internal flags/state (fastest, most reliable)
     * 2. From FloodgateApi.getPlayers() by XUID lookup
     */
    private boolean modifyFloodgatePlayer(GeyserConnection connection, String xuid,
                                           PendingSwitchStore.PendingSwitchData data) {
        // Strategy 1: Try to get the FloodgatePlayer from the GeyserSession's internal state
        try {
            Object fgPlayer = findFloodgatePlayerOnSession(connection);
            if (fgPlayer != null) {
                return applyFloodgatePlayerChanges(fgPlayer, data);
            }
        } catch (Exception e) {
            logger.debug("Could not get FloodgatePlayer from session: " + e.getMessage());
        }

        // Strategy 2: Look up by XUID through FloodgateApi.getPlayers()
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgateApiClass.getMethod("getInstance").invoke(null);

            java.util.Collection<?> players =
                (java.util.Collection<?>) floodgateApiClass.getMethod("getPlayers").invoke(api);
            for (Object player : players) {
                String playerXuid = (String) player.getClass().getMethod("getXuid").invoke(player);
                if (xuid.equals(playerXuid)) {
                    return applyFloodgatePlayerChanges(player, data);
                }
            }
            logger.debug("FloodgatePlayer not found in FloodgateApi.getPlayers() for XUID '" + xuid + "'");
        } catch (ClassNotFoundException e) {
            logger.debug("Floodgate API not available for player modification");
        } catch (Exception e) {
            logger.warning("Could not modify FloodgatePlayer via API: " + e.getMessage());
        }
        return false;
    }

    /**
     * Tries to find the FloodgatePlayer stored on the GeyserSession (e.g., as a flag or field).
     */
    private Object findFloodgatePlayerOnSession(GeyserConnection connection) {
        // Try session flags (common pattern in Geyser extensions)
        try {
            Method getFlagMethod = findMethod(connection.getClass(), "getFlag");
            if (getFlagMethod != null) {
                getFlagMethod.setAccessible(true);
                // Try common flag names used by Floodgate
                for (String flagName : new String[]{"floodgate_player", "floodgate-player", "floodgatePlayer"}) {
                    try {
                        Object player = getFlagMethod.invoke(connection, flagName);
                        if (player != null) {
                            logger.debug("Found FloodgatePlayer via session flag '" + flagName + "'");
                            return player;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Try direct field on the session
        try {
            Field fgField = findField(connection.getClass(), "floodgatePlayer", "floodgateData", "floodgate");
            if (fgField != null) {
                fgField.setAccessible(true);
                Object player = fgField.get(connection);
                if (player != null) {
                    logger.debug("Found FloodgatePlayer via session field");
                    return player;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * Applies name and UUID changes to a FloodgatePlayer instance via reflection.
     */
    private boolean applyFloodgatePlayerChanges(Object fgPlayer,
                                                  PendingSwitchStore.PendingSwitchData data) {
        try {
            Field usernameField = findField(fgPlayer.getClass(), "correctUsername", "username");
            if (usernameField != null) {
                usernameField.setAccessible(true);
                usernameField.set(fgPlayer, data.getTargetName());
            }

            Field uuidField = findField(fgPlayer.getClass(), "correctUniqueId", "uniqueId", "uuid");
            if (uuidField != null) {
                uuidField.setAccessible(true);
                uuidField.set(fgPlayer, data.getTargetUuid());
            }

            logger.debug("Modified FloodgatePlayer: name='" + data.getTargetName()
                + "', uuid=" + data.getTargetUuid());
            return true;
        } catch (Exception e) {
            logger.warning("Could not modify FloodgatePlayer fields: " + e.getMessage());
            return false;
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

    /**
     * Searches for a method in the given class and its superclasses by name.
     */
    private static Method findMethod(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
