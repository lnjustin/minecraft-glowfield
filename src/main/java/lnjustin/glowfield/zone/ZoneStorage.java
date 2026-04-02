package lnjustin.glowfield.zone;

import lnjustin.glowfield.GlowField;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ZoneStorage {
	private static final String FILE_NAME = "glowfield_zones.nbt";

	private ZoneStorage() {
	}

	public static void save(MinecraftServer server, Iterable<GlowZone> zones) {
		Path file = server.getRunDirectory().resolve(FILE_NAME);
		NbtCompound root = new NbtCompound();
		NbtList zoneList = new NbtList();

		for (GlowZone zone : zones) {
			NbtCompound zoneTag = new NbtCompound();
			zone.writeNbt(zoneTag);
			zoneList.add(zoneTag);
		}

		root.put("zones", zoneList);
		try {
			NbtIo.writeCompressed(root, file);
		} catch (Exception exception) {
			GlowField.LOGGER.error("Failed to save GlowField zones", exception);
		}
	}

	public static List<GlowZone> load(MinecraftServer server) {
		Path file = server.getRunDirectory().resolve(FILE_NAME);
		if (Files.notExists(file)) {
			return List.of();
		}

		try {
			NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
			NbtList zoneList = root.getListOrEmpty("zones");
			List<GlowZone> zones = new ArrayList<>(zoneList.size());
			for (int i = 0; i < zoneList.size(); i++) {
				zones.add(GlowZone.fromNbt(zoneList.getCompoundOrEmpty(i)));
			}
			return zones;
		} catch (Exception exception) {
			GlowField.LOGGER.error("Failed to load GlowField zones", exception);
			return List.of();
		}
	}
}
