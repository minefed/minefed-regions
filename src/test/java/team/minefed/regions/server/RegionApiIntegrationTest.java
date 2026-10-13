package team.minefed.regions.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import team.minefed.regions.model.Geometry;
import team.minefed.regions.model.Snapshot;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in wire test against the isolated Go/PostgreSQL validation service, never a remote host. */
@EnabledIfEnvironmentVariable(named="MINEFED_REGIONS_TEST_CONFIG",matches=".+")
class RegionApiIntegrationTest {
    @Test void javaClientUsesRealGoSnapshotMutationsAndIdempotency() throws Exception {
        JsonObject connection=JsonParser.parseString(Files.readString(Path.of(System.getenv("MINEFED_REGIONS_TEST_CONFIG")))).getAsJsonObject();
        ServerConfig config=new ServerConfig();config.apiBaseUrl=connection.get("apiBaseUrl").getAsString();config.worldId=connection.get("worldId").getAsString();
        assertTrue(Set.of("127.0.0.1","localhost","[::1]").contains(URI.create(config.apiBaseUrl).getHost()),"Live integration test requires a loopback service");
        config.validate();RegionApi api=new RegionApi(config,connection.get("serverToken").getAsString());
        String admin=UUID.randomUUID().toString(),country=UUID.randomUUID().toString();
        Snapshot state=api.snapshot();
        JsonObject grant=command("admin-grant",state);grant.addProperty("managerUuid",admin);state=api.command(grant);
        try {
            double x=100000+Math.floor(Math.random()*1000000);
            JsonObject create=command("create",state);create.addProperty("regionId",country);create.addProperty("name","검증 국가 "+country.substring(0,8));
            create.add("geometry",RegionApi.JSON.toJsonTree(new Geometry("MultiPolygon",new double[][][][]{{{{x,x},{x+10,x},{x+10,x+10},{x,x+10},{x,x}}}})));
            state=api.command(create);assertNotNull(state.find(country));
            assertEquals(state.revision(),api.command(create).revision(),"Same requestId must not create twice");
            JsonObject rename=command("rename",state);rename.addProperty("console",false);rename.addProperty("actorUuid",admin);
            rename.addProperty("regionId",country);rename.addProperty("name","새 검증 국가 "+country.substring(0,8));state=api.command(rename);
            assertTrue(state.find(country).name().startsWith("새 검증"));
            JsonObject denied=command("rename",state);denied.addProperty("console",false);denied.addProperty("actorUuid",UUID.randomUUID().toString());
            denied.addProperty("regionId",country);denied.addProperty("name","거부될 이름");
            assertEquals("PERMISSION_DENIED",assertThrows(RegionApi.ApiException.class,()->api.command(denied)).code);
            assertFalse(api.history(0).getAsJsonArray("entries").isEmpty());
            assertTrue(api.history(1).getAsJsonArray("entries").isEmpty());
        } finally {
            state=api.snapshot();
            if(state.find(country)!=null) {JsonObject delete=command("delete",state);delete.addProperty("regionId",country);state=api.command(delete);}
            JsonObject revoke=command("admin-revoke",state);revoke.addProperty("managerUuid",admin);api.command(revoke);
        }
    }
    private static JsonObject command(String operation,Snapshot snapshot) {
        JsonObject command=new JsonObject();command.addProperty("operation",operation);command.addProperty("requestId",UUID.randomUUID().toString());
        command.addProperty("expectedRevision",snapshot.revision());command.addProperty("console",true);return command;
    }
}
