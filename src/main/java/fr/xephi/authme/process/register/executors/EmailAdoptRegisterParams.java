package fr.xephi.authme.process.register.executors;

import fr.xephi.authme.security.crypts.HashedPassword;
import org.bukkit.entity.Player;

/**
 * Parameters for registering an account with an email address whose password is
 * adopted from the other accounts already bound to that email (v2 rule: the
 * password follows the email address).
 */
public class EmailAdoptRegisterParams extends RegistrationParameters {

    private final String email;
    private final HashedPassword hashedPassword;

    protected EmailAdoptRegisterParams(Player player, String email, HashedPassword hashedPassword) {
        super(player);
        this.email = email;
        this.hashedPassword = hashedPassword;
    }

    /**
     * Creates a params object.
     *
     * @param player the player to register
     * @param email the confirmed email address to bind to the new account
     * @param hashedPassword the password hash of the email (adopted by the new account)
     * @return params object with the given data
     */
    public static EmailAdoptRegisterParams of(Player player, String email, HashedPassword hashedPassword) {
        return new EmailAdoptRegisterParams(player, email, hashedPassword);
    }

    public String getEmail() {
        return email;
    }

    public HashedPassword getHashedPassword() {
        return hashedPassword;
    }
}
