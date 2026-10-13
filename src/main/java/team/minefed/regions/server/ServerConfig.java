package team.minefed.regions.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class ServerConfig {
    public String apiBaseUrl = "http://127.0.0.1:8091";
    public String worldId = "minefed-overworld";
    public String tokenEnvironmentVariable = "MINEFED_REGIONS_TOKEN";
    public int pollSeconds = 5;

    public static ServerConfig load(Path path) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(new ServerConfig()));
        }
        ServerConfig config = gson.fromJson(Files.readString(path), ServerConfig.class);
        if (config == null) throw new IOException("Empty regions server configuration");
        config.validate();
        return config;
    }

    public URI endpoint(String resource) {
        return URI.create(apiBaseUrl.replaceAll("/+$", "") + "/v1/regions/" + worldId + "/" + resource);
    }

    public void validate() throws IOException {
        try {
            URI uri = URI.create(apiBaseUrl);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
            if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            if (!worldId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}") || pollSeconds < 1 || pollSeconds > 300
                    || tokenEnvironmentVariable == null || tokenEnvironmentVariable.isBlank()) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IOException("Invalid regions configuration: HTTPS required except loopback; worldId and poll interval must be valid");
        }
    }
}
