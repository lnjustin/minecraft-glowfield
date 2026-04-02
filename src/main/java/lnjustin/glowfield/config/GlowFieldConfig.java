package lnjustin.glowfield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lnjustin.glowfield.GlowField;
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
	public boolean nameTagNamingEnabled = true;
	public boolean persistCachedBounds = true;

	public int forceFieldMinCharge = 4;
	public int partialMinCharge = 1;
	public int degradeEveryMobInteractions = 50;
	public int degradingStateThreshold = 25;
	public int particleIntervalTicks = 20;
	public int entitySweepIntervalTicks = 10;
	public int saveFlushIntervalTicks = 200;

	public double forceFieldDamage = 4.0;
	public double particleStep = 1.5;
	public double particleViewPadding = 32.0;

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

				return config;
			}
		} catch (Exception exception) {
			GlowField.LOGGER.error("Failed to load GlowField config", exception);
			return new GlowFieldConfig();
		}
	}

	public static void save(GlowFieldConfig config) {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(config, writer);
			}
		} catch (Exception exception) {
			GlowField.LOGGER.error("Failed to save GlowField config", exception);
		}
	}

	public ParticleEffect particleFor(ZoneStateLike state) {
		String id = stateParticles.getOrDefault(state.serializedName(), "minecraft:end_rod");
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return ParticleTypes.END_ROD;
		}

		try {
			Object particleType = Registries.PARTICLE_TYPE.get(identifier);
			return particleType instanceof ParticleEffect effect ? effect : ParticleTypes.END_ROD;
		} catch (Exception exception) {
			return ParticleTypes.END_ROD;
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

	public interface ZoneStateLike {
		String serializedName();
	}
}
