package lnjustin.glowfield.zone;

import lnjustin.glowfield.config.GlowFieldConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

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
	private final Map<PendingHostileActivationKey, Set<BlockPos>> pendingHostileActivations = new HashMap<>();
	private final Map<UUID, ZoneDamageRecord> recentPlayerZoneDamage = new HashMap<>();
	private final Map<AnchorDamageKey, AnchorDamageProgress> anchorDamage = new HashMap<>();
	private final Map<UUID, Long> lastHostileDamageTicks = new HashMap<>();
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
		syncZonesForSave(server);
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
		boolean changed = false;
		for (GlowZone zone : loadedZones(world)) {
			long before = zone.remainingActiveTicks();
			zone.syncRuntime(world, config);
			maybeNotifyZoneMode(world, zone);
			if (zone.remainingActiveTicks() != before) {
				changed = true;
			}
		}
		if (changed) {
			dirty = true;
		}

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

	public void onEntityDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayerEntity player)) {
			return;
		}

		ZoneDamageRecord record = recentPlayerZoneDamage.remove(player.getUuid());
		if (record == null) {
			return;
		}

		ServerWorld world = (ServerWorld) player.getEntityWorld();
		if (world.getTime() - record.recordedTick() > Math.max(40, config.entitySweepIntervalTicks * 4L)) {
			return;
		}

		GlowZone zone = zones.get(record.zoneId());
		if (zone == null || !zone.worldKey().equals(world.getRegistryKey())) {
			return;
		}

		if (zone.consumesPlayerDeath(world, config)) {
			dirty = true;
		}
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

	public boolean beforeBlockBreak(ServerWorld world, ServerPlayerEntity player, BlockPos pos, BlockState state) {
		if (!state.isOf(Blocks.RESPAWN_ANCHOR)) {
			return true;
		}

		GlowZone hostileZone = findHostileZoneForAnchor(world, pos, player.getUuid());
		if (hostileZone == null) {
			return true;
		}

		AnchorDamageKey key = new AnchorDamageKey(world.getRegistryKey(), pos.toImmutable());
		AnchorDamageProgress progress = anchorDamage.computeIfAbsent(key, unused -> new AnchorDamageProgress(0, world.getTime()));
		progress = decayedProgress(progress, world.getTime());
		int nextHits = progress.hits() + 1;
		if (nextHits >= Math.max(1, config.anchorBreakHitsRequired)) {
			anchorDamage.remove(key);
			dirty = true;
			return true;
		}

		anchorDamage.put(key, new AnchorDamageProgress(nextHits, world.getTime()));
		applyAnchorBacklash(world, pos, player, hostileZone);
		player.sendMessage(Text.literal("Anchor Damage: " + nextHits + "/" + Math.max(1, config.anchorBreakHitsRequired) + "."), true);
		dirty = true;
		return false;
	}

	public void onBlockBroken(ServerWorld world, BlockPos pos, BlockState oldState, @Nullable ServerPlayerEntity player) {
		if (oldState.isOf(Blocks.RESPAWN_ANCHOR)) {
			anchorDamage.remove(new AnchorDamageKey(world.getRegistryKey(), pos.toImmutable()));
			triggerAnchorDestroyedExplosion(world, pos, player);
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

		if (state.isOf(Blocks.RESPAWN_ANCHOR)
			&& config.allowNonMemberPlayerDamage
			&& stack.isOf(config.hostileFieldActivationItem())) {
			return handleHostileFieldActivation(world, player, hand, hitPos);
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

	public boolean addMember(UUID zoneId, ServerPlayerEntity member) {
		GlowZone zone = zones.get(zoneId);
		if (zone == null) {
			return false;
		}
		zone.addMember(member.getUuid(), member.getGameProfile().name());
		dirty = true;
		return true;
	}

	public boolean removeMember(UUID zoneId, UUID memberUuid) {
		GlowZone zone = zones.get(zoneId);
		if (zone == null || !zone.members().containsKey(memberUuid)) {
			return false;
		}
		zone.removeMember(memberUuid);
		dirty = true;
		return true;
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

	private ActionResult handleHostileFieldActivation(ServerWorld world, ServerPlayerEntity player, Hand hand, BlockPos anchor) {
		GlowZone zone = findOwnedZoneByAnchor(world, anchor, player.getUuid());
		if (zone == null) {
			return ActionResult.PASS;
		}
		if (zone.hostileToNonMembers()) {
			player.sendMessage(Text.literal("Zone " + describeZone(zone.id()) + " is already overcharged to be hostile against non-members."), false);
			return ActionResult.SUCCESS;
		}
		if (zone.state(world, config) != ZoneState.FORCE_FIELD) {
			player.sendMessage(Text.literal("The zone must be fully charged before it can be hostile against non-members."), false);
			return ActionResult.SUCCESS;
		}

		ItemStack stack = player.getStackInHand(hand);
		if (stack.isEmpty()) {
			return ActionResult.PASS;
		}

		PendingHostileActivationKey key = new PendingHostileActivationKey(zone.id(), player.getUuid());
		Set<BlockPos> activatedAnchors = pendingHostileActivations.computeIfAbsent(key, unused -> new HashSet<>());
		BlockPos immutableAnchor = anchor.toImmutable();
		if (activatedAnchors.contains(immutableAnchor)) {
			player.sendMessage(Text.literal("That anchor is already overcharged for hostility against non-members."), false);
			return ActionResult.SUCCESS;
		}

		activatedAnchors.add(immutableAnchor);
		stack.decrement(1);
		dirty = true;

		if (activatedAnchors.containsAll(zone.anchors())) {
			zone.armAgainstNonMembers(world, config);
			pendingHostileActivations.remove(key);
			player.sendMessage(Text.literal("Zone " + describeZone(zone.id()) + " is now overcharged for hostile mode against non-members."), false);
			maybeNotifyZoneMode(world, zone);
		} else {
			player.sendMessage(Text.literal("Overcharged " + activatedAnchors.size() + "/" + zone.anchors().size() + " anchors of " + describeZone(zone.id()) + " to be hostile against non-members."), false);
		}

		return ActionResult.SUCCESS;
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
			+ " anchors=" + GlowZone.sortedAnchors(zone.anchors())
			+ " members=" + zone.members().values()
			+ " hostile=" + zone.hostileToNonMembers()
			+ " remaining_ticks=" + zone.remainingActiveTicks();
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
			resetZonesForAnchor(world, pos);
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

			for (Entity entity : world.getOtherEntities(null, zone.runtimeBounds(world).expand(1.0), checked -> !checked.isSpectator() && !(checked instanceof ServerPlayerEntity))) {
				if (!zone.contains(entity.getBlockPos())) {
					continue;
				}
				applyZoneEffects(world, zone, state, entity);
			}

			for (ServerPlayerEntity player : world.getPlayers()) {
				if (player.isSpectator() || !zone.contains(player.getBlockPos())) {
					continue;
				}
				logHostileZoneCheck(world, zone, state, player, "player-inside-zone");
				applyZoneEffects(world, zone, state, player);
			}
		}
	}

	private void applyZoneEffects(ServerWorld world, GlowZone zone, ZoneState state, Entity entity) {
		if (entity instanceof MobEntity mob) {
			if (config.damageMobs && state == ZoneState.FORCE_FIELD) {
				boolean wasAlive = mob.isAlive();
				mob.damage(world, world.getDamageSources().magic(), Float.MAX_VALUE);
				if (wasAlive && !mob.isAlive()) {
					recordZoneInteraction(world, zone, 1);
				}
			}
			return;
		}

		if (entity instanceof ServerPlayerEntity player && state == ZoneState.FORCE_FIELD) {
			boolean hostileActive = zone.isHostileProtectionActive(world, config);
			boolean member = zone.isMember(player.getUuid());
			logHostileZoneCheck(world, zone, state, player, "apply-effects hostileActive=" + hostileActive + " member=" + member);
			if (hostileActive && !member) {
				long now = world.getTime();
				Long lastTick = lastHostileDamageTicks.get(player.getUuid());
				boolean intervalElapsed = lastTick == null
					|| config.hostileFieldDamageIntervalTicks <= 0
					|| now >= lastTick + config.hostileFieldDamageIntervalTicks;
				if (intervalElapsed) {
					debugLog(
						"damaging non-member player={} zone={} now={} lastTick={} interval={} damage={}",
						player.getGameProfile().name(),
						describeZone(zone.id()),
						now,
						lastTick,
						config.hostileFieldDamageIntervalTicks,
						config.hostileFieldDamage
					);
					player.damage(world, world.getDamageSources().magic(), (float) config.hostileFieldDamage);
					recentPlayerZoneDamage.put(player.getUuid(), new ZoneDamageRecord(zone.id(), now));
					lastHostileDamageTicks.put(player.getUuid(), now);
					dirty = true;
				} else {
					debugLog(
						"skipped hostile damage due to interval player={} zone={} now={} lastTick={} interval={}",
						player.getGameProfile().name(),
						describeZone(zone.id()),
						now,
						lastTick,
						config.hostileFieldDamageIntervalTicks
					);
				}
				return;
			}

			if (config.damagePlayers) {
				player.damage(world, world.getDamageSources().magic(), (float) config.forceFieldDamage);
			}
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
		if (!shouldRenderParticlesForState(state)) {
			return;
		}

		ParticleEffect particle = zone.isHostileProtectionActive(world, config) ? config.hostileFieldParticle() : config.particleFor(state);
		Box bounds = zone.particleBounds(config);
		double step = Math.max(0.5, config.particleStep);

		if (config.renderVerticalParticleEdges) {
			for (double x = bounds.minX; x <= bounds.maxX; x += step) {
				spawnColumn(world, particle, x, particleColumnMinY(world, bounds, x, bounds.minZ), bounds.maxY, bounds.minZ);
				spawnColumn(world, particle, x, particleColumnMinY(world, bounds, x, bounds.maxZ), bounds.maxY, bounds.maxZ);
			}
			for (double z = bounds.minZ; z <= bounds.maxZ; z += step) {
				spawnColumn(world, particle, bounds.minX, particleColumnMinY(world, bounds, bounds.minX, z), bounds.maxY, z);
				spawnColumn(world, particle, bounds.maxX, particleColumnMinY(world, bounds, bounds.maxX, z), bounds.maxY, z);
			}
		}

		if (config.renderBottomParticleFace || config.renderTopParticleFace) {
			for (double x = bounds.minX; x <= bounds.maxX; x += step) {
				for (double z = bounds.minZ; z <= bounds.maxZ; z += step) {
					if (config.renderBottomParticleFace) {
						world.spawnParticles(particle, x + 0.5, bounds.minY + 0.5, z + 0.5, 1, 0, 0, 0, 0);
					}
					if (config.renderTopParticleFace) {
						world.spawnParticles(particle, x + 0.5, bounds.maxY + 0.5, z + 0.5, 1, 0, 0, 0, 0);
					}
				}
			}
		}
	}

	private boolean shouldRenderParticlesForState(ZoneState state) {
		return switch (state) {
			case INACTIVE -> false;
			case PARTIAL -> config.renderParticlesForPartialState;
			case FORCE_FIELD -> config.renderParticlesForForceFieldState;
			case DEGRADING -> config.renderParticlesForDegradingState;
		};
	}

	private double particleColumnMinY(ServerWorld world, Box bounds, double x, double z) {
		if (!config.renderParticlesDownToGround) {
			return bounds.minY;
		}

		int blockX = BlockPos.ofFloored(x, bounds.minY, z).getX();
		int blockZ = BlockPos.ofFloored(x, bounds.minY, z).getZ();
		int topOpenY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
		int groundY = Math.max(world.getBottomY(), topOpenY - 1);
		return Math.min(bounds.minY, groundY);
	}

	private void spawnColumn(ServerWorld world, ParticleEffect particle, double x, double minY, double maxY, double z) {
		double step = Math.max(0.5, config.particleStep);
		double lastY = Double.NEGATIVE_INFINITY;
		for (double y = minY; y <= maxY; y += step) {
			world.spawnParticles(particle, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
			lastY = y;
		}
		if (Math.abs(lastY - maxY) > 1.0E-4) {
			world.spawnParticles(particle, x + 0.5, maxY + 0.5, z + 0.5, 1, 0, 0, 0, 0);
		}
	}

	private void triggerAnchorDestroyedExplosion(ServerWorld world, BlockPos pos, @Nullable ServerPlayerEntity player) {
		if (config.anchorDestroyedExplosionPower <= 0) {
			return;
		}
		if (player == null) {
			return;
		}

		boolean shouldExplode = false;
		for (UUID zoneId : anchorToZones.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>()).getOrDefault(pos, Set.of())) {
			GlowZone zone = zones.get(zoneId);
			if (zone != null && zone.isHostileProtectionActive(world, config) && !zone.isMember(player.getUuid())) {
				shouldExplode = true;
				break;
			}
		}
		if (!shouldExplode) {
			return;
		}

		world.createExplosion(
			null,
			pos.getX() + 0.5,
			pos.getY() + 0.5,
			pos.getZ() + 0.5,
			(float) config.anchorDestroyedExplosionPower,
			config.anchorDestroyedExplosionCreatesFire,
			World.ExplosionSourceType.TNT
		);
	}

	private void registerZone(GlowZone zone) {
		zones.put(zone.id(), zone);
		zoneSignatures.put(ZoneSignature.of(zone.worldKey(), zone.anchors()), zone.id());
		loadedZoneIds.computeIfAbsent(zone.worldKey(), key -> new HashSet<>()).add(zone.id());

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

	private void applyAnchorBacklash(ServerWorld world, BlockPos anchor, ServerPlayerEntity player, GlowZone zone) {
		if (config.backlashDamage > 0) {
			player.damage(world, world.getDamageSources().magic(), (float) config.backlashDamage);
			recentPlayerZoneDamage.put(player.getUuid(), new ZoneDamageRecord(zone.id(), world.getTime()));
		}

		if (config.backlashKnockback > 0) {
			double dx = player.getX() - (anchor.getX() + 0.5);
			double dz = player.getZ() - (anchor.getZ() + 0.5);
			if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
				dz = 1.0;
			}
			player.takeKnockback(config.backlashKnockback, dx, dz);
		}

		if (config.backlashSlownessTicks > 0) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, config.backlashSlownessTicks, Math.max(0, config.backlashSlownessAmplifier)));
		}
		if (config.backlashWitherTicks > 0) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, config.backlashWitherTicks, Math.max(0, config.backlashWitherAmplifier)));
		}
	}

	@Nullable
	private GlowZone findHostileZoneForAnchor(ServerWorld world, BlockPos anchor, UUID playerUuid) {
		for (UUID zoneId : anchorToZones.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>()).getOrDefault(anchor, Set.of())) {
			GlowZone zone = zones.get(zoneId);
			if (zone != null && !zone.isMember(playerUuid) && zone.isHostileProtectionActive(world, config)) {
				return zone;
			}
		}
		return null;
	}

	private AnchorDamageProgress decayedProgress(AnchorDamageProgress progress, long currentTick) {
		if (!config.hitDecayEnabled || progress.hits() <= 0 || config.hitDecayTimeTicks <= 0) {
			return progress;
		}

		long elapsed = Math.max(0, currentTick - progress.lastUpdatedTick());
		int decayedHits = Math.max(0, progress.hits() - (int) (elapsed / config.hitDecayTimeTicks));
		long updatedTick = decayedHits == progress.hits() ? progress.lastUpdatedTick() : currentTick;
		return new AnchorDamageProgress(decayedHits, updatedTick);
	}

	private void resetZonesForAnchor(ServerWorld world, BlockPos anchor) {
		Set<UUID> ids = anchorToZones.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>()).getOrDefault(anchor, Set.of());
		for (UUID zoneId : ids) {
			GlowZone zone = zones.get(zoneId);
			if (zone != null) {
				zone.resetForCurrentCharge(world, config);
				pendingHostileActivations.entrySet().removeIf(entry -> entry.getKey().zoneId().equals(zoneId));
				maybeNotifyZoneMode(world, zone);
			}
		}
	}

	private void maybeNotifyZoneMode(ServerWorld world, GlowZone zone) {
		if (server == null || !zone.worldKey().equals(world.getRegistryKey())) {
			return;
		}

		String modeKey = zone.currentModeKey(world, config);
		if (!zone.markModeAnnounced(modeKey)) {
			return;
		}

		ServerPlayerEntity owner = server.getPlayerManager().getPlayer(zone.ownerUuid());
		if (owner == null) {
			return;
		}

		owner.sendMessage(Text.literal("Zone " + describeZone(zone.id()) + " is now in " + zone.currentModeLabel(world, config) + "."), false);
	}

	private void logHostileZoneCheck(ServerWorld world, GlowZone zone, ZoneState state, ServerPlayerEntity player, String context) {
		debugLog(
			"{} player={} zone={} state={} mode={} hostileFlag={} hostileActive={} member={} pos={} remainingTicks={} deathsRemaining={}",
			context,
			player.getGameProfile().name(),
			describeZone(zone.id()),
			state.serializedName(),
			zone.currentModeKey(world, config),
			zone.hostileToNonMembers(),
			zone.isHostileProtectionActive(world, config),
			zone.isMember(player.getUuid()),
			player.getBlockPos(),
			zone.remainingActiveTicks(),
			zone.hostilePlayerDeathsRemaining()
		);
	}

	private void debugLog(String message, Object... args) {
		if (!config.debugLoggingEnabled) {
			return;
		}
		lnjustin.glowfield.GlowField.LOGGER.info("[GlowField debug] " + message, args);
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
						if (chunk.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)) {
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
			if (zone != null && zone.contains(world, box)) {
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

	private void syncZonesForSave(MinecraftServer server) {
		boolean changed = false;
		for (GlowZone zone : zones.values()) {
			ServerWorld world = server.getWorld(zone.worldKey());
			if (world == null) {
				continue;
			}

			long before = zone.remainingActiveTicks();
			zone.syncRuntime(world, config);
			if (zone.remainingActiveTicks() != before) {
				changed = true;
			}
		}
		if (changed) {
			dirty = true;
		}
	}

	private void clearRuntimeState() {
		zones.clear();
		chunkIndex.clear();
		anchorToZones.clear();
		anchorsByY.clear();
		loadedZoneIds.clear();
		zoneSignatures.clear();
		pendingPlacements.clear();
		pendingHostileActivations.clear();
		recentPlayerZoneDamage.clear();
		anchorDamage.clear();
		lastHostileDamageTicks.clear();
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

	private record PendingHostileActivationKey(UUID zoneId, UUID playerUuid) {
	}

	private record ZoneDamageRecord(UUID zoneId, long recordedTick) {
	}

	private record AnchorDamageKey(RegistryKey<World> worldKey, BlockPos pos) {
	}

	private record AnchorDamageProgress(int hits, long lastUpdatedTick) {
	}
}
