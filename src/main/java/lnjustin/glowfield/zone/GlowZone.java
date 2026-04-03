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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GlowZone {
	private final UUID id;
	private final RegistryKey<World> worldKey;
	private final UUID ownerUuid;
	private String ownerName;
	private String name;
	private final LinkedHashSet<BlockPos> anchors;
	private final LinkedHashMap<UUID, String> members;
	private final Box bounds;
	private final Set<ChunkPos> coveredChunks;
	private int interactionProgress;
	private boolean hostileToNonMembers;
	private long remainingActiveTicks;
	private long lastUpdatedTick;
	private int hostilePlayerDeathsRemaining;
	private String lastAnnouncedMode;

	public GlowZone(UUID id, RegistryKey<World> worldKey, UUID ownerUuid, String ownerName, String name, Collection<BlockPos> anchors, int interactionProgress) {
		this(id, worldKey, ownerUuid, ownerName, name, anchors, interactionProgress, Map.of(), false, 0, -1, 0);
	}

	public GlowZone(
		UUID id,
		RegistryKey<World> worldKey,
		UUID ownerUuid,
		String ownerName,
		String name,
		Collection<BlockPos> anchors,
		int interactionProgress,
		Map<UUID, String> members,
		boolean hostileToNonMembers,
		long remainingActiveTicks,
		long lastUpdatedTick,
		int hostilePlayerDeathsRemaining
	) {
		this.id = id;
		this.worldKey = worldKey;
		this.ownerUuid = ownerUuid;
		this.ownerName = ownerName;
		this.name = name == null ? "" : name;
		this.anchors = new LinkedHashSet<>(anchors);
		this.members = new LinkedHashMap<>(members);
		this.bounds = computeBounds(this.anchors);
		this.coveredChunks = computeChunks(bounds);
		this.interactionProgress = Math.max(0, interactionProgress);
		this.hostileToNonMembers = hostileToNonMembers;
		this.remainingActiveTicks = Math.max(0, remainingActiveTicks);
		this.lastUpdatedTick = lastUpdatedTick;
		this.hostilePlayerDeathsRemaining = Math.max(0, hostilePlayerDeathsRemaining);
		this.lastAnnouncedMode = null;
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

	public Map<UUID, String> members() {
		return Map.copyOf(members);
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

	public boolean isMember(UUID uuid) {
		return ownerUuid.equals(uuid) || members.containsKey(uuid);
	}

	public boolean hostileToNonMembers() {
		return hostileToNonMembers;
	}

	public long remainingActiveTicks() {
		return remainingActiveTicks;
	}

	public int hostilePlayerDeathsRemaining() {
		return hostilePlayerDeathsRemaining;
	}

	public String currentModeKey(ServerWorld world, GlowFieldConfig config) {
		ZoneState state = state(world, config);
		if (state == ZoneState.FORCE_FIELD && isHostileProtectionActive(world, config)) {
			return "hostile_force_field";
		}
		return state.serializedName();
	}

	public String currentModeLabel(ServerWorld world, GlowFieldConfig config) {
		return switch (currentModeKey(world, config)) {
			case "partial" -> "Spawn Prevention Mode";
			case "force_field" -> "Force Field Mode";
			case "degrading" -> "Degrading Mode";
			case "hostile_force_field" -> "Hostile Force Field Mode";
			default -> "Inactive Mode";
		};
	}

	public boolean markModeAnnounced(String modeKey) {
		if (modeKey.equals(lastAnnouncedMode)) {
			return false;
		}
		lastAnnouncedMode = modeKey;
		return true;
	}

	public void addMember(UUID uuid, String name) {
		if (ownerUuid.equals(uuid)) {
			return;
		}
		members.put(uuid, name == null ? "" : name);
	}

	public void removeMember(UUID uuid) {
		members.remove(uuid);
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
		syncRuntime(world, config);
		int minCharge = minAnchorCharge(world);
		if (minCharge < config.partialMinCharge || remainingActiveTicks <= 0) {
			return ZoneState.INACTIVE;
		}
		if (hostileToNonMembers && hostilePlayerDeathsRemaining <= 0) {
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

	public void syncRuntime(ServerWorld world, GlowFieldConfig config) {
		long currentTick = world.getTime();
		if (lastUpdatedTick < 0) {
			lastUpdatedTick = currentTick;
			if (remainingActiveTicks <= 0) {
				remainingActiveTicks = maxDurationTicks(world, config);
			}
			if (hostileToNonMembers && hostilePlayerDeathsRemaining <= 0) {
				hostilePlayerDeathsRemaining = Math.max(0, config.hostileFieldPlayerDeathLimit);
			}
			return;
		}

		long elapsed = Math.max(0, currentTick - lastUpdatedTick);
		lastUpdatedTick = currentTick;
		if (elapsed <= 0 || remainingActiveTicks <= 0 || minAnchorCharge(world) < config.partialMinCharge) {
			return;
		}

		remainingActiveTicks = Math.max(0, remainingActiveTicks - elapsed);
	}

	public void resetForCurrentCharge(ServerWorld world, GlowFieldConfig config) {
		hostileToNonMembers = false;
		interactionProgress = 0;
		remainingActiveTicks = maxDurationTicks(world, config);
		lastUpdatedTick = world.getTime();
		hostilePlayerDeathsRemaining = 0;
	}

	public void armAgainstNonMembers(ServerWorld world, GlowFieldConfig config) {
		hostileToNonMembers = true;
		interactionProgress = 0;
		remainingActiveTicks = maxDurationTicks(world, config);
		lastUpdatedTick = world.getTime();
		hostilePlayerDeathsRemaining = Math.max(0, config.hostileFieldPlayerDeathLimit);
	}

	public boolean consumesPlayerDeath(ServerWorld world, GlowFieldConfig config) {
		syncRuntime(world, config);
		if (!hostileToNonMembers || hostilePlayerDeathsRemaining <= 0) {
			return false;
		}

		hostilePlayerDeathsRemaining--;
		if (hostilePlayerDeathsRemaining <= 0) {
			remainingActiveTicks = 0;
		}
		return true;
	}

	public boolean isHostileProtectionActive(ServerWorld world, GlowFieldConfig config) {
		if (!config.allowNonMemberPlayerDamage) {
			return false;
		}

		return hostileToNonMembers && state(world, config) == ZoneState.FORCE_FIELD;
	}

	private long maxDurationTicks(ServerWorld world, GlowFieldConfig config) {
		int minCharge = minAnchorCharge(world);
		if (minCharge < config.partialMinCharge) {
			return 0;
		}
		if (hostileToNonMembers && minCharge >= config.forceFieldMinCharge) {
			return Math.max(0, config.hostileForceFieldDurationTicks);
		}
		if (minCharge >= config.forceFieldMinCharge) {
			return Math.max(0, config.forceFieldDurationTicks);
		}
		return Math.max(0, config.partialFieldDurationTicks);
	}

	public void writeNbt(NbtCompound tag) {
		tag.putString("id", id.toString());
		tag.putString("world", worldKey.getValue().toString());
		tag.putString("owner_uuid", ownerUuid.toString());
		tag.putString("owner_name", ownerName == null ? "" : ownerName);
		tag.putString("name", name == null ? "" : name);
		tag.putInt("interaction_progress", interactionProgress);
		tag.putBoolean("hostile_to_non_members", hostileToNonMembers);
		tag.putLong("remaining_active_ticks", remainingActiveTicks);
		tag.putLong("last_updated_tick", lastUpdatedTick);
		tag.putInt("hostile_player_deaths_remaining", hostilePlayerDeathsRemaining);

		NbtList anchorList = new NbtList();
		for (BlockPos anchor : sortedAnchors(anchors)) {
			NbtCompound anchorTag = new NbtCompound();
			anchorTag.putInt("x", anchor.getX());
			anchorTag.putInt("y", anchor.getY());
			anchorTag.putInt("z", anchor.getZ());
			anchorList.add(anchorTag);
		}
		tag.put("anchors", anchorList);

		NbtList memberList = new NbtList();
		for (Map.Entry<UUID, String> entry : members.entrySet()) {
			NbtCompound memberTag = new NbtCompound();
			memberTag.putString("uuid", entry.getKey().toString());
			memberTag.putString("name", entry.getValue() == null ? "" : entry.getValue());
			memberList.add(memberTag);
		}
		tag.put("members", memberList);

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

		Map<UUID, String> members = new LinkedHashMap<>();
		NbtList memberList = tag.getListOrEmpty("members");
		for (int i = 0; i < memberList.size(); i++) {
			NbtCompound memberTag = memberList.getCompoundOrEmpty(i);
			String uuid = memberTag.getString("uuid", "");
			try {
				members.put(UUID.fromString(uuid), memberTag.getString("name", ""));
			} catch (IllegalArgumentException ignored) {
			}
		}

		return new GlowZone(
			UUID.fromString(tag.getString("id", UUID.randomUUID().toString())),
			RegistryKey.of(RegistryKeys.WORLD, Identifier.of(tag.getString("world", "minecraft:overworld"))),
			UUID.fromString(tag.getString("owner_uuid", UUID.randomUUID().toString())),
			tag.getString("owner_name", ""),
			tag.getString("name", ""),
			anchors,
			tag.getInt("interaction_progress", 0),
			members,
			tag.getBoolean("hostile_to_non_members", false),
			tag.getLong("remaining_active_ticks", 0),
			tag.getLong("last_updated_tick", -1),
			tag.getInt("hostile_player_deaths_remaining", 0)
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
