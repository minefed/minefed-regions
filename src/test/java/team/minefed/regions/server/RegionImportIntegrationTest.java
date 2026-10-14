package team.minefed.regions.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.rcon.RconCommandOutput;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import team.minefed.regions.RegionsMod;
import team.minefed.regions.mixin.ServerCommandSourceAccessor;
import team.minefed.regions.model.Geometry;
import team.minefed.regions.model.Region;
import team.minefed.regions.model.Snapshot;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises command parsing, the real worker, and wire requests against loopback only. */
class RegionImportIntegrationTest {
    private static final String PARENT = "11111111-1111-4111-8111-111111111111";
    private static final String CHILD = "22222222-2222-4222-8222-222222222222";
    private static final String MANAGER = "33333333-3333-4333-8333-333333333333";
    private static final String INJECTED = "44444444-4444-4444-8444-444444444444";
    private static final String FILE = "country.geojson";
    private static final String INPUT = """
            {"type":"MultiPolygon","coordinates":[
              [[[0,0],[10,0],[10,10],[0,10],[0,0]],[[2,2],[2,3],[3,3],[3,2],[2,2]]],
              [[[20.125,-20],[30,-20],[30,-10],[20.125,-10],[20.125,-20]]]
            ],"regionId":"44444444-4444-4444-8444-444444444444","name":"injected",
            "actorUuid":"33333333-3333-4333-8333-333333333333","console":false,
            "managers":["33333333-3333-4333-8333-333333333333"],"displayLevel":63}
            """;
    @TempDir Path temporary;

    @BeforeAll static void bootstrapMinecraftRegistries() {
        // Plain JUnit does not run the server entry point before loading player entity classes.
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test void consoleAndRconAreAllowedButPlayerCommandBlockAndExecuteAsAreDenied() throws Exception {
        try (Fixture fixture = new Fixture(false, Duration.ofSeconds(2))) {
            ServerPlayerEntity player = mock(ServerPlayerEntity.class);
            List<Source> allowed = List.of(fixture.source(fixture.server, null, true),
                    fixture.source(mock(RconCommandOutput.class), null, true));
            List<Source> denied = List.of(fixture.source(player, player, false),
                    fixture.source(player, player, true),
                    fixture.source(mock(CommandOutput.class), null, true),
                    fixture.source(fixture.server, player, true),
                    fixture.source(fixture.server, null, false),
                    fixture.source(mock(RconCommandOutput.class), player, true));
            for (Source source : allowed) {
                assertTrue(RegionController.console(source.command));
                assertEquals(1, fixture.dispatcher.execute("region import validate " + FILE, source.command));
                await(() -> source.contains("파일 구조 검증 통과"));
                verify(source.command, never()).getPlayerOrThrow();
            }
            for (Source source : denied) {
                assertFalse(RegionController.console(source.command));
                assertThrows(CommandSyntaxException.class,
                        () -> fixture.dispatcher.execute("region import validate " + FILE, source.command));
                assertEquals(0, fixture.controller.validateImport(source.command, FILE));
                assertEquals(0, fixture.controller.createImport(source.command, "root", FILE, "Denied"));
                assertEquals(0, fixture.controller.boundaryImport(source.command, CHILD, FILE));
                verify(source.command, never()).getPlayerOrThrow();
            }
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test void validateWorksOfflineAndReportsStructureWithoutMutatingAnything() throws Exception {
        try (Fixture fixture = new Fixture(false, Duration.ofSeconds(2))) {
            Source source = fixture.console();
            assertFalse(fixture.controller.online());
            assertEquals(1, fixture.dispatcher.execute("region import validate " + FILE, source.command));
            await(() -> source.contains("파일 구조 검증 통과"));
            String messages = String.join("\n", source.messages);
            assertTrue(messages.contains(FILE));
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(INPUT.getBytes(StandardCharsets.UTF_8)));
            assertTrue(messages.contains(sha));
            assertTrue(messages.contains("영역 2개"));
            assertTrue(messages.contains("고리 3개"));
            assertTrue(messages.contains("포함 정점 15개"));
            assertTrue(messages.contains("제외 정점 12개"));
            assertFalse(messages.contains("위상 검증 완료"));
            assertEquals(Snapshot.empty(), fixture.controller.snapshot());
            assertFalse(Files.exists(fixture.cache));
            assertTrue(fixture.published.isEmpty());
            assertTrue(fixture.requests.isEmpty());
            verify(source.command, never()).getPlayerOrThrow();
        }
    }

    @Test void rootCreationUsesFreshIdentityWhitelistedFieldsAndFinalPersistenceAcknowledgement() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            Source source = fixture.console();
            fixture.holdCommands = true;
            assertEquals(1, fixture.dispatcher.execute("region import create root " + FILE + " 새 국가 이름", source.command));
            assertTrue(fixture.commandStarted.await(3, TimeUnit.SECONDS));
            assertFalse(source.contains("저장했습니다"), "Acceptance must not claim that saving has finished");
            JsonObject body = fixture.requests.get(0).body;
            assertEquals(Set.of("operation", "regionId", "parentId", "name", "geometry", "requestId", "expectedRevision", "console"), body.keySet());
            assertEquals("create", body.get("operation").getAsString());
            String id = body.get("regionId").getAsString();
            assertEquals(id, UUID.fromString(id).toString());
            assertNotEquals(INJECTED, id);
            assertNotEquals(id, body.get("requestId").getAsString());
            assertEquals(body.get("requestId").getAsString(), UUID.fromString(body.get("requestId").getAsString()).toString());
            assertTrue(body.get("parentId").isJsonNull());
            assertEquals("새 국가 이름", body.get("name").getAsString());
            assertEquals(7, body.get("expectedRevision").getAsLong());
            assertTrue(body.get("console").getAsBoolean());
            assertGeometry(body);
            assertEquals("Bearer local-import-test-token", fixture.requests.get(0).authorization);
            assertEquals("/v1/regions/import-test-world/commands", fixture.requests.get(0).path);
            fixture.releaseCommands.countDown();
            await(() -> source.contains(id) && source.contains("버전 8"));
            assertEquals("새 국가 이름", fixture.controller.snapshot().find(id).name());
            assertEquals(8, fixture.controller.snapshot().revision());
            assertEquals(8, fixture.published.get(fixture.published.size() - 1).revision());
            JsonObject cached = JsonParser.parseString(Files.readString(fixture.cache)).getAsJsonObject();
            assertEquals("import-test-world", cached.get("worldId").getAsString());
            assertEquals(8, cached.getAsJsonObject("snapshot").get("revision").getAsLong());
            assertEquals(1, fixture.requests.size());
            verify(source.command, never()).getPlayerOrThrow();
        }
    }

    @Test void rconCreatesChildWithExplicitParentAndSpacedName() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            Source source = fixture.source(mock(RconCommandOutput.class), null, true);
            assertEquals(1, fixture.dispatcher.execute("region import create " + PARENT + " " + FILE + " 새 하위 지역", source.command));
            await(() -> fixture.controller.snapshot().revision() == 8);
            JsonObject body = fixture.requests.get(0).body;
            assertEquals(PARENT, body.get("parentId").getAsString());
            assertEquals("새 하위 지역", body.get("name").getAsString());
            assertTrue(body.get("console").getAsBoolean());
            assertFalse(body.has("actorUuid"));
            await(() -> source.contains(body.get("regionId").getAsString()) && source.contains("버전 8"));
        }
    }

    @Test void boundaryReplacesWholeGeometryAndPreservesAllRegionMetadata() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            Source source = fixture.console();
            Region before = fixture.controller.snapshot().find(CHILD);
            assertEquals(1, fixture.dispatcher.execute("region import boundary " + CHILD + " " + FILE, source.command));
            await(() -> source.contains(CHILD) && source.contains("버전 8"));
            JsonObject body = fixture.requests.get(0).body;
            assertEquals(Set.of("operation", "regionId", "geometry", "requestId", "expectedRevision", "console"), body.keySet());
            assertEquals("geometry", body.get("operation").getAsString());
            assertEquals(CHILD, body.get("regionId").getAsString());
            assertEquals(7, body.get("expectedRevision").getAsLong());
            assertGeometry(body);
            Region after = fixture.controller.snapshot().find(CHILD);
            assertEquals(before.id(), after.id());
            assertEquals(before.name(), after.name());
            assertEquals(before.parentId(), after.parentId());
            assertEquals(before.managers(), after.managers());
            assertEquals(before.displayLevel(), after.displayLevel());
            assertEquals(2, after.geometry().coordinates().length);
            assertEquals(2, after.geometry().coordinates()[0].length);
            assertEquals(body.get("geometry"), RegionApi.JSON.toJsonTree(after.geometry()));
            verify(source.command, never()).getPlayerOrThrow();
        }
    }

    @Test void missingTargetsInvalidNamesAndOfflineMutationsNeverReachApi() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            Source source = fixture.console();
            assertEquals(1, fixture.dispatcher.execute("region import boundary " + INJECTED + " " + FILE, source.command));
            assertEquals(1, fixture.dispatcher.execute("region import create " + INJECTED + " " + FILE + " Unknown parent", source.command));
            assertEquals(1, fixture.dispatcher.execute("region import create root " + FILE + " " + "x".repeat(81), source.command));
            assertEquals(1, fixture.dispatcher.execute("region import boundary here " + FILE, source.command));
            assertEquals(1, fixture.dispatcher.execute("region import create root " + FILE + " Existing country", source.command));
            await(() -> source.messages.stream().filter(message -> message.contains("파일 가져오기 실패")).count() == 5);
            assertTrue(fixture.requests.isEmpty());
            assertEquals(7, fixture.controller.snapshot().revision());
        }
        try (Fixture fixture = new Fixture(false, Duration.ofSeconds(2))) {
            Source source = fixture.console();
            assertEquals(0, fixture.controller.createImport(source.command, "root", FILE, "Offline"));
            assertEquals(0, fixture.controller.boundaryImport(source.command, CHILD, FILE));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test void revisionConflictRefreshesSnapshotWithoutRetryingOrOverwriting() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            Source source = fixture.console();
            fixture.errorCode = "REVISION_CONFLICT";
            fixture.errorStatus = 409;
            assertEquals(1, fixture.controller.createImport(source.command, "root", FILE, "Conflicting country"));
            await(() -> source.contains("다른 변경"));
            await(() -> fixture.controller.snapshot().revision() == 8);
            assertEquals(1, fixture.requests.size());
            assertEquals(7, fixture.requests.get(0).body.get("expectedRevision").getAsLong());
            assertEquals(2, fixture.controller.snapshot().regions().size());
            assertFalse(source.contains("저장했습니다"));
        }
    }

    @Test void apiRejectionDoesNotReplaceExistingRegionOrRetryCreate() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            Source source = fixture.console();
            fixture.errorCode = "CONFLICT";
            fixture.errorStatus = 409;
            assertEquals(1, fixture.controller.createImport(source.command, "root", FILE, "Concurrent duplicate country"));
            await(() -> source.contains("중복 이름"));
            assertEquals(1, fixture.requests.size());
            assertEquals(2, fixture.controller.snapshot().regions().size());
            assertEquals("Existing country", fixture.controller.snapshot().find(PARENT).name());
            assertNotEquals(PARENT, fixture.requests.get(0).body.get("regionId").getAsString());
        }
    }

    @Test void queuedImportKeepsItsOriginalRevisionAfterEarlierSaveCompletes() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            Source first = fixture.console();
            Source second = fixture.console();
            fixture.holdCommands = true;
            assertEquals(1, fixture.controller.createImport(first.command, "root", FILE, "First country"));
            assertTrue(fixture.commandStarted.await(3, TimeUnit.SECONDS));
            assertEquals(1, fixture.controller.createImport(second.command, "root", FILE, "Queued country"));
            fixture.releaseCommands.countDown();
            await(() -> first.contains("버전 8") && second.contains("다른 변경"));
            assertEquals(2, fixture.requests.size());
            assertEquals(7, fixture.requests.get(0).body.get("expectedRevision").getAsLong());
            assertEquals(7, fixture.requests.get(1).body.get("expectedRevision").getAsLong());
            assertEquals(8, fixture.controller.snapshot().revision());
            assertEquals(3, fixture.controller.snapshot().regions().size());
            assertFalse(second.contains("저장했습니다"));
        }
    }

    @Test void timeoutIsAmbiguousAndDisablesFurtherMutationsWithoutAutomaticResubmission() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofMillis(250))) {
            Source source = fixture.console();
            fixture.holdCommands = true;
            assertEquals(1, fixture.controller.createImport(source.command, "root", FILE, "Uncertain country"));
            assertTrue(fixture.commandStarted.await(3, TimeUnit.SECONDS));
            await(() -> source.contains("history") && source.contains("list"));
            assertFalse(fixture.controller.online());
            assertFalse(source.contains("저장했습니다"));
            assertEquals(1, fixture.requests.size());
            assertEquals(7, fixture.controller.snapshot().revision());
            assertEquals(0, fixture.controller.createImport(source.command, "root", FILE, "Do not resend"));
            assertEquals(1, fixture.requests.size());
        }
    }

    private static void assertGeometry(JsonObject body) {
        JsonObject geometry = body.getAsJsonObject("geometry");
        assertEquals(Set.of("type", "coordinates"), geometry.keySet());
        assertEquals("MultiPolygon", geometry.get("type").getAsString());
        assertEquals(JsonParser.parseString(INPUT).getAsJsonObject().get("coordinates"), geometry.get("coordinates"));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Asynchronous operation did not finish in time");
    }

    private record Source(ServerCommandSource command, List<String> messages) {
        boolean contains(String part) { return messages.stream().anyMatch(message -> message.contains(part)); }
    }

    private record Request(JsonObject body, String authorization, String path) { }

    private final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService httpWorkers = Executors.newCachedThreadPool();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final List<Snapshot> published = new CopyOnWriteArrayList<>();
        final CountDownLatch commandStarted = new CountDownLatch(1);
        final CountDownLatch releaseCommands = new CountDownLatch(1);
        final Path cache = temporary.resolve(UUID.randomUUID() + "/cache.json");
        final Path imports = temporary.resolve(UUID.randomUUID() + "/imports");
        final RegionController controller;
        final CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        final Field controllerField;
        final Object previousController;
        volatile Snapshot state = initialSnapshot();
        volatile boolean holdCommands;
        volatile int errorStatus;
        volatile String errorCode;

        Fixture(boolean online, Duration timeout) throws Exception {
            Files.createDirectories(imports);
            Files.writeString(imports.resolve(FILE), INPUT, StandardCharsets.UTF_8);
            doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                    .when(server).execute(any(Runnable.class));
            http.setExecutor(httpWorkers);
            http.createContext("/", this::respond);
            http.start();
            ServerConfig config = new ServerConfig();
            config.apiBaseUrl = "http://127.0.0.1:" + http.getAddress().getPort();
            config.worldId = "import-test-world";
            config.pollSeconds = 300;
            config.validate();
            controller = new RegionController(server, config, published::add,
                    new RegionApi(config, online ? "local-import-test-token" : null, timeout), cache, imports);
            controllerField = RegionsMod.class.getDeclaredField("controller");
            controllerField.setAccessible(true);
            previousController = controllerField.get(null);
            controllerField.set(null, controller);
            RegionsCommands.register(dispatcher);
            controller.start();
            if (online) await(() -> controller.online() && !published.isEmpty() && Files.exists(cache));
        }

        Source console() { return source(server, null, true); }

        Source source(CommandOutput output, Entity entity, boolean permission) {
            ServerCommandSource command = mock(ServerCommandSource.class,
                    withSettings().extraInterfaces(ServerCommandSourceAccessor.class));
            when(((ServerCommandSourceAccessor) command).regions$getOutput()).thenReturn(output);
            when(command.getServer()).thenReturn(server);
            when(command.getEntity()).thenReturn(entity);
            when(command.hasPermissionLevel(4)).thenReturn(permission);
            List<String> messages = new CopyOnWriteArrayList<>();
            doAnswer(invocation -> {
                Supplier<Text> feedback = invocation.getArgument(0);
                messages.add(feedback.get().getString()); return null;
            }).when(command).sendFeedback(any(), anyBoolean());
            doAnswer(invocation -> { messages.add(((Text) invocation.getArgument(0)).getString()); return null; })
                    .when(command).sendError(any(Text.class));
            return new Source(command, messages);
        }

        private void respond(HttpExchange exchange) throws IOException {
            try {
                if (exchange.getRequestURI().getPath().endsWith("/snapshot")) {
                    send(exchange, 200, RegionApi.JSON.toJson(state));
                    return;
                }
                if (!exchange.getRequestURI().getPath().endsWith("/commands")) {
                    send(exchange, 404, "{}"); return;
                }
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                requests.add(new Request(body, exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestURI().getPath()));
                commandStarted.countDown();
                if (holdCommands) releaseCommands.await(5, TimeUnit.SECONDS);
                if (errorStatus != 0) {
                    if ("REVISION_CONFLICT".equals(errorCode)) state = new Snapshot(8, state.regions(), state.globalAdmins());
                    send(exchange, errorStatus, "{\"code\":\"" + errorCode + "\"}"); return;
                }
                if (body.get("expectedRevision").getAsLong() != state.revision()) {
                    send(exchange, 409, "{\"code\":\"REVISION_CONFLICT\"}"); return;
                }
                Geometry geometry = RegionApi.JSON.fromJson(body.get("geometry"), Geometry.class);
                List<Region> regions = new ArrayList<>(state.regions());
                if ("create".equals(body.get("operation").getAsString())) {
                    String parent = body.get("parentId").isJsonNull() ? null : body.get("parentId").getAsString();
                    regions.add(new Region(body.get("regionId").getAsString(), parent, body.get("name").getAsString(), null, geometry, List.of()));
                } else {
                    Region before = state.find(body.get("regionId").getAsString());
                    regions.remove(before);
                    regions.add(new Region(before.id(), before.parentId(), before.name(), before.displayLevel(), geometry, before.managers()));
                }
                state = new Snapshot(state.revision() + 1, List.copyOf(regions), state.globalAdmins());
                send(exchange, 200, RegionApi.JSON.toJson(state));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        }

        @Override public void close() throws Exception {
            controller.close();
            releaseCommands.countDown();
            http.stop(0);
            httpWorkers.shutdownNow();
            controllerField.set(null, previousController);
        }
    }

    private static void send(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Snapshot initialSnapshot() {
        Geometry geometry = new Geometry("MultiPolygon", new double[][][][]{{{{0,0},{100,0},{100,100},{0,100},{0,0}}}});
        return new Snapshot(7, List.of(new Region(PARENT, null, "Existing country", null, geometry, List.of()),
                new Region(CHILD, PARENT, "Existing child", 3, geometry, List.of(MANAGER))), List.of());
    }
}
