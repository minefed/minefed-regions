package team.minefed.regions.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.rcon.RconCommandOutput;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.minefed.regions.RegionsMod;
import team.minefed.regions.model.Region;
import team.minefed.regions.model.Snapshot;
import team.minefed.regions.mixin.ServerCommandSourceAccessor;
import team.minefed.regions.model.SnapshotValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class RegionController implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("minefed-regions");
    private final MinecraftServer server;
    private final RegionApi api;
    private final ServerConfig config;
    private final Path cache;
    private final Path imports;
    private final Consumer<Snapshot> onSnapshot;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "minefed-regions-api"); thread.setDaemon(true); return thread;
    });
    private final Map<UUID, String> sentAddresses = new HashMap<>();
    private final Map<UUID, PositionStamp> checkedPositions = new HashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile boolean online, hasSnapshot, closed;
    private int tick;
    private record PositionStamp(double x, double z, boolean overworld, long revision, boolean online, boolean known) { }

    public RegionController(MinecraftServer server, ServerConfig config, Consumer<Snapshot> onSnapshot) {
        this(server, config, onSnapshot, new RegionApi(config),
                server.getSavePath(WorldSavePath.ROOT).resolve("data/minefed-regions-" + config.worldId + ".json"),
                FabricLoader.getInstance().getConfigDir().resolve("minefed-regions/imports"));
    }
    RegionController(MinecraftServer server, ServerConfig config, Consumer<Snapshot> onSnapshot,
                     RegionApi api, Path cache, Path imports) {
        this.server = server; this.config = config; this.onSnapshot = onSnapshot;
        this.api = api; this.cache = cache; this.imports = imports;
    }
    public void start() {
        worker.execute(() -> {
            loadCache();
            if (!api.configured()) LOG.warn("Regions is read-only: configure the token environment variable in minefed-regions-server.json");
        });
        worker.scheduleWithFixedDelay(this::poll, 0, config.pollSeconds, TimeUnit.SECONDS);
    }
    public Snapshot snapshot() { return snapshot; }
    public boolean online() { return online; }
    public static boolean console(ServerCommandSource source) {
        // Excludes OP players, command blocks, and permission-limited datapack functions.
        var output = ((ServerCommandSourceAccessor) source).regions$getOutput();
        return source.getEntity() == null && source.hasPermissionLevel(4)
                && (output == source.getServer() || output instanceof RconCommandOutput);
    }
    public boolean manager(ServerCommandSource source) {
        if (console(source)) return true;
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) return false;
        var output = ((ServerCommandSourceAccessor) source).regions$getOutput();
        if (!(output instanceof ServerPlayerEntity issuer)
                || !ActorIdentity.matches(issuer.getUuid(), player.getUuid()) || issuer != player) return false;
        String uuid = player.getUuidAsString();
        return snapshot.isGlobal(uuid) || snapshot.regions().stream().anyMatch(r -> r.managers().contains(uuid));
    }
    public Selection selection(ServerPlayerEntity player) {
        Selection draft = selections.computeIfAbsent(player.getUuid(), unused -> new Selection());
        draft.captureRevision(snapshot.revision());
        return draft;
    }
    public void forget(ServerPlayerEntity player) {
        sentAddresses.remove(player.getUuid()); checkedPositions.remove(player.getUuid()); selections.remove(player.getUuid());
    }
    public void tick() {
        if (++tick % 5 != 0 || closed) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!ServerPlayNetworking.canSend(player, RegionsMod.ADDRESS)) continue;
            Snapshot current = snapshot;
            boolean onlineNow = online, known = hasSnapshot;
            boolean overworld = player.getWorld().getRegistryKey().equals(World.OVERWORLD);
            PositionStamp stamp = new PositionStamp(player.getX(),player.getZ(),overworld,current.revision(),onlineNow,known);
            if (stamp.equals(checkedPositions.get(player.getUuid()))) continue;
            String address = "";
            if (overworld) {
                Region region = current.locate(player.getX(), player.getZ());
                address = !known ? "행정구역 정보를 불러오지 못했습니다" : region == null ? "미지정 지역" : current.address(region);
            }
            String key = address + '\0' + onlineNow;
            if (!key.equals(sentAddresses.get(player.getUuid()))) {
                PacketByteBuf buffer = PacketByteBufs.create(); buffer.writeString(address, 16384); buffer.writeBoolean(!onlineNow);
                ServerPlayNetworking.send(player, RegionsMod.ADDRESS, buffer); sentAddresses.put(player.getUuid(),key);
            }
            checkedPositions.put(player.getUuid(),stamp);
        }
    }
    private void poll() {
        if (closed || !api.configured()) return;
        try { accept(api.snapshot()); }
        catch (Exception exception) { offline(); }
    }
    private void offline() {
        boolean wasOnline = online; online = false;
        if (wasOnline && !closed) LOG.warn("Regions API disconnected; retaining last snapshot and disabling edits");
    }
    private void accept(Snapshot next) {
        if (closed) return;
        if (hasSnapshot && next.revision() < snapshot.revision()) {
            // A restored or wrong backend must never silently replace a newer local world.
            offline(); return;
        }
        boolean changed = !hasSnapshot || next.revision() != snapshot.revision();
        snapshot = next; hasSnapshot = true; online = true;
        if (changed) {
            saveCache(next);
            if (!closed) publishMap(next);
        }
    }
    private void publishMap(Snapshot next) {
        try { onSnapshot.accept(next); }
        catch (RuntimeException exception) { LOG.warn("BlueMap region publication failed; API and HUD remain available"); }
    }
    public int mutate(ServerCommandSource source, JsonObject command) {
        if (!manager(source)) { source.sendError(Text.literal("행정구역 관리 권한이 없습니다. OP 권한과는 별개입니다.")); return 0; }
        if (!online) { source.sendError(Text.literal("API 연결이 없어 수정할 수 없습니다. /region status 를 확인하세요.")); return 0; }
        command.addProperty("requestId",UUID.randomUUID().toString());
        if (!command.has("expectedRevision")) command.addProperty("expectedRevision",snapshot.revision());
        command.addProperty("console",console(source));
        if (source.getEntity() instanceof ServerPlayerEntity player) command.addProperty("actorUuid",player.getUuidAsString());
        source.sendFeedback(() -> Text.literal("행정구역 변경을 확인하고 있습니다…"),false);
        worker.execute(() -> performMutation(source, command, false));
        return 1;
    }
    public int validateImport(ServerCommandSource source, String filename) {
        return importFile(source, filename, null, null, null);
    }
    public int createImport(ServerCommandSource source, String parent, String filename, String name) {
        return importFile(source, filename, "create", parent, name);
    }
    public int boundaryImport(ServerCommandSource source, String region, String filename) {
        return importFile(source, filename, "geometry", region, null);
    }
    private int importFile(ServerCommandSource source, String filename, String operation, String target, String name) {
        if (!console(source)) {
            source.sendError(Text.literal("파일 가져오기는 서버 콘솔과 인증된 RCON에서만 사용할 수 있습니다.")); return 0;
        }
        if (closed || operation != null && !online) {
            source.sendError(Text.literal("API 연결이 없어 수정할 수 없습니다. /region status 를 확인하세요.")); return 0;
        }
        // Capture once, before file I/O: neither a poll nor a queued mutation may silently rebase this request.
        Snapshot base = snapshot;
        String regionId = operation == null ? null : operation.equals("create") ? UUID.randomUUID().toString() : target;
        String requestId = UUID.randomUUID().toString();
        source.sendFeedback(() -> Text.literal(operation == null
                ? "파일 구조 검사 요청을 접수했습니다. 아직 검사가 완료되지 않았습니다. 최종 결과는 서버 로그를 확인하세요."
                : "파일 가져오기 요청을 접수했습니다. 아직 저장되지 않았습니다. 지역 UUID " + regionId
                  + " · 요청 " + requestId + " · 서버 로그의 저장 완료를 확인한 뒤 다음 명령을 실행하세요."), false);
        worker.execute(() -> {
            try {
                if (closed) return;
                GeoJsonImport.Result file = GeoJsonImport.read(imports, filename);
                if (operation == null) {
                    importReply(source, "파일 구조 검증 통과 · 파일 " + file.filename() + " · SHA-256 " + file.sha256()
                            + " · 영역 " + file.polygons() + "개 · 전체 고리 " + file.rings() + "개"
                            + " · 폐합점 포함 정점 " + file.verticesIncludingClosure() + "개 · 폐합점 제외 정점 "
                            + file.verticesExcludingClosure() + "개. 자기 교차·부모 포함·형제 중첩 및 실제 등록 가능 여부는 API 저장 시 판정합니다.");
                    return;
                }
                JsonObject command = importCommand(base, operation, regionId, target, name, file);
                command.addProperty("requestId", requestId);
                command.addProperty("expectedRevision", base.revision());
                command.addProperty("console", true);
                performMutation(source, command, true);
            } catch (IllegalArgumentException | IOException exception) {
                importReply(source, "파일 가져오기 실패 · 파일 " + filename + ": " + exception.getMessage());
            } catch (Exception exception) {
                importReply(source, "파일을 처리하지 못했습니다. 파일 형식과 읽기 권한을 확인하세요.");
            }
        });
        return 1;
    }
    private static JsonObject importCommand(Snapshot base, String operation, String regionId, String target,
                                            String name, GeoJsonImport.Result file) {
        regionId = canonicalUuid(regionId);
        JsonObject command = new JsonObject();
        command.addProperty("operation", operation);
        command.addProperty("regionId", regionId);
        List<Region> regions = new ArrayList<>(base.regions());
        if (operation.equals("create")) {
            String parent = "root".equals(target) ? null : canonicalUuid(target);
            if (parent != null && base.find(parent) == null) throw new IllegalArgumentException("상위 지역 UUID를 찾을 수 없습니다.");
            command.add("parentId", parent == null ? JsonNull.INSTANCE : RegionApi.JSON.toJsonTree(parent));
            command.addProperty("name", name);
            regions.add(new Region(regionId, parent, name, null, file.geometry(), List.of()));
        } else {
            Region previous = base.find(regionId);
            if (previous == null) throw new IllegalArgumentException("지역을 찾을 수 없습니다. /region list 로 UUID를 확인하세요.");
            regions.set(regions.indexOf(previous), new Region(previous.id(), previous.parentId(), previous.name(),
                    previous.displayLevel(), file.geometry(), previous.managers()));
        }
        // Same structural/name/hierarchy rules as accepted snapshots; topology remains the API's decision.
        SnapshotValidator.validate(new Snapshot(base.revision(), regions, base.globalAdmins()));
        command.add("geometry", RegionApi.JSON.toJsonTree(file.geometry()));
        return command;
    }
    private static String canonicalUuid(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (canonical.equalsIgnoreCase(value)) return canonical;
        } catch (IllegalArgumentException | NullPointerException ignored) { }
        throw new IllegalArgumentException("정확한 지역 UUID를 입력하세요. /region list 로 확인할 수 있습니다.");
    }
    /** Runs only on worker, shared by player edits and imports, including the existing publication path. */
    private void performMutation(ServerCommandSource source, JsonObject command, boolean importing) {
        if (closed) return;
        if (!online) {
            mutationReply(source, "API 연결이 없어 수정할 수 없습니다. /region status 를 확인하세요.", importing); return;
        }
        String identity = importing ? " · 지역 UUID " + command.get("regionId").getAsString()
                + " · 요청 " + command.get("requestId").getAsString() : "";
        try {
            Snapshot result = api.command(command); accept(result);
            mutationReply(source, "변경을 저장했습니다." + identity + " · 버전 " + result.revision(), importing);
        } catch (IllegalArgumentException exception) {
            mutationReply(source, "요청을 보내지 않았습니다: " + exception.getMessage() + identity, importing);
        } catch (RegionApi.ApiException exception) {
            if (exception.status >= 500 || exception.status == 401) offline();
            String message = importing && exception.code.equals("REVISION_CONFLICT")
                    ? "다른 변경이 먼저 저장되어 가져오기를 적용하지 않았습니다. /region history 와 /region list 를 확인한 뒤 명령을 다시 실행하세요. 자동 재시도하지 않습니다."
                    : errorMessage(exception.code);
            mutationReply(source, message + identity, importing);
            if (exception.status == 409) poll();
        } catch (Exception exception) {
            offline(); mutationReply(source, "응답을 확인하지 못해 저장 결과가 불확실합니다." + identity
                    + " · /region history 와 /region list 로 반영 여부를 확인하세요. 자동 재전송하지 않습니다.", importing);
        }
    }
    private void mutationReply(ServerCommandSource source, String message, boolean importing) {
        if (importing) importReply(source, message); else reply(source, message);
    }
    private void importReply(ServerCommandSource source, String message) {
        LOG.info("{}", message);
        reply(source, message);
    }
    public int history(ServerCommandSource source, long beforeRevision) {
        if (!manager(source)) { source.sendError(Text.literal("관리자만 변경 이력을 조회할 수 있습니다.")); return 0; }
        if (!online) { source.sendError(Text.literal("변경 이력 조회에는 API 연결이 필요합니다.")); return 0; }
        worker.execute(() -> {
            try {
                JsonObject result = api.history(beforeRevision);
                StringBuilder lines = new StringBuilder("최근 변경 이력 (되돌리기: /region undo <이력 UUID>)");
                long oldest = 0;
                for (var item : result.getAsJsonArray("entries")) {
                    JsonObject entry = item.getAsJsonObject();
                    lines.append('\n').append(entry.get("id").getAsString()).append(" · ")
                            .append(entry.get("operation").getAsString()).append(" · 버전 ").append(entry.get("revision").getAsLong())
                            .append("\n  ").append(entry.has("actorUuid") && !entry.get("actorUuid").isJsonNull() ? entry.get("actorUuid").getAsString() : "서버 콘솔")
                            .append(" · ").append(entry.get("createdAt").getAsString());
                    oldest = entry.get("revision").getAsLong();
                }
                if (oldest > 1) lines.append("\n이전 이력: /region history ").append(oldest);
                reply(source, lines.toString());
            } catch (Exception exception) { reply(source,"변경 이력을 불러오지 못했습니다."); }
        });
        return 1;
    }
    private static String errorMessage(String code) {
        return switch (code) {
            case "PERMISSION_DENIED" -> "권한이 없습니다. 자기 지역은 수정할 수 없으며 상위 지역 관리자가 수정해야 합니다.";
            case "REVISION_CONFLICT" -> "다른 변경이 먼저 저장되었습니다. 경계를 다시 load하거나, 최신 변경을 확인한 후 select rebase 로 초안을 명시적으로 갱신하세요.";
            case "CONFLICT" -> "하위 지역·중복 이름·후속 변경 때문에 요청을 적용할 수 없습니다.";
            case "INVALID_ARGUMENT" -> "입력이나 경계가 유효하지 않습니다. 교차·중첩·부모 포함 조건을 확인하세요.";
            case "NOT_FOUND" -> "해당 지역 또는 이력이 없습니다.";
            case "UNAUTHENTICATED" -> "API 서버 인증 설정을 확인하세요.";
            default -> "API가 변경을 거부했습니다: " + code;
        };
    }
    private void reply(ServerCommandSource source, String message) {
        server.execute(() -> { if (!closed) source.sendFeedback(() -> Text.literal(message),false); });
    }
    private void loadCache() {
        if (!Files.exists(cache)) return;
        try {
            if (Files.size(cache) > 8 * 1024 * 1024) throw new IOException();
            JsonObject saved = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
            if (!config.apiBaseUrl.equals(saved.get("apiBaseUrl").getAsString()) || !config.worldId.equals(saved.get("worldId").getAsString())) return;
            Snapshot loaded = SnapshotValidator.validate(RegionApi.JSON.fromJson(saved.get("snapshot"),Snapshot.class));
            snapshot = loaded; hasSnapshot = true;
            if (!closed) onSnapshot.accept(loaded);
        } catch (Exception exception) { LOG.warn("Ignoring unreadable region cache; waiting for authoritative API"); }
    }
    private void saveCache(Snapshot next) {
        try {
            Files.createDirectories(cache.getParent());
            JsonObject saved = new JsonObject(); saved.addProperty("apiBaseUrl",config.apiBaseUrl); saved.addProperty("worldId",config.worldId);
            saved.add("snapshot", RegionApi.JSON.toJsonTree(next));
            Path temporary = cache.resolveSibling(cache.getFileName()+".tmp");
            Files.writeString(temporary,RegionApi.JSON.toJson(saved));
            try { Files.move(temporary,cache,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary,cache,StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { LOG.warn("Unable to save last-known region cache"); }
    }
    @Override public void close() { closed = true; online = false; worker.shutdownNow(); selections.clear(); sentAddresses.clear(); checkedPositions.clear(); }
}
