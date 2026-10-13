package team.minefed.regions.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @Test void rejectsTokenExposingUrlsAndWorldPathInjection() throws Exception {
        ServerConfig config=new ServerConfig();config.validate();
        config.apiBaseUrl="http://api.example.com";assertThrows(java.io.IOException.class,config::validate);
        config.apiBaseUrl="https://secret@api.example.com";assertThrows(java.io.IOException.class,config::validate);
        config.apiBaseUrl="https://api.example.com";config.validate();
        config.worldId="../other";assertThrows(java.io.IOException.class,config::validate);
    }
}
