package team.minefed.regions.server;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ActorIdentityTest {
    @Test void executingAsAdministratorDoesNotChangeActualIssuer() {
        UUID operator=UUID.randomUUID(), administrator=UUID.randomUUID();
        assertFalse(ActorIdentity.matches(operator,administrator),"/execute as an administrator must not inherit its authority");
        assertFalse(ActorIdentity.matches(null,administrator),"Command block output is not a player");
        assertFalse(ActorIdentity.matches(administrator,null));
        assertTrue(ActorIdentity.matches(administrator,administrator));
    }
}
