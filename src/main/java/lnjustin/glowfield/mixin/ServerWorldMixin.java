package lnjustin.glowfield.mixin;

import lnjustin.glowfield.GlowField;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;

@Mixin(World.class)
public abstract class ServerWorldMixin {
	@Unique
	private final ThreadLocal<ArrayDeque<BlockSnapshot>> glowfield$pendingSnapshots = ThreadLocal.withInitial(ArrayDeque::new);

	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("HEAD"))
	private void glowfield$captureBefore(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
		World world = (World) (Object) this;
		glowfield$pendingSnapshots.get().push(new BlockSnapshot(pos.toImmutable(), world.getBlockState(pos)));
	}

	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("RETURN"))
	private void glowfield$emitAfter(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
		World world = (World) (Object) this;
		BlockSnapshot snapshot = glowfield$pendingSnapshots.get().pop();
		if (cir.getReturnValueZ() && world instanceof ServerWorld serverWorld) {
			GlowField.zones().onAnchorStateChanged(serverWorld, snapshot.pos(), snapshot.state(), world.getBlockState(snapshot.pos()));
		}
	}

	@Unique
	private record BlockSnapshot(BlockPos pos, BlockState state) {
	}
}
