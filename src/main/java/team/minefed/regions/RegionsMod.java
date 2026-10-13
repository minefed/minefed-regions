package team.minefed.regions;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.LoggerFactory;
import team.minefed.regions.bluemap.BlueMapIntegration;
import team.minefed.regions.model.Snapshot;
import team.minefed.regions.server.RegionController;
import team.minefed.regions.server.RegionsCommands;
import team.minefed.regions.server.ServerConfig;

public final class RegionsMod implements ModInitializer {
    public static final Identifier ADDRESS = new Identifier("minefed-regions", "address");
    public static final String WAND_TAG = "minefed_regions_wand";
    private static volatile RegionController controller;
    public static RegionController controller() { return controller; }

    @Override public void onInitialize() {
        boolean hasBlueMap = FabricLoader.getInstance().isModLoaded("bluemap");
        if (hasBlueMap) BlueMapIntegration.start(() -> controller == null ? Snapshot.empty() : controller.snapshot());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (hasBlueMap) BlueMapIntegration.start(() -> controller == null ? Snapshot.empty() : controller.snapshot());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> RegionsCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                ServerConfig config = ServerConfig.load(FabricLoader.getInstance().getConfigDir().resolve("minefed-regions-server.json"));
                controller = new RegionController(server, config, snapshot -> { if (hasBlueMap) BlueMapIntegration.refresh(snapshot); });
                controller.start();
            } catch (Exception exception) {
                LoggerFactory.getLogger("minefed-regions").error("Region configuration could not be loaded; /region is disabled. Check config/minefed-regions-server.json");
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (controller != null) controller.tick(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> { if (controller != null) controller.forget(handler.player); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RegionController current = controller; controller = null;
            if (current != null) current.close();
            if (hasBlueMap) BlueMapIntegration.stop();
        });
        UseBlockCallback.EVENT.register((player,world,hand,hit) -> {
            if (hand != Hand.MAIN_HAND || !player.getStackInHand(hand).hasNbt()
                    || !player.getStackInHand(hand).getNbt().getBoolean(WAND_TAG)) return ActionResult.PASS;
            if (world.isClient) return ActionResult.SUCCESS;
            if (!(player instanceof ServerPlayerEntity serverPlayer) || controller == null) return ActionResult.FAIL;
            if (!world.getRegistryKey().equals(World.OVERWORLD) || !controller.manager(serverPlayer.getCommandSource())) {
                player.sendMessage(Text.literal("일반 월드의 행정구역 관리자만 경계를 선택할 수 있습니다."),true);
                return ActionResult.FAIL;
            }
            try {
                var selection = controller.selection(serverPlayer);
                if (player.isSneaking()) selection.undo();
                else selection.point(hit.getBlockPos().getX(),hit.getBlockPos().getZ());
                player.sendMessage(Text.literal("선택 정점 " + selection.count() + "개 · /region select show 로 확인"),true);
            } catch (IllegalArgumentException exception) { player.sendMessage(Text.literal(exception.getMessage()),true); }
            return ActionResult.SUCCESS;
        });
    }
}
