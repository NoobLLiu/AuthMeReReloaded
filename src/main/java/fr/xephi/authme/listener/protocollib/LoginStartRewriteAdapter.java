package fr.xephi.authme.listener.protocollib;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import fr.xephi.authme.AuthMe;
import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.identity.IdentitySwitchManager;
import fr.xephi.authme.identity.PendingSwitch;
import fr.xephi.authme.listener.PreLoginIdentityListener;
import fr.xephi.authme.output.ConsoleLoggerFactory;

import java.util.Locale;

/**
 * Fallback identity rewriting for servers without the Paper profile API: rewrites the name
 * (and UUID, if the packet provides one) of the client's login start packet so that the
 * server derives the target identity for the reconnecting player.
 * <p>
 * Only used when {@link PreLoginIdentityListener#isPaperProfileSupported()} is false.
 */
class LoginStartRewriteAdapter extends PacketAdapter {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(LoginStartRewriteAdapter.class);
    private final IdentitySwitchManager identitySwitchManager;

    LoginStartRewriteAdapter(AuthMe plugin, IdentitySwitchManager identitySwitchManager) {
        // MONITOR priority: run after Floodgate's adapter so we rewrite the already-transformed packet
        super(plugin, ListenerPriority.MONITOR, PacketType.Login.Client.START);
        this.identitySwitchManager = identitySwitchManager;
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        if (event.getPacketType() != PacketType.Login.Client.START) {
            return;
        }
        try {
            PacketContainer packet = event.getPacket();
            String clientName = readLoginName(packet);
            if (clientName == null || clientName.isEmpty()) {
                return;
            }

            String sourceLower = clientName.toLowerCase(Locale.ROOT);
            PendingSwitch pending = identitySwitchManager.getPendingSwitch(sourceLower);
            if (pending == null) {
                return;
            }

            String ip = resolveIp(event);
            if (ip != null && (pending.getIp() == null || !pending.getIp().equals(ip))) {
                // Different address than the one the switch was initiated from
                return;
            }

            if (identitySwitchManager.isOnline(pending.getTargetRealName())) {
                identitySwitchManager.removePendingSwitch(sourceLower);
                logger.warning("Identity switch to '" + pending.getTargetRealName()
                    + "' aborted: the target is already online");
                return;
            }

            if (writeLoginIdentity(packet, pending)) {
                // Don't consume the PendingSwitch or mark auto-login here: the final
                // Player identity is only resolved after the AsyncPlayerPreLoginEvent
                // and PlayerJoinEvent, where the actual consumption is handled.
                logger.info(String.format("Rewrote login packet identity: '%s' -> '%s'",
                    clientName, pending.getTargetRealName()));
            }
        } catch (Exception e) {
            logger.logException("Error while rewriting the login packet", e);
        }
    }

    /**
     * Reads the player name from the login start packet.
     *
     * @param packet the login start packet
     * @return the client's player name, or null if it could not be read
     */
    private String readLoginName(PacketContainer packet) {
        // MC 1.20.2+: the packet carries a profile (uuid + name)
        try {
            WrappedGameProfile profile = packet.getGameProfiles().readSafely(0);
            if (profile != null) {
                return profile.getName();
            }
        } catch (Exception ignored) {
        }
        // Older versions: plain username field
        return packet.getStrings().readSafely(0);
    }

    /**
     * Writes the target identity into the login start packet.
     *
     * @param packet the login start packet
     * @param pending the pending switch to apply
     * @return true if the identity could be written
     */
    private boolean writeLoginIdentity(PacketContainer packet, PendingSwitch pending) {
        // MC 1.20.2+: rewrite the whole profile (name + uuid)
        try {
            WrappedGameProfile profile = packet.getGameProfiles().readSafely(0);
            if (profile != null) {
                packet.getGameProfiles().write(0,
                    new WrappedGameProfile(pending.getTargetUuid(), pending.getTargetRealName()));
                return true;
            }
        } catch (Exception e) {
            logger.warning("Could not write the login profile: " + e.getMessage());
        }

        // Bedrock identities require an explicit UUID, which only the profile provides
        if (pending.isBedrockTarget()) {
            logger.warning("Cannot switch to the Bedrock identity '" + pending.getTargetRealName()
                + "' on this server software: the login packet cannot carry the required UUID");
            return false;
        }

        // Older versions: rewrite the username; the server derives the offline UUID from it
        try {
            packet.getStrings().write(0, pending.getTargetRealName());
            // Best effort: also write the UUID if the packet has such a field
            try {
                packet.getUUIDs().write(0, pending.getTargetUuid());
            } catch (Exception ignored) {
            }
            return true;
        } catch (Exception e) {
            logger.logException("Could not rewrite the login packet", e);
            return false;
        }
    }

    /**
     * Resolves the IP address the connection originates from.
     *
     * @param event the packet event of the login start packet
     * @return the IP address, or null if unavailable
     */
    private String resolveIp(PacketEvent event) {
        try {
            if (event.getPlayer() != null && event.getPlayer().getAddress() != null
                && event.getPlayer().getAddress().getAddress() != null) {
                return event.getPlayer().getAddress().getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }
}
