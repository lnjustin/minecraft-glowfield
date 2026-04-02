package lnjustin.glowfield.mixin;

import lnjustin.glowfield.GlowField;
import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RespawnAnchorBlock.class)
public abstract class RespawnAnchorBlockMixin {
	@Inject(method = "charge", at = @At("HEAD"))
	private static void glowfield$captureChargeBefore(Entity charger, World world, BlockPos pos, BlockState state, CallbackInfo ci) {
		if (!world.isClient()) {
			GlowField.zones().onAnchorStateChanged((net.minecraft.server.world.ServerWorld) world, pos, state, world.getBlockState(pos));
		}
	}
}
