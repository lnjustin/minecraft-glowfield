package lnjustin.glowfield;

import lnjustin.glowfield.command.GlowFieldCommands;
import lnjustin.glowfield.config.GlowFieldConfig;
import lnjustin.glowfield.zone.ZoneRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GlowField implements ModInitializer {
	public static final String MOD_ID = "glowfield";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static GlowFieldConfig config;
	private static ZoneRegistry zoneRegistry;

	@Override
	public void onInitialize() {
		config = GlowFieldConfig.load();
		zoneRegistry = new ZoneRegistry(config);

		ServerLifecycleEvents.SERVER_STARTED.register(zoneRegistry::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(zoneRegistry::save);

		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> zoneRegistry.onChunkLoad((ServerWorld) world, chunk));
		ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> zoneRegistry.onChunkUnload((ServerWorld) world, chunk));
		ServerTickEvents.END_WORLD_TICK.register(zoneRegistry::onEndWorldTick);

		ServerEntityEvents.ENTITY_LOAD.register(zoneRegistry::onEntityLoad);
		ServerPlayerEvents.JOIN.register(zoneRegistry::onPlayerJoin);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> zoneRegistry.onPlayerJoin(newPlayer));
		ServerEntityWorldChangeEvents.AFTER_ENTITY_CHANGE_WORLD.register((oldEntity, newEntity, origin, destination) -> zoneRegistry.onEntityLoad(newEntity, destination));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> zoneRegistry.onPlayerJoin(player));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register(zoneRegistry::allowDamage);
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register(zoneRegistry::onEntityDeath);

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
				return zoneRegistry.beforeBlockBreak((ServerWorld) world, serverPlayer, pos, state);
			}
			return true;
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
				zoneRegistry.onBlockBroken((ServerWorld) world, pos, state, serverPlayer);
			}
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient()) {
				return ActionResult.PASS;
			}

			if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
				return zoneRegistry.onUseBlock((ServerWorld) world, serverPlayer, hand, hitResult);
			}
			return ActionResult.PASS;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			GlowFieldCommands.register(dispatcher, zoneRegistry));
	}

	public static GlowFieldConfig config() {
		return config;
	}

	public static ZoneRegistry zones() {
		return zoneRegistry;
	}
}
