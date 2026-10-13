package team.minefed.regions.server;

import java.util.UUID;

/** Vanilla /execute can replace the entity without replacing the issuing command output. */
public final class ActorIdentity {
    private ActorIdentity() { }
    public static boolean matches(UUID issuer, UUID executingEntity) {
        return issuer != null && issuer.equals(executingEntity);
    }
}
