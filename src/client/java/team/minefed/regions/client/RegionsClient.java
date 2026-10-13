package team.minefed.regions.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** The server owns the address; the client only chooses how to display it. */
public final class RegionsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("minefed-regions-client");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("minefed-regions-hud.json");
    private Settings settings = new Settings();
    private String address;
    private boolean stale;

    @Override
    public void onInitializeClient() {
        loadSettings();
        ClientPlayNetworking.registerGlobalReceiver(new Identifier("minefed-regions", "address"),
                (client, handler, buffer, responseSender) -> {
                    String nextAddress = buffer.readString(16384);
                    boolean nextStale = buffer.readBoolean();
                    client.execute(() -> {
                        // Ignore work queued by an already disconnected server connection.
                        if (client.getNetworkHandler() != handler) return;
                        address = nextAddress.isBlank() ? "미지정 지역" : nextAddress;
                        stale = nextStale;
                    });
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        HudRenderCallback.EVENT.register(this::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("regionshud")
                        .executes(context -> feedback(context.getSource(), "행정구역 HUD: " + settings.side
                                + ", 배율 " + settings.scale + ", 표시 " + settings.visible))
                        .then(literal("address").executes(context -> feedback(context.getSource(),
                                address == null ? "행정구역 정보가 없습니다." : address)))
                        .then(literal("side")
                                .then(literal("left").executes(context -> {
                                    settings.side = "left";
                                    return saveAndFeedback(context.getSource());
                                }))
                                .then(literal("right").executes(context -> {
                                    settings.side = "right";
                                    return saveAndFeedback(context.getSource());
                                })))
                        .then(literal("scale").then(argument("value", FloatArgumentType.floatArg(0.5f, 2f))
                                .executes(context -> {
                                    settings.scale = FloatArgumentType.getFloat(context, "value");
                                    return saveAndFeedback(context.getSource());
                                })))
                        .then(literal("visible").then(argument("value", BoolArgumentType.bool())
                                .executes(context -> {
                                    settings.visible = BoolArgumentType.getBool(context, "value");
                                    return saveAndFeedback(context.getSource());
                                })))));
    }

    private void clear() {
        address = null;
        stale = false;
    }

    private void render(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!settings.visible || address == null || client.world == null || client.player == null
                || client.options.hudHidden || !World.OVERWORLD.equals(client.world.getRegistryKey())) return;
        float scale = settings.scale;
        int screenWidth = (int) (client.getWindow().getScaledWidth() / scale);
        int padding = 5;
        int maxWidth = Math.max(40, screenWidth - 24);
        List<OrderedText> wrapped = client.textRenderer.wrapLines(Text.literal(address), maxWidth - padding * 2);
        String status = stale ? "마지막 동기화 정보" : null;
        int lineHeight = client.textRenderer.fontHeight + 2;
        int maximumLines = Math.max(2, ((int) (client.getWindow().getScaledHeight() / scale) - 26) / lineHeight);
        boolean overflow = wrapped.size() + (stale ? 1 : 0) > maximumLines;
        List<OrderedText> lines = wrapped.subList(0, Math.min(wrapped.size(),
                Math.max(1, maximumLines - (stale ? 1 : 0) - (overflow ? 1 : 0))));
        String more = overflow ? "전체 주소: /regionshud address" : null;
        int width = lines.stream().mapToInt(client.textRenderer::getWidth).max().orElse(0);
        if (status != null) width = Math.max(width, client.textRenderer.getWidth(status));
        if (more != null) width = Math.max(width, client.textRenderer.getWidth(more));
        width += padding * 2;
        int height = padding * 2 + (lines.size() + (stale ? 1 : 0) + (overflow ? 1 : 0)) * lineHeight;
        int x = "left".equals(settings.side) ? 8 : screenWidth - width - 8;
        int y = 8;
        context.getMatrices().push();
        try {
            context.getMatrices().scale(scale, scale, 1f);
            context.fill(x, y, x + width, y + height, 0x880C1720);
            for (OrderedText line : lines) {
                context.drawTextWithShadow(client.textRenderer, line, x + padding, y + padding, 0xF1F5F9);
                y += lineHeight;
            }
            if (more != null) {
                context.drawTextWithShadow(client.textRenderer, more, x + padding, y + padding, 0x9ED7F5);
                y += lineHeight;
            }
            if (status != null) context.drawTextWithShadow(client.textRenderer, status, x + padding,
                    y + padding, 0xAAB4C0);
        } finally {
            context.getMatrices().pop();
        }
    }

    private void loadSettings() {
        if (!Files.isRegularFile(CONFIG)) return;
        try {
            Settings loaded = GSON.fromJson(Files.readString(CONFIG, StandardCharsets.UTF_8), Settings.class);
            if (loaded == null) return;
            loaded.side = "left".equals(loaded.side) ? "left" : "right";
            if (!Float.isFinite(loaded.scale)) loaded.scale = 1f;
            loaded.scale = Math.max(0.5f, Math.min(2f, loaded.scale));
            settings = loaded;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read region HUD settings; using defaults", exception);
        }
    }

    private int saveAndFeedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        try {
            Files.createDirectories(CONFIG.getParent());
            Path temporary = CONFIG.resolveSibling(CONFIG.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(settings), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING);
            }
            return feedback(source, "행정구역 HUD 설정을 저장했습니다.");
        } catch (IOException exception) {
            LOGGER.warn("Could not save region HUD settings", exception);
            source.sendError(Text.literal("설정은 적용했지만 파일에 저장하지 못했습니다."));
            return 0;
        }
    }

    private static int feedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String text) {
        source.sendFeedback(Text.literal(text));
        return 1;
    }

    private static final class Settings {
        String side = "right";
        float scale = 1f;
        boolean visible = true;
    }
}
