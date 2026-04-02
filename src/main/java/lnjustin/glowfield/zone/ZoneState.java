package lnjustin.glowfield.zone;

import lnjustin.glowfield.config.GlowFieldConfig;

public enum ZoneState implements GlowFieldConfig.ZoneStateLike {
	INACTIVE("inactive"),
	PARTIAL("partial"),
	FORCE_FIELD("force_field"),
	DEGRADING("degrading");

	private final String serializedName;

	ZoneState(String serializedName) {
		this.serializedName = serializedName;
	}

	@Override
	public String serializedName() {
		return serializedName;
	}
}
