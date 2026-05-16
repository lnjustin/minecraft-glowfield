package lnjustin.glowfield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lnjustin.glowfield.GlowField;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class GlowFieldConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = Path.of("config", "glowfield.json");

	public boolean particlesEnabled = true;
	public boolean damagePlayers = false;
	public boolean pvpEnabled = false;
	public boolean suppressMobSpawns = true;
	public boolean damageMobs = true;
	public Boolean damageHostileMobs = null;
	public Boolean damageNonHostileMobs = null;
	public boolean debugLoggingEnabled = false;
	public boolean nameTagNamingEnabled = true;
	public boolean allowNonMemberPlayerDamage = true;
	public boolean persistCachedBounds = true;
	public boolean renderVerticalParticleEdges = true;
	public boolean renderTopParticleFace = true;
	public boolean renderBottomParticleFace = false;
	public boolean renderParticlesDownToGround = true;
	public boolean renderParticlesForPartialState = true;
	public boolean renderParticlesForForceFieldState = true;
	public boolean renderParticlesForDegradingState = true;

	public int forceFieldMinCharge = 4;
	public int partialMinCharge = 1;
	public int degradeEveryMobInteractions = 50;
	public int degradingStateThreshold = 25;
	public int mobInteractionNormalizationAreaBlocks = 256;
	public int hostileFieldPlayerDeathLimit = 5;
	public int anchorBreakHitsRequired = 20;
	public int particleIntervalTicks = 20;
	public int entitySweepIntervalTicks = 10;
	public int saveFlushIntervalTicks = 200;
	public int particleRenderHeightBlocks = 1;
	public int partialFieldDurationTicks = 240000;
	public int forceFieldDurationTicks = 120000;
	public int hostileForceFieldDurationTicks = 48000;
	public int hostileFieldDamageIntervalTicks = 20;
	public int hitDecayTimeTicks = 200;
	public int backlashSlownessTicks = 60;
	public int backlashSlownessAmplifier = 1;
	public int backlashWitherTicks = 0;
	public int backlashWitherAmplifier = 0;

	public double forceFieldDamage = 4.0;
	public double hostileFieldDamage = 4.0;
	public double backlashDamage = 4.0;
	public double backlashKnockback = 1.0;
	public double anchorDestroyedExplosionPower = 0.0;
	public double particleStep = 3.0;
	public double particleViewPadding = 32.0;

	public String hostileFieldActivationItem = "minecraft:echo_shard";
	public String hostileFieldParticle = "minecraft:soul_fire_flame";
	public boolean hitDecayEnabled = false;
	public boolean anchorDestroyedExplosionCreatesFire = false;
	public Map<String, String> stateParticles = defaultParticles();

	public static GlowFieldConfig load() {
		try {
			if (Files.notExists(FILE)) {
				GlowFieldConfig config = new GlowFieldConfig();
				save(config);
				return config;
			}

			try (Reader reader = Files.newBufferedReader(FILE)) {
				GlowFieldConfig config = GSON.fromJson(reader, GlowFieldConfig.class);
				if (config == null) {
					config = new GlowFieldConfig();
				}

				if (config.stateParticles == null || config.stateParticles.isEmpty()) {
					config.stateParticles = defaultParticles();
				}
				config.applyDerivedDefaults();

				save(config);
				return config;
			}
		} catch (Exception exception) {
			GlowField.LOGGER.error("Failed to load GlowField config", exception);
			return new GlowFieldConfig();
		}
	}

	public static void save(GlowFieldConfig config) {
		try {
			config.applyDerivedDefaults();
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(config, writer);
			}
		} catch (Exception exception) {
			GlowField.LOGGER.error("Failed to save GlowField config", exception);
		}
	}

	public ParticleEffect particleFor(ZoneStateLike state) {
		ParticleEffect fallback = fallbackParticleFor(state.serializedName());
		String id = stateParticles.getOrDefault(state.serializedName(), defaultParticleIdFor(state.serializedName()));
		return particleFromId(id, fallback);
	}

	public ParticleEffect hostileFieldParticle() {
		return particleFromId(hostileFieldParticle, ParticleTypes.SOUL_FIRE_FLAME);
	}

	public Item hostileFieldActivationItem() {
		Identifier identifier = Identifier.tryParse(hostileFieldActivationItem);
		if (identifier == null) {
			return Items.ECHO_SHARD;
		}

		try {
			Item item = Registries.ITEM.get(identifier);
			return item == null ? Items.ECHO_SHARD : item;
		} catch (Exception exception) {
			return Items.ECHO_SHARD;
		}
	}

	public boolean damageHostileMobs() {
		return damageHostileMobs != null ? damageHostileMobs : damageMobs;
	}

	public boolean damageNonHostileMobs() {
		return damageNonHostileMobs != null ? damageNonHostileMobs : damageMobs;
	}

	private void applyDerivedDefaults() {
		if (damageHostileMobs == null) {
			damageHostileMobs = damageMobs;
		}
		if (damageNonHostileMobs == null) {
			damageNonHostileMobs = damageMobs;
		}
		if (stateParticles == null) {
			stateParticles = defaultParticles();
		} else {
			defaultParticles().forEach(stateParticles::putIfAbsent);
		}
	}

	private ParticleEffect particleFromId(String id, ParticleEffect fallback) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return fallback;
		}

		try {
			Object particleType = Registries.PARTICLE_TYPE.get(identifier);
			return particleType instanceof ParticleEffect effect ? effect : fallback;
		} catch (Exception exception) {
			return fallback;
		}
	}

	private static Map<String, String> defaultParticles() {
		Map<String, String> particles = new HashMap<>();
		particles.put("inactive", "minecraft:end_rod");
		particles.put("partial", "minecraft:smoke");
		particles.put("force_field", "minecraft:portal");
		particles.put("degrading", "minecraft:ash");
		return particles;
	}

	private static String defaultParticleIdFor(String state) {
		return defaultParticles().getOrDefault(state, "minecraft:end_rod");
	}

	private static ParticleEffect fallbackParticleFor(String state) {
		return switch (state) {
			case "partial" -> ParticleTypes.SMOKE;
			case "force_field" -> ParticleTypes.PORTAL;
			case "degrading" -> ParticleTypes.ASH;
			default -> ParticleTypes.END_ROD;
		};
	}

	public interface ZoneStateLike {
		String serializedName();
	}
}
