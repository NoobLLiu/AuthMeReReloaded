package fr.xephi.authme.service;

import fr.xephi.authme.initialization.HasCleanup;
import fr.xephi.authme.util.expiring.ExpiringMap;

import javax.inject.Inject;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * In-memory cache for the two-phase v2 registration flow.
 *
 * <p>Phase 1: the player issues {@code /register <email>}; the email address and a
 * verification code are stored here and the code is sent to the given address.
 * The player confirms the code with {@code /email confirm <code>}. Phase 2: the
 * player sets a password with {@code /register <password> <confirmPassword>}.</p>
 */
public class PendingRegistrationCache implements HasCleanup {

    /** Default TTL: 10 minutes. */
    private static final long DEFAULT_TTL_MINUTES = 10;

    private final ExpiringMap<String, PendingRegistration> pendingRegistrations;

    @Inject
    PendingRegistrationCache() {
        pendingRegistrations = new ExpiringMap<>(DEFAULT_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Stores a pending registration for the given player.
     *
     * @param playerName the player's name (case-insensitive, stored lowercased)
     * @param email the email address to bind to the new account
     * @param code the verification code sent to the email address
     */
    public void put(String playerName, String email, String code) {
        pendingRegistrations.put(playerName.toLowerCase(Locale.ROOT), new PendingRegistration(email, code));
    }

    /**
     * Returns the pending registration for the given player, or {@code null}
     * if none exists or it has expired.
     *
     * @param playerName the player's name
     * @return the pending registration, or {@code null}
     */
    public PendingRegistration get(String playerName) {
        return pendingRegistrations.get(playerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Removes the pending registration for the given player, if any.
     *
     * @param playerName the player's name
     */
    public void remove(String playerName) {
        pendingRegistrations.remove(playerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns whether a pending registration exists for the given player.
     *
     * @param playerName the player's name
     * @return true if a non-expired pending registration exists
     */
    public boolean has(String playerName) {
        return get(playerName) != null;
    }

    /**
     * Returns and removes the pending registration for the given player. Used when
     * completing the registration so the email cannot be confirmed twice.
     *
     * @param playerName the player's name
     * @return the pending registration, or {@code null} if none exists or it has expired
     */
    public PendingRegistration take(String playerName) {
        String lowerName = playerName.toLowerCase(Locale.ROOT);
        PendingRegistration pending = pendingRegistrations.get(lowerName);
        pendingRegistrations.remove(lowerName);
        return pending;
    }

    @Override
    public void performCleanup() {
        pendingRegistrations.removeExpiredEntries();
    }

    /**
     * Holds the pending email address, its verification code and the confirmation status.
     */
    public static final class PendingRegistration {
        private final String email;
        private final String code;
        private boolean verified;

        PendingRegistration(String email, String code) {
            this.email = email;
            this.code = code;
        }

        public String getEmail() {
            return email;
        }

        public String getCode() {
            return code;
        }

        public boolean isVerified() {
            return verified;
        }

        public void setVerified(boolean verified) {
            this.verified = verified;
        }
    }
}
