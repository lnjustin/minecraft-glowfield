package lnjustin.glowfield.zone;

import lnjustin.glowfield.config.GlowFieldConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ZoneRegistry {
	private final GlowFieldConfig config;
	private final Map<UUID, GlowZone> zones = new LinkedHashMap<>();
	private final Map<RegistryKey<World>, Map<ChunkPos, Set<UUID>>> chunkIndex = new HashMap<>();
	private final Map<RegistryKey<World>, Map<BlockPos, Set<UUID>>> anchorToZones = new HashMap<>();
	private final Map<RegistryKey<World>, Map<Integer, Set<BlockPos>>> anchorsByY = new HashMap<>();
	private final Map<RegistryKey<World>, Set<UUID>> loadedZoneIds = new HashMap<>();
	private final Map<ZoneSignature, UUID> zoneSignatures = new HashMap<>();
	private final Map<PendingPlacementKey, PlacementClaim> pendingPlacements = new HashMap<>();
	private MinecraftServer server;
	private boolean dirty;

	public ZoneRegistry(GlowFieldConfig config) {
		this.config = config;
	}

	public void load(MinecraftServer server) {
		this.server = server;
		clearRuntimeState();
		for (GlowZone zone : ZoneStorage.load(server)) {
			ServerWorld world = server.getWorld(zone.worldKey());
			if (world == null || !validateZone(world, zone.anchors())) {
				continue;
			}
			registerZone(zone);
		}
		dirty = false;
	}

	public void save(MinecraftServer server) {
		if (!dirty) {
			return;
		}
		ZoneStorage.save(server, zones.values());
		dirty = false;
	}

	public void onChunkLoad(ServerWorld world, WorldChunk chunk) {
		indexChunkAnchors(world, chunk);
		loadedZoneIds
			.computeIfAbsent(world.getRegistryKey(), key -> new HashSet<>())
			.addAll(chunkIndex.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>()).getOrDefault(chunk.getPos(), Set.of()));
	}

	public void onChunkUnload(ServerWorld world, WorldChunk chunk) {
		Set<UUID> active = loadedZoneIds.computeIfAbsent(world.getRegistryKey(), key -> new HashSet<>());
		active.removeAll(chunkIndex.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>()).getOrDefault(chunk.getPos(), Set.of()));
	}

	public void onEndWorldTick(ServerWorld world) {
		long time = world.getTime();
		if (config.particlesEnabled && time % config.particleIntervalTicks == 0) {
			renderWorldParticles(world);
		}
		if (time % config.entitySweepIntervalTicks == 0) {
			sweepWorldEntities(world);
		}
		if (time % config.saveFlushIntervalTicks == 0 && server != null) {
			save(server);
		}
	}

	public void onEntityLoad(Entity entity, ServerWorld world) {
		GlowZone zone = findFirstZoneContaining(world, entity.getBoundingBox());
		if (zone == null || !(entity instanceof MobEntity mob)) {
			return;
		}
		if (config.suppressMobSpawns && zone.state(world, config) != ZoneState.INACTIVE) {
			mob.discard();
			recordZoneInteraction(world, zone, 1);
		}
	}

	public void onPlayerJoin(ServerPlayerEntity player) {
		findFirstZoneContaining((ServerWorld) player.getEntityWorld(), player.getBoundingBox());
	}

	public boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (config.pvpEnabled || !(entity instanceof ServerPlayerEntity targetPlayer)) {
			return true;
		}
		Entity attacker = source.getAttacker();
		if (!(attacker instanceof ServerPlayerEntity attackingPlayer)) {
			return true;
		}

		GlowZone targetZone = findFirstZoneContaining((ServerWorld) targetPlayer.getEntityWorld(), targetPlayer.getBoundingBox());
		GlowZone attackerZone = findFirstZoneContaining((ServerWorld) attackingPlayer.getEntityWorld(), attackingPlayer.getBoundingBox());
		return targetZone == null && attackerZone == null;
	}

	public void onBlockBroken(ServerWorld world, BlockPos pos, BlockState oldState, @Nullable ServerPlayerEntity player) {
		if (oldState.isOf(Blocks.RESPAWN_ANCHOR)) {
			handleAnchorTransition(world, pos, oldState, Blocks.AIR.getDefaultState(), player);
		}
	}

	public ActionResult onUseBlock(ServerWorld world, ServerPlayerEntity player, Hand hand, BlockHitResult hitResult) {
		ItemStack stack = player.getStackInHand(hand);
		BlockPos hitPos = hitResult.getBlockPos();
		BlockState state = world.getBlockState(hitPos);

		if (stack.isOf(Items.RESPAWN_ANCHOR)) {
			recordPotentialAnchorPlacement(world, player, hitPos);
			recordPotentialAnchorPlacement(world, player, hitPos.offset(hitResult.getSide()));
			return ActionResult.PASS;
		}

		if (config.nameTagNamingEnabled && stack.isOf(Items.NAME_TAG) && stack.getCustomName() != null && state.isOf(Blocks.RESPAWN_ANCHOR)) {
			GlowZone zone = findOwnedZoneByAnchor(world, hitPos, player.getUuid());
			if (zone == null) {
				return ActionResult.PASS;
			}

			renameZone(zone.id(), stack.getCustomName().getString());
			player.sendMessage(Text.literal("Zone renamed to " + describeZone(zone.id()) + "."), false);
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	public void onAnchorStateChanged(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
		handleAnchorTransition(world, pos, oldState, newState, consumePendingOwner(world, pos));
	}

	@Nullable
	public GlowZone findZoneContaining(ServerPlayerEntity player) {
		return findFirstZoneContaining((ServerWorld) player.getEntityWorld(), player.getBoundingBox());
	}

	@Nullable
	public GlowZone findOwnedZoneContaining(ServerPlayerEntity player) {
		GlowZone zone = findZoneContaining(player);
		if (zone == null || !zone.ownerUuid().equals(player.getUuid())) {
			return null;
		}

		zone.setOwnerName(player.getGameProfile().name());
		return zone;
	}

	public List<GlowZone> listOwnedZones(UUID ownerUuid) {
		return zones.values().stream()
			.filter(zone -> zone.ownerUuid().equals(ownerUuid))
			.sorted(Comparator.comparing(GlowZone::name).thenComparing(zone -> zone.id().toString()))
			.toList();
	}

	public void renameZone(UUID zoneId, String name) {
		GlowZone zone = zones.get(zoneId);
		if (zone == null) {
			return;
		}
		zone.setName(name);
		dirty = true;
	}

	public void removeZone(UUID zoneId) {
		GlowZone zone = zones.remove(zoneId);
		if (zone == null) {
			return;
		}

		zoneSignatures.remove(ZoneSignature.of(zone.worldKey(), zone.anchors()));
		removeZoneIndexes(zone);
		dirty = true;
	}

	public String describeZone(@Nullable UUID zoneId) {
		GlowZone zone = zoneId == null ? null : zones.get(zoneId);
		if (zone == null) {
			return "unnamed zone";
		}
		return zone.name().isBlank() ? zone.id().toString() : zone.name();
	}

	public String describeZoneVerbose(GlowZone zone) {
		return "Zone " + describeZone(zone.id())
			+ " owner=" + zone.ownerName()
			+ " world=" + zone.worldKey().getValue()
			+ " anchors=" + GlowZone.sortedAnchors(zone.anchors());
	}

	private void handleAnchorTransition(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState, @Nullable ServerPlayerEntity owner) {
		boolean wasAnchor = oldState.isOf(Blocks.RESPAWN_ANCHOR);
		boolean isAnchor = newState.isOf(Blocks.RESPAWN_ANCHOR);
		if (!wasAnchor && !isAnchor) {
			return;
		}

		if (!wasAnchor) {
			addAnchor(world.getRegistryKey(), pos);
			discoverZonesAt(world, pos.toImmutable(), owner);
			dirty = true;
			return;
		}

		if (!isAnchor) {
			removeAnchor(world.getRegistryKey(), pos);
			removeZonesForAnchor(world.getRegistryKey(), pos);
			dirty = true;
			return;
		}

		if (oldState.contains(RespawnAnchorBlock.CHARGES) && newState.contains(RespawnAnchorBlock.CHARGES)
			&& !oldState.get(RespawnAnchorBlock.CHARGES).equals(newState.get(RespawnAnchorBlock.CHARGES))) {
			dirty = true;
		}
	}

	private void discoverZonesAt(ServerWorld world, BlockPos pivot, @Nullable ServerPlayerEntity owner) {
		if (owner == null) {
			return;
		}

		Set<BlockPos> samePlane = anchorsByY.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>()).getOrDefault(pivot.getY(), Set.of());
		if (samePlane.size() < 4) {
			return;
		}

		for (BlockPos candidate : samePlane) {
			if (candidate.equals(pivot) || candidate.getX() == pivot.getX() || candidate.getZ() == pivot.getZ()) {
				continue;
			}

			Set<BlockPos> anchors = Set.of(
				pivot,
				candidate,
				new BlockPos(pivot.getX(), pivot.getY(), candidate.getZ()),
				new BlockPos(candidate.getX(), pivot.getY(), pivot.getZ())
			);

			if (!samePlane.containsAll(anchors) || !validateZone(world, anchors)) {
				continue;
			}

			ZoneSignature signature = ZoneSignature.of(world.getRegistryKey(), anchors);
			if (zoneSignatures.containsKey(signature)) {
				continue;
			}

			GlowZone zone = new GlowZone(UUID.randomUUID(), world.getRegistryKey(), owner.getUuid(), owner.getGameProfile().name(), "", anchors, 0);
			registerZone(zone);
			dirty = true;
			owner.sendMessage(Text.literal("Created GlowField zone " + describeZone(zone.id()) + "."), false);
		}
	}

	private void renderWorldParticles(ServerWorld world) {
		for (GlowZone zone : loadedZones(world)) {
			ZoneState state = zone.state(world, config);
			if (state == ZoneState.INACTIVE) {
				continue;
			}
			renderZoneParticles(world, zone, state);
		}
	}

	private void sweepWorldEntities(ServerWorld world) {
		for (GlowZone zone : loadedZones(world)) {
			ZoneState state = zone.state(world, config);
			if (state == ZoneState.INACTIVE) {
				continue;
			}

			for (Entity entity : world.getOtherEntities(null, zone.bounds().expand(1.0), checked -> !checked.isSpectator())) {
				if (!zone.contains(entity.getBlockPos())) {
					continue;
				}
				applyZoneEffects(world, zone, state, entity);
			}
		}
	}

	private void applyZoneEffects(ServerWorld world, GlowZone zone, ZoneState state, Entity entity) {
		if (entity instanceof MobEntity mob) {
			if (config.damageMobs && state == ZoneState.FORCE_FIELD) {
				boolean lethal = mob.getHealth() <= config.forceFieldDamage;
				mob.damage(world, world.getDamageSources().magic(), (float) config.forceFieldDamage);
				if (lethal) {
					recordZoneInteraction(world, zone, 1);
				}
			}
			return;
		}

		if (entity instanceof ServerPlayerEntity player && config.damagePlayers && state == ZoneState.FORCE_FIELD) {
			player.damage(world, world.getDamageSources().magic(), (float) config.forceFieldDamage);
		}
	}

	private void recordZoneInteraction(ServerWorld world, GlowZone zone, int interactions) {
		if (zone.recordInteractionAndShouldDegrade(interactions, config)) {
			for (BlockPos anchor : zone.anchors()) {
				BlockState state = world.getBlockState(anchor);
				if (state.isOf(Blocks.RESPAWN_ANCHOR) && state.contains(RespawnAnchorBlock.CHARGES)) {
					int nextCharge = Math.max(0, state.get(RespawnAnchorBlock.CHARGES) - 1);
					world.setBlockState(anchor, state.with(RespawnAnchorBlock.CHARGES, nextCharge));
				}
			}
		}
		dirty = true;
	}

	private void renderZoneParticles(ServerWorld world, GlowZone zone, ZoneState state) {
		ParticleEffect particle = config.particleFor(state);
		Box bounds = zone.bounds();
		double step = Math.max(0.5, config.particleStep);

		for (double x = bounds.minX; x <= bounds.maxX; x += step) {
			spawnColumn(world, particle, x, bounds.minY, bounds.maxY, bounds.minZ);
			spawnColumn(world, particle, x, bounds.minY, bounds.maxY, bounds.maxZ);
		}
		for (double z = bounds.minZ; z <= bounds.maxZ; z += step) {
			spawnColumn(world, particle, bounds.minX, bounds.minY, bounds.maxY, z);
			spawnColumn(world, particle, bounds.maxX, bounds.minY, bounds.maxY, z);
		}
		for (double x = bounds.minX; x <= bounds.maxX; x += step) {
			for (double z = bounds.minZ; z <= bounds.maxZ; z += step) {
				world.spawnParticles(particle, x + 0.5, bounds.minY + 0.5, z + 0.5, 1, 0, 0, 0, 0);
				world.spawnParticles(particle, x + 0.5, bounds.maxY + 0.5, z + 0.5, 1, 0, 0, 0, 0);
			}
		}
	}

	private void spawnColumn(ServerWorld world, ParticleEffect particle, double x, double minY, double maxY, double z) {
		double step = Math.max(0.5, config.particleStep);
		for (double y = minY; y <= maxY; y += step) {
			world.spawnParticles(particle, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
		}
	}

	private void registerZone(GlowZone zone) {
		zones.put(zone.id(), zone);
		zoneSignatures.put(ZoneSignature.of(zone.worldKey(), zone.anchors()), zone.id());

		for (BlockPos anchor : zone.anchors()) {
			addAnchor(zone.worldKey(), anchor);
			anchorToZones
				.computeIfAbsent(zone.worldKey(), key -> new HashMap<>())
				.computeIfAbsent(anchor, key -> new LinkedHashSet<>())
				.add(zone.id());
		}

		for (ChunkPos chunkPos : zone.coveredChunks()) {
			chunkIndex
				.computeIfAbsent(zone.worldKey(), key -> new HashMap<>())
				.computeIfAbsent(chunkPos, key -> new LinkedHashSet<>())
				.add(zone.id());
		}
	}

	private void removeZoneIndexes(GlowZone zone) {
		Map<BlockPos, Set<UUID>> zoneAnchors = anchorToZones.get(zone.worldKey());
		if (zoneAnchors != null) {
			for (BlockPos anchor : zone.anchors()) {
				Set<UUID> ids = zoneAnchors.get(anchor);
				if (ids != null) {
					ids.remove(zone.id());
					if (ids.isEmpty()) {
						zoneAnchors.remove(anchor);
					}
				}
			}
		}

		Map<ChunkPos, Set<UUID>> zoneChunks = chunkIndex.get(zone.worldKey());
		if (zoneChunks != null) {
			for (ChunkPos chunkPos : zone.coveredChunks()) {
				Set<UUID> ids = zoneChunks.get(chunkPos);
				if (ids != null) {
					ids.remove(zone.id());
					if (ids.isEmpty()) {
						zoneChunks.remove(chunkPos);
					}
				}
			}
		}

		Set<UUID> active = loadedZoneIds.get(zone.worldKey());
		if (active != null) {
			active.remove(zone.id());
		}
	}

	private void removeZonesForAnchor(RegistryKey<World> worldKey, BlockPos anchor) {
		Set<UUID> ids = new HashSet<>(anchorToZones.computeIfAbsent(worldKey, key -> new HashMap<>()).getOrDefault(anchor, Set.of()));
		for (UUID zoneId : ids) {
			removeZone(zoneId);
		}
	}

	private void addAnchor(RegistryKey<World> worldKey, BlockPos pos) {
		anchorsByY
			.computeIfAbsent(worldKey, key -> new HashMap<>())
			.computeIfAbsent(pos.getY(), key -> new LinkedHashSet<>())
			.add(pos.toImmutable());
	}

	private void removeAnchor(RegistryKey<World> worldKey, BlockPos pos) {
		Map<Integer, Set<BlockPos>> byY = anchorsByY.get(worldKey);
		if (byY == null) {
			return;
		}
		Set<BlockPos> anchors = byY.get(pos.getY());
		if (anchors != null) {
			anchors.remove(pos);
			if (anchors.isEmpty()) {
				byY.remove(pos.getY());
			}
		}
	}

	private void indexChunkAnchors(ServerWorld world, WorldChunk chunk) {
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		int bottomY = world.getBottomY();
		int topY = world.getTopYInclusive();

		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				for (int y = bottomY; y <= topY; y++) {
					BlockPos pos = new BlockPos(startX + x, y, startZ + z);
					if (world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)) {
						addAnchor(world.getRegistryKey(), pos);
					}
				}
			}
		}
	}

	private boolean validateZone(ServerWorld world, Collection<BlockPos> anchors) {
		if (anchors.size() != 4) {
			return false;
		}

		Set<Integer> xs = new HashSet<>();
		Set<Integer> ys = new HashSet<>();
		Set<Integer> zs = new HashSet<>();
		for (BlockPos anchor : anchors) {
			if (!world.getBlockState(anchor).isOf(Blocks.RESPAWN_ANCHOR)) {
				return false;
			}
			xs.add(anchor.getX());
			ys.add(anchor.getY());
			zs.add(anchor.getZ());
		}

		if (xs.size() != 2 || ys.size() != 1 || zs.size() != 2) {
			return false;
		}

		List<Integer> orderedX = xs.stream().sorted().toList();
		List<Integer> orderedZ = zs.stream().sorted().toList();
		int y = ys.iterator().next();
		Set<BlockPos> expected = Set.of(
			new BlockPos(orderedX.get(0), y, orderedZ.get(0)),
			new BlockPos(orderedX.get(0), y, orderedZ.get(1)),
			new BlockPos(orderedX.get(1), y, orderedZ.get(0)),
			new BlockPos(orderedX.get(1), y, orderedZ.get(1))
		);
		return expected.equals(new HashSet<>(anchors));
	}

	@Nullable
	private GlowZone findFirstZoneContaining(ServerWorld world, Box box) {
		int minChunkX = BlockPos.ofFloored(box.minX, 0, 0).getX() >> 4;
		int maxChunkX = BlockPos.ofFloored(box.maxX, 0, 0).getX() >> 4;
		int minChunkZ = BlockPos.ofFloored(0, 0, box.minZ).getZ() >> 4;
		int maxChunkZ = BlockPos.ofFloored(0, 0, box.maxZ).getZ() >> 4;

		Map<ChunkPos, Set<UUID>> worldChunks = chunkIndex.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>());
		Set<UUID> candidates = new LinkedHashSet<>();
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				candidates.addAll(worldChunks.getOrDefault(new ChunkPos(chunkX, chunkZ), Set.of()));
			}
		}

		for (UUID zoneId : candidates) {
			GlowZone zone = zones.get(zoneId);
			if (zone != null && zone.contains(box)) {
				return zone;
			}
		}
		return null;
	}

	@Nullable
	private GlowZone findOwnedZoneByAnchor(ServerWorld world, BlockPos anchor, UUID ownerUuid) {
		for (UUID zoneId : anchorToZones.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>()).getOrDefault(anchor, Set.of())) {
			GlowZone zone = zones.get(zoneId);
			if (zone != null && zone.ownerUuid().equals(ownerUuid)) {
				return zone;
			}
		}
		return null;
	}

	private List<GlowZone> loadedZones(ServerWorld world) {
		List<GlowZone> loaded = new ArrayList<>();
		for (UUID zoneId : loadedZoneIds.computeIfAbsent(world.getRegistryKey(), key -> new HashSet<>())) {
			GlowZone zone = zones.get(zoneId);
			if (zone != null) {
				loaded.add(zone);
			}
		}
		return loaded;
	}

	private void recordPotentialAnchorPlacement(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
		pendingPlacements.put(new PendingPlacementKey(world.getRegistryKey(), pos.toImmutable()), new PlacementClaim(player.getUuid(), world.getTime()));
	}

	@Nullable
	private ServerPlayerEntity consumePendingOwner(ServerWorld world, BlockPos pos) {
		PlacementClaim claim = pendingPlacements.remove(new PendingPlacementKey(world.getRegistryKey(), pos.toImmutable()));
		if (claim == null || world.getTime() - claim.recordedTick() > 2 || server == null) {
			return null;
		}
		return server.getPlayerManager().getPlayer(claim.ownerUuid());
	}

	private void clearRuntimeState() {
		zones.clear();
		chunkIndex.clear();
		anchorToZones.clear();
		anchorsByY.clear();
		loadedZoneIds.clear();
		zoneSignatures.clear();
		pendingPlacements.clear();
	}

	private record ZoneSignature(RegistryKey<World> worldKey, List<BlockPos> anchors) {
		private static ZoneSignature of(RegistryKey<World> worldKey, Collection<BlockPos> anchors) {
			return new ZoneSignature(worldKey, GlowZone.sortedAnchors(anchors));
		}
	}

	private record PendingPlacementKey(RegistryKey<World> worldKey, BlockPos pos) {
	}

	private record PlacementClaim(UUID ownerUuid, long recordedTick) {
	}
}
