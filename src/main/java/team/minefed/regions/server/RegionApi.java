package team.minefed.regions.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import team.minefed.regions.model.Snapshot;
import team.minefed.regions.model.SnapshotValidator;

/** Called on the dedicated worker only. Neither credentials nor snapshots go to game clients. */
public final class RegionApi {
    public static final Gson JSON = new Gson();
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ServerConfig config;
    private final String token;
    private final Duration timeout;

    public RegionApi(ServerConfig config) {
        this(config, System.getenv(config.tokenEnvironmentVariable));
    }

    RegionApi(ServerConfig config, String token) { this(config,token,Duration.ofSeconds(15)); }
    RegionApi(ServerConfig config, String token, Duration timeout) { this.config = config; this.token = token; this.timeout = timeout; }

    public boolean configured() { return token != null && !token.isBlank(); }
    public Snapshot snapshot() throws IOException, InterruptedException {
        return parseSnapshot(request("snapshot", null));
    }
    public Snapshot command(JsonObject command) throws IOException, InterruptedException {
        return parseSnapshot(request("commands", command));
    }
    public JsonObject history(long beforeRevision) throws IOException, InterruptedException {
        return JsonParser.parseString(request("history?limit=20" + (beforeRevision > 0 ? "&beforeRevision=" + beforeRevision : ""), null)).getAsJsonObject();
    }
    private static Snapshot parseSnapshot(String body) throws IOException {
        try {
            return SnapshotValidator.validate(JSON.fromJson(body, Snapshot.class));
        } catch (RuntimeException exception) { throw new IOException("API returned an invalid region snapshot"); }
    }

    private String request(String resource, JsonObject body) throws IOException, InterruptedException {
        if (!configured()) throw new IOException("API token environment variable is not configured");
        HttpRequest.Builder request = HttpRequest.newBuilder(config.endpoint(resource))
                .timeout(timeout).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        var pending = client.sendAsync(request.build(), info -> new LimitedBodySubscriber(MAX_BODY));
        HttpResponse<byte[]> response;
        try { response = pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch (TimeoutException exception) { pending.cancel(true); throw new IOException("API response timed out"); }
        catch (InterruptedException exception) { pending.cancel(true); throw exception; }
        catch (ExecutionException exception) { throw new IOException("API response could not be read",exception.getCause()); }
        String text = new String(response.body(),java.nio.charset.StandardCharsets.UTF_8);
        if (response.statusCode() != 200) {
            String code = "HTTP_" + response.statusCode();
            try {
                String supplied = JsonParser.parseString(text).getAsJsonObject().get("code").getAsString();
                if (supplied.matches("[A-Z_]{1,40}")) code = supplied;
            } catch (RuntimeException ignored) { }
            throw new ApiException(code, response.statusCode());
        }
        return text;
    }

    public static final class ApiException extends IOException {
        public final String code;
        public final int status;
        ApiException(String code, int status) { super(code); this.code = code; this.status = status; }
    }
}
