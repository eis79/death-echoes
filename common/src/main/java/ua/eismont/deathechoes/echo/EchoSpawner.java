package ua.eismont.deathechoes.echo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Spawns an {@link EchoEntity} at a dead player's last safe recorded position, seeded with their
 * movement trail and half their lost experience.
 *
 * <p>Main server thread only; called directly from loader death hooks (and from GameTests).
 */
public final class EchoSpawner {

    private EchoSpawner() {}

    public static void onPlayerDeath(Player player) {
        UUID uuid = player.getUUID();
        EchoRecording recording = RecordingManager.recordingFor(uuid);
        if (recording == null || recording.size() == 0) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        EchoFrame frame = findSafeFrame(level, recording, player);

        EchoEntity echo = new EchoEntity(ModEntities.ECHO, level);
        int storedXp = player.totalExperience / 2;
        echo.configure(recording, storedXp, uuid, player.getGameProfile().name());
        echo.setPos(frame.x(), frame.y(), frame.z());
        echo.setYRot(frame.yaw());
        echo.setXRot(frame.pitch());

        // Registered before addFreshEntity (echo.getUUID() is already assigned at construction)
        // so there's never a window where a freshly spawned echo exists in the world without a
        // matching tracker entry - closing the gap EchoEntity.orphanSelfCheck() otherwise has to
        // guard against defensively.
        UUID evicted = EchoTracker.get(level).register(uuid, echo.getUUID());
        level.addFreshEntity(echo);
        if (evicted != null) {
            Entity evictedEntity = level.getEntityInAnyDimension(evicted);
            if (evictedEntity != null) {
                evictedEntity.discard();
            }
        }

        RecordingManager.clear(uuid);
    }

    /**
     * Walks the recording backwards for the most recent frame that isn't below the world floor
     * or submerged in lava, falling back to the player's own death position (clamped above the
     * world floor) if every recorded frame is unsafe.
     */
    private static EchoFrame findSafeFrame(ServerLevel level, EchoRecording recording, Player player) {
        for (int i = recording.size() - 1; i >= 0; i--) {
            EchoFrame frame = recording.frame(i);
            BlockPos pos = BlockPos.containing(frame.x(), frame.y(), frame.z());
            if (frame.y() > level.getMinY() && !level.getFluidState(pos).is(FluidTags.LAVA)) {
                return frame;
            }
        }
        double clampedY = Math.max(player.getY(), level.getMinY() + 1);
        BlockPos deathPos = BlockPos.containing(player.getX(), clampedY, player.getZ());
        if (level.getFluidState(deathPos).is(FluidTags.LAVA)) {
            // Cosmetic-only nudge: keeps the echo from spawning submerged in lava when even the
            // death position itself is unsafe.
            clampedY += 2;
        }
        return new EchoFrame(player.getX(), clampedY, player.getZ(), player.getYRot(), player.getXRot(),
                EchoFrame.Pose.STANDING, player.getMainHandItem().copy());
    }
}
