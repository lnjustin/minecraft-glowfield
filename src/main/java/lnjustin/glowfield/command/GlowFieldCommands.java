package lnjustin.glowfield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import lnjustin.glowfield.zone.GlowZone;
import lnjustin.glowfield.zone.ZoneRegistry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

public final class GlowFieldCommands {
	private GlowFieldCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, ZoneRegistry registry) {
		dispatcher.register(CommandManager.literal("glowfield")
			.then(CommandManager.literal("setname")
				.then(CommandManager.argument("name", StringArgumentType.greedyString())
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
						GlowZone zone = registry.findOwnedZoneContaining(player);
						if (zone == null) {
							context.getSource().sendError(Text.literal("You are not inside one of your GlowField zones."));
							return 0;
						}

						String name = StringArgumentType.getString(context, "name").trim();
						registry.renameZone(zone.id(), name);
						context.getSource().sendFeedback(() -> Text.literal("Zone renamed to " + registry.describeZone(zone.id()) + "."), false);
						return 1;
					})))
			.then(CommandManager.literal("info")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					GlowZone zone = registry.findZoneContaining(player);
					if (zone == null) {
						context.getSource().sendError(Text.literal("You are not inside a GlowField zone."));
						return 0;
					}

					context.getSource().sendFeedback(() -> Text.literal(registry.describeZoneVerbose(zone)), false);
					return 1;
				}))
			.then(CommandManager.literal("list")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					List<GlowZone> zones = registry.listOwnedZones(player.getUuid());
					if (zones.isEmpty()) {
						context.getSource().sendFeedback(() -> Text.literal("You do not own any GlowField zones."), false);
						return 0;
					}

					for (GlowZone zone : zones) {
						context.getSource().sendFeedback(() -> Text.literal(registry.describeZoneVerbose(zone)), false);
					}
					return zones.size();
				}))
			.then(CommandManager.literal("remove")
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
					GlowZone zone = registry.findOwnedZoneContaining(player);
					if (zone == null) {
						context.getSource().sendError(Text.literal("You are not inside one of your GlowField zones."));
						return 0;
					}

					registry.removeZone(zone.id());
					context.getSource().sendFeedback(() -> Text.literal("Removed " + registry.describeZone(zone.id()) + "."), true);
					return 1;
				})));
	}
}
