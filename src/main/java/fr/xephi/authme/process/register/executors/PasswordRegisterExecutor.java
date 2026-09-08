package fr.xephi.authme.process.register.executors;

import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.service.EmailPasswordService;

import javax.inject.Inject;

import static fr.xephi.authme.process.register.executors.PlayerAuthBuilderHelper.createPlayerAuth;

/**
 * Registration executor for password registration.
 */
class PasswordRegisterExecutor extends AbstractPasswordRegisterExecutor<PasswordRegisterParams> {

    @Inject
    private EmailPasswordService emailPasswordService;

    @Override
    public synchronized PlayerAuth createPlayerAuthObject(PasswordRegisterParams params) {
        return createPlayerAuth(params.getPlayer(), params.getHashedPassword(), params.getEmail());
    }

    @Override
    public void executePostPersistAction(PasswordRegisterParams params) {
        super.executePostPersistAction(params);
        // The password follows the email: keep every account bound to the email in sync
        emailPasswordService.syncPasswordToEmail(params.getEmail(), params.getHashedPassword(),
            params.getPlayerName());
    }

}
