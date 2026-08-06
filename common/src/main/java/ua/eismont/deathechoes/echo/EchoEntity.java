package ua.eismont.deathechoes.echo;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The ghost spawned at a player's death spot: replays their last recorded moments and holds half
 * of their lost experience for whoever collects it. Invulnerable, weightless and unpushable so it
 * behaves like a stationary apparition rather than a normal mob.
 */
public class EchoEntity extends Entity {

    /** How long the replay lingers on the last frame before looping back to the start. */
    private static final int PAUSE_TICKS = 40;

    /**
     * How often (in ticks) {@link #orphanSelfCheck()} re-verifies tracker membership, and how
     * long a freshly spawned echo is given before the first check. EchoSpawner registers the new
     * echo in the tracker right after {@code addFreshEntity}, so this window comfortably outlasts
     * that gap without ever mistaking a legitimate fresh spawn for an orphan.
     */
    private static final int ORPHAN_CHECK_GRACE_TICKS = 100;
    private static final int ORPHAN_CHECK_INTERVAL_TICKS = 200;

    private static final EntityDataAccessor<String> DATA_OWNER_NAME =
            SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.STRING);
    // Our own EchoFrame.Pose (STANDING/SNEAKING/SPRINTING), not vanilla Pose - synced as a raw
    // ordinal byte so the client renderer can pick an animation frame.
    private static final EntityDataAccessor<Byte> DATA_POSE =
            SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<ItemStack> DATA_MAIN_HAND =
            SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.ITEM_STACK);

    private EchoRecording recording = new EchoRecording();
    private int storedXp;
    private UUID ownerUUID;
    private String ownerName = "";

    // Client-only walk-animation accumulator (never saved/synced - rebuilt from observed movement
    // each client tick). Mirrors LivingEntity.updateWalkAnimation()/WalkAnimationState.update(),
    // which EchoEntity can't call directly since it isn't a LivingEntity. We track our own
    // last-seen client position rather than reusing Entity's xo/zo fields: those are refreshed by
    // the level's entity-tick dispatcher on a schedule this class doesn't control, so recording our
    // own "position last tick" is the only way to get a reliably-ordered delta.
    private float clientWalkAnimPos;
    private float clientWalkAnimSpeed;
    private float clientWalkAnimSpeedOld;
    private double lastClientX;
    private double lastClientZ;
    private boolean hasLastClientPos;

    public EchoEntity(EntityType<? extends EchoEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setInvulnerable(true);
    }

    /** Assigns the replay data and ownership for a freshly spawned echo. */
    public void configure(EchoRecording recording, int storedXp, UUID ownerUUID, String ownerName) {
        this.recording = recording;
        this.storedXp = storedXp;
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.entityData.set(DATA_OWNER_NAME, ownerName);
    }

    public EchoRecording getRecording() {
        return recording;
    }

    public int getStoredXp() {
        return storedXp;
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public String getOwnerName() {
        return ownerName;
    }

    /**
     * Client-visible owner name, read straight off {@link #entityData} rather than the
     * server-only {@link #ownerName} field (which is populated from save data, not network sync,
     * and is therefore empty on the client). Used by the client renderer to resolve the dead
     * player's skin.
     */
    public String getSyncedOwnerName() {
        return entityData.get(DATA_OWNER_NAME);
    }

    /** Client-visible replay pose, decoded from the synced ordinal byte. Used by the renderer. */
    public EchoFrame.Pose getSyncedPose() {
        byte ordinal = entityData.get(DATA_POSE);
        EchoFrame.Pose[] poses = EchoFrame.Pose.values();
        return (ordinal >= 0 && ordinal < poses.length) ? poses[ordinal] : EchoFrame.Pose.STANDING;
    }

    /**
     * Interpolated walk-cycle position for the renderer, mirroring {@code
     * WalkAnimationState.position(float)}: {@code (position - speed * (1 - partialTicks))}.
     */
    public float getClientWalkAnimationPos(float partialTicks) {
        return clientWalkAnimPos - clientWalkAnimSpeed * (1.0F - partialTicks);
    }

    /**
     * Interpolated walk-cycle speed for the renderer, mirroring {@code
     * WalkAnimationState.speed(float)}: {@code min(lerp(partialTicks, speedOld, speed), 1)}.
     */
    public float getClientWalkAnimationSpeed(float partialTicks) {
        return Math.min(Mth.lerp(partialTicks, clientWalkAnimSpeedOld, clientWalkAnimSpeed), 1.0F);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            updateClientWalkAnimation();
            tickClientAmbience();
            return;
        }
        orphanSelfCheck();
        if (isRemoved()) {
            // orphanSelfCheck() may have just discarded this echo; don't run the replay block
            // (and push a stale sync update) in the same tick.
            return;
        }
        if (recording.size() == 0) {
            return;
        }
        int cycle = recording.size() + PAUSE_TICKS;
        // ServerLevel.tickNonPassenger increments tickCount immediately before calling tick(), so
        // by the time this runs tickCount already counts *this* tick; subtract 1 so the very
        // first tick after spawn plays frame 0, not frame 1.
        int idx = (tickCount - 1) % cycle;
        if (idx >= 0 && idx < recording.size()) {
            // Replay intentionally follows the raw recording, including originally-unsafe
            // positions (lava/underground) - the echo is ethereal (invulnerable, fireImmune), so
            // this is accepted, known behavior rather than a bug.
            EchoFrame frame = recording.frame(idx);
            setPos(frame.x(), frame.y(), frame.z());
            setYRot(frame.yaw());
            setXRot(frame.pitch());
            // Single-yaw approximation: body/head rotation both mirror look yaw. Deliberate simplification
            // for the player-model renderer, which needs these synced for a believable pose.
            setYBodyRot(frame.yaw());
            setYHeadRot(frame.yaw());
            entityData.set(DATA_POSE, (byte) frame.pose().ordinal());
            ItemStack current = entityData.get(DATA_MAIN_HAND);
            if (!ItemStack.matches(current, frame.mainHand())) {
                entityData.set(DATA_MAIN_HAND, frame.mainHand());
            }
        }
    }

    /**
     * Advances the client-only walk-cycle accumulator by how far the echo visibly moved this
     * tick. Exact constants (targetSpeed = min(distance * 4, 1), ease factor 0.4) are copied from
     * {@code LivingEntity.updateWalkAnimation(float)} / {@code WalkAnimationState.update(float,
     * float, float)} (decompiled and verified against the project's merged jar) so the ghost's
     * limb swing matches vanilla's walk animation feel instead of inventing new constants.
     */
    private void updateClientWalkAnimation() {
        double dx = hasLastClientPos ? getX() - lastClientX : 0.0;
        double dz = hasLastClientPos ? getZ() - lastClientZ : 0.0;
        lastClientX = getX();
        lastClientZ = getZ();
        hasLastClientPos = true;

        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        float targetSpeed = Math.min(distance * 4.0F, 1.0F);
        clientWalkAnimSpeedOld = clientWalkAnimSpeed;
        clientWalkAnimSpeed = clientWalkAnimSpeed + (targetSpeed - clientWalkAnimSpeed) * 0.4F;
        clientWalkAnimPos += clientWalkAnimSpeed;
    }

    /**
     * Client-only ambience: occasional drifting soul particles, plus a rare eerie ambient sound
     * when a local player is nearby. Deliberately kept in common (not the per-loader renderer)
     * since {@link Level#addParticle}, {@link Level#playLocalSound} and {@link Level#getNearestPlayer}
     * are all common-side APIs - no client package dependency needed, so both loaders get this for
     * free without duplicating renderer tick hooks.
     */
    private void tickClientAmbience() {
        RandomSource random = getRandom();
        if (random.nextInt(40) == 0) {
            double px = getX() + (random.nextDouble() - 0.5) * 0.6;
            double py = getY() + 0.1 + random.nextDouble() * 1.6;
            double pz = getZ() + (random.nextDouble() - 0.5) * 0.6;
            level().addParticle(ParticleTypes.SOUL, px, py, pz, 0.0, 0.02, 0.0);
        }
        // ~1/200 chance per tick => average ~10s between checks (randomized, not a fixed period),
        // and only actually plays if a player is close enough to notice.
        if (random.nextInt(200) == 0 && level().getNearestPlayer(this, 16.0) != null) {
            level().playLocalSound(this, SoundEvents.SCULK_CLICKING, SoundSource.AMBIENT,
                    0.3f, 0.6f + random.nextFloat() * 0.3f);
        }
    }

    /**
     * Mitigates the eviction leak where {@link EchoTracker#register} evicts an owner's oldest
     * echo UUID but the corresponding entity has since moved to another dimension (or otherwise
     * escaped the {@code level.getEntityInAnyDimension} lookup EchoSpawner uses), leaving a
     * ghost that no longer has a tracker entry. Cheap and infrequent by design: only runs well
     * past spawn ({@link #ORPHAN_CHECK_GRACE_TICKS}) and then every
     * {@link #ORPHAN_CHECK_INTERVAL_TICKS} ticks.
     */
    private void orphanSelfCheck() {
        if (ownerUUID == null || tickCount <= ORPHAN_CHECK_GRACE_TICKS || tickCount % ORPHAN_CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        if (level() instanceof ServerLevel serverLevel && !EchoTracker.get(serverLevel).contains(ownerUUID, getUUID())) {
            discard();
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hitVec) {
        if (isRemoved()) {
            // Guards against XP duplication from duplicate interact packets landing in the same
            // tick: the first one discards the echo, so any follow-up must be a no-op.
            return InteractionResult.PASS;
        }
        if (level().isClientSide()) {
            // Client always swings - ownership resolved server-side (ownerUUID not synced).
            return InteractionResult.SUCCESS;
        }
        if (ownerUUID == null || !ownerUUID.equals(player.getUUID())) {
            return InteractionResult.PASS;
        }
        player.giveExperiencePoints(storedXp);
        level().playSound(null, blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 0.6f);
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SOUL, getX(), getY() + 1.0, getZ(), 30, 0.3, 0.6, 0.3, 0.02);
            EchoTracker.get(serverLevel).unregister(ownerUUID, getUUID());
        }
        discard();
        return InteractionResult.CONSUME;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_OWNER_NAME, "");
        builder.define(DATA_POSE, (byte) EchoFrame.Pose.STANDING.ordinal());
        builder.define(DATA_MAIN_HAND, ItemStack.EMPTY);
    }

    @Override
    public boolean isPickable() {
        // Needed so a right-click interaction (collecting stored xp) can target it.
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount) {
        // setInvulnerable(true) already short-circuits Entity.hurt() for almost everything;
        // this is the belt-and-suspenders no-op for the abstract contract itself.
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        HolderLookup.Provider registries = input.lookup();
        CompoundTag recordingTag = input.read("recording", CompoundTag.CODEC).orElseGet(CompoundTag::new);
        this.recording = EchoRecording.fromTag(recordingTag, registries);
        this.storedXp = input.getIntOr("storedXp", 0);
        this.ownerUUID = input.read("owner", UUIDUtil.CODEC).orElse(null);
        this.ownerName = input.getStringOr("ownerName", "");
        this.entityData.set(DATA_OWNER_NAME, ownerName);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        HolderLookup.Provider registries = this.level().registryAccess();
        output.store("recording", CompoundTag.CODEC, recording.toTag(registries));
        output.putInt("storedXp", storedXp);
        if (ownerUUID != null) {
            output.store("owner", UUIDUtil.CODEC, ownerUUID);
        }
        output.putString("ownerName", ownerName);
    }
}
