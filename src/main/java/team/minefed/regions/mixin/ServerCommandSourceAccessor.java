package team.minefed.regions.mixin;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Identify the actual console output, never a player-supplied command source name. */
@Mixin(ServerCommandSource.class)
public interface ServerCommandSourceAccessor {
    @Accessor("output") CommandOutput regions$getOutput();
}
