package lnjustin.glowfield.zone;

import lnjustin.glowfield.config.GlowFieldConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class GlowZone {
	private final UUID id;
	private final RegistryKey<World> worldKey;
	private final UUID ownerUuid;
	private String ownerName;
	private String name;
	private final LinkedHashSet<BlockPos> anchors;
	private final Box bounds;
	private final Set<ChunkPos> coveredChunks;
	private int interactionProgress;

	public GlowZone(UUID id, RegistryKey<World> worldKey, UUID ownerUuid, String ownerName, String name, Collection<BlockPos> anchors, int interactionProgress) {
		this.id = id;
		this.worldKey = worldKey;
		this.ownerUuid = ownerUuid;
		this.ownerName = ownerName;
		this.name = name == null ? "" : name;
		this.anchors = new LinkedHashSet<>(anchors);
		this.bounds = computeBounds(this.anchors);
		this.coveredChunks = computeChunks(bounds);
		this.interactionProgress = Math.max(0, interactionProgress);
	}

	public UUID id() {
		return id;
	}

	public RegistryKey<World> worldKey() {
		return worldKey;
	}

	public UUID ownerUuid() {
		return ownerUuid;
	}

	public String ownerName() {
		return ownerName;
	}

	public void setOwnerName(String ownerName) {
		this.ownerName = ownerName == null ? "" : ownerName;
	}

	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name == null ? "" : name.trim();
	}

	public Set<BlockPos> anchors() {
		return Set.copyOf(anchors);
	}

	public Box bounds() {
		return bounds;
	}

	public Box runtimeBounds(ServerWorld world) {
		return new Box(bounds.minX, world.getBottomY(), bounds.minZ, bounds.maxX, world.getTopYInclusive(), bounds.maxZ);
	}

	public Box particleBounds(GlowFieldConfig config) {
		double maxY = bounds.minY + Math.max(0, config.particleRenderHeightBlocks - 1);
		return new Box(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, maxY, bounds.maxZ);
	}

	public Set<ChunkPos> coveredChunks() {
		return Set.copyOf(coveredChunks);
	}

	public int interactionProgress() {
		return interactionProgress;
	}

	public int minAnchorCharge(ServerWorld world) {
		int minCharge = Integer.MAX_VALUE;
		for (BlockPos anchor : anchors) {
			BlockState state = world.getBlockState(anchor);
			if (!state.isOf(net.minecraft.block.Blocks.RESPAWN_ANCHOR) || !state.contains(RespawnAnchorBlock.CHARGES)) {
				return 0;
			}
			minCharge = Math.min(minCharge, state.get(RespawnAnchorBlock.CHARGES));
		}
		return minCharge == Integer.MAX_VALUE ? 0 : minCharge;
	}

	public ZoneState state(ServerWorld world, GlowFieldConfig config) {
		int minCharge = minAnchorCharge(world);
		if (minCharge < config.partialMinCharge) {
			return ZoneState.INACTIVE;
		}
		if (interactionProgress >= config.degradingStateThreshold && minCharge > 0) {
			return ZoneState.DEGRADING;
		}
		if (minCharge >= config.forceFieldMinCharge) {
			return ZoneState.FORCE_FIELD;
		}
		return ZoneState.PARTIAL;
	}

	public boolean contains(BlockPos pos) {
		return pos.getX() >= bounds.minX && pos.getX() <= bounds.maxX
			&& pos.getZ() >= bounds.minZ && pos.getZ() <= bounds.maxZ;
	}

	public boolean contains(ServerWorld world, Box box) {
		Box runtimeBounds = runtimeBounds(world);
		return box.minX >= runtimeBounds.minX && box.maxX <= runtimeBounds.maxX + 1.0
			&& box.minY >= runtimeBounds.minY && box.maxY <= runtimeBounds.maxY + 1.0
			&& box.minZ >= runtimeBounds.minZ && box.maxZ <= runtimeBounds.maxZ + 1.0;
	}

	public boolean recordInteractionAndShouldDegrade(int interactionCount, GlowFieldConfig config) {
		interactionProgress += interactionCount;
		if (interactionProgress < config.degradeEveryMobInteractions) {
			return false;
		}
		interactionProgress -= config.degradeEveryMobInteractions;
		return true;
	}

	public void resetProgress() {
		interactionProgress = 0;
	}

	public void writeNbt(NbtCompound tag) {
		tag.putString("id", id.toString());
		tag.putString("world", worldKey.getValue().toString());
		tag.putString("owner_uuid", ownerUuid.toString());
		tag.putString("owner_name", ownerName == null ? "" : ownerName);
		tag.putString("name", name == null ? "" : name);
		tag.putInt("interaction_progress", interactionProgress);

		NbtList anchorList = new NbtList();
		for (BlockPos anchor : sortedAnchors(anchors)) {
			NbtCompound anchorTag = new NbtCompound();
			anchorTag.putInt("x", anchor.getX());
			anchorTag.putInt("y", anchor.getY());
			anchorTag.putInt("z", anchor.getZ());
			anchorList.add(anchorTag);
		}
		tag.put("anchors", anchorList);

		NbtCompound boundsTag = new NbtCompound();
		boundsTag.putInt("min_x", (int) bounds.minX);
		boundsTag.putInt("min_y", (int) bounds.minY);
		boundsTag.putInt("min_z", (int) bounds.minZ);
		boundsTag.putInt("max_x", (int) bounds.maxX);
		boundsTag.putInt("max_y", (int) bounds.maxY);
		boundsTag.putInt("max_z", (int) bounds.maxZ);
		tag.put("bounds", boundsTag);
	}

	public static GlowZone fromNbt(NbtCompound tag) {
		List<BlockPos> anchors = new ArrayList<>();
		NbtList anchorList = tag.getListOrEmpty("anchors");
		for (int i = 0; i < anchorList.size(); i++) {
			NbtCompound anchorTag = anchorList.getCompoundOrEmpty(i);
			anchors.add(new BlockPos(anchorTag.getInt("x", 0), anchorTag.getInt("y", 0), anchorTag.getInt("z", 0)));
		}

		return new GlowZone(
			UUID.fromString(tag.getString("id", UUID.randomUUID().toString())),
			RegistryKey.of(RegistryKeys.WORLD, Identifier.of(tag.getString("world", "minecraft:overworld"))),
			UUID.fromString(tag.getString("owner_uuid", UUID.randomUUID().toString())),
			tag.getString("owner_name", ""),
			tag.getString("name", ""),
			anchors,
			tag.getInt("interaction_progress", 0)
		);
	}

	public static Box computeBounds(Collection<BlockPos> anchors) {
		int minX = anchors.stream().mapToInt(BlockPos::getX).min().orElse(0);
		int maxX = anchors.stream().mapToInt(BlockPos::getX).max().orElse(0);
		int minY = anchors.stream().mapToInt(BlockPos::getY).min().orElse(0);
		int maxY = anchors.stream().mapToInt(BlockPos::getY).max().orElse(0);
		int minZ = anchors.stream().mapToInt(BlockPos::getZ).min().orElse(0);
		int maxZ = anchors.stream().mapToInt(BlockPos::getZ).max().orElse(0);
		return new Box(minX, minY, minZ, maxX, maxY, maxZ);
	}

	public static Set<ChunkPos> computeChunks(Box bounds) {
		int minChunkX = ((int) Math.floor(bounds.minX)) >> 4;
		int maxChunkX = ((int) Math.floor(bounds.maxX)) >> 4;
		int minChunkZ = ((int) Math.floor(bounds.minZ)) >> 4;
		int maxChunkZ = ((int) Math.floor(bounds.maxZ)) >> 4;

		Set<ChunkPos> chunks = new LinkedHashSet<>();
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				chunks.add(new ChunkPos(chunkX, chunkZ));
			}
		}
		return chunks;
	}

	public static List<BlockPos> sortedAnchors(Collection<BlockPos> anchors) {
		return anchors.stream()
			.sorted(Comparator.comparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ))
			.toList();
	}
}
