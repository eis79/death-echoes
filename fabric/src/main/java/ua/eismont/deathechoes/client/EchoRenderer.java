package ua.eismont.deathechoes.client;

// NOTE: this class is duplicated verbatim between the fabric and neoforge modules. The common
// module has no client-side Minecraft dependencies (this multiloader template has no shared
// client source set), so a renderer - which is unavoidably client-only Minecraft API - cannot
// live there. The class has zero fabric/neoforge-specific imports, so a byte-for-byte copy is the
// simplest option; mirror any future edits into the sibling module's copy of this file. A gradle
// task in the root build.gradle (wired into `check`) fails the build if the two copies drift.

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;
import ua.eismont.deathechoes.echo.EchoEntity;
import ua.eismont.deathechoes.echo.EchoFrame;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders {@link EchoEntity} as a translucent player-shaped ghost, using the dead player's skin
 * resolved by name (falling back to the default Steve/Alex skin when the name can't be resolved,
 * e.g. offline-mode servers or an unknown name).
 *
 * <p><b>Why not reuse the vanilla player renderer:</b> 26.1.2 replaced the old immediate-mode
 * {@code render(state, poseStack, buffer, light)} contract with a "submit" pipeline -
 * {@code EntityRenderer<T, S>} now only has {@code createRenderState()}, {@code
 * extractRenderState(T, S, float)} and {@code submit(S, PoseStack, SubmitNodeCollector,
 * CameraRenderState)}. Verified via javap/vineflower against the project's merged Minecraft jar:
 * <ul>
 *   <li>{@code LivingEntityRenderer<T extends LivingEntity, ...>} and {@code
 *   HumanoidMobRenderer<T extends Mob, ...>} both require the entity type to extend {@code
 *   LivingEntity}/{@code Mob}. {@link EchoEntity} extends plain {@code Entity} (it is
 *   invulnerable, weightless, non-living), so it cannot satisfy either bound.</li>
 *   <li>{@code AvatarRenderer<AvatarlikeEntity extends Avatar & ClientAvatarEntity>} needs the
 *   entity to implement {@code Avatar}, and its model - {@code PlayerModel} - is declared as
 *   {@code HumanoidModel<AvatarRenderState>} (a fixed type argument, not a generic bound). Its
 *   {@code setupAnim(HumanoidRenderState)} overload that appears in javap is a synthetic bridge
 *   method for the erased superclass signature; calling it with a plain {@code
 *   HumanoidRenderState} instance throws {@code ClassCastException} at runtime because it
 *   unconditionally casts to {@code AvatarRenderState}.</li>
 *   <li>{@code HumanoidModel<T extends HumanoidRenderState>} (the base class), by contrast, is
 *   genuinely generic - its {@code setupAnim(T)} is the real implementation, not a bridge. Baking
 *   the vanilla {@code ModelLayers.PLAYER} layer and wrapping it in a plain {@code
 *   HumanoidModel<HumanoidRenderState>} gives us the exact player mesh (including the jacket
 *   /sleeve/pants overlay parts - {@code ModelPart} rendering walks the whole child tree
 *   regardless of which fields the wrapping Java class happens to keep references to, so those
 *   overlay parts render "for free" even though {@code HumanoidModel} never mentions them by
 *   name) without needing {@code Avatar}/{@code AvatarRenderState}.</li>
 * </ul>
 *
 * <p>Skin resolution mirrors the vanilla player-head-by-name pattern in {@code
 * SkullBlockRenderer}/{@code PlayerSkinRenderCache}: {@link ResolvableProfile#createUnresolved(String)}
 * builds a profile from just the name, and {@code PlayerSkinRenderCache.getOrDefault(profile)}
 * kicks off an async resolve (name -> profile -> skin) while returning the default skin
 * immediately (non-blocking - it's backed by {@code CompletableFuture.getNow(...)}) until the
 * real skin resolves and gets cached. We reuse the {@code RenderType} it hands back rather than
 * building our own via {@code RenderTypes.entityTranslucent(...)} - it's already that, since
 * player skins always render translucent to support the semi-transparent overlay layer.
 *
 * <p>{@link #profileCache} lives on the renderer instance (created once at registration, shared
 * across every echo) rather than on {@link EchoRenderState}: {@code EntityRenderDispatcher}
 * allocates a brand-new render state every frame via {@code createRenderState(entity,
 * partialTicks)} (confirmed by decompiling it - {@code extractEntity(...)} calls {@code
 * renderer.createRenderState(entity, partialTicks)} unconditionally, no per-entity reuse), so a
 * memo field on the state would be reset to its default every single frame and never actually
 * short-circuit anything.
 *
 * <p>Translucency for the ghost itself is a plain ARGB tint: {@code submitNodeCollector.submitModel}
 * takes a packed color int whose high byte is alpha, multiplied into the model's vertex color by
 * the shader; with an alpha-blending {@code RenderType} (which {@code entityTranslucent} is) an
 * alpha below 255 there really does show through to whatever's behind the ghost.
 */
public class EchoRenderer extends EntityRenderer<EchoEntity, EchoRenderer.EchoRenderState> {

    /** ~40% opacity, packed into the alpha byte of an otherwise-white tint. */
    private static final int ALPHA = (int) (0.4f * 255.0f);
    private static final int TINT_COLOR = (ALPHA << 24) | 0xFFFFFF;

    /** Floor so the ghost stays visible at night; a 0-15 block-light level like any lit mob. */
    private static final int MIN_BLOCK_LIGHT = 10;

    private final HumanoidModel<EchoRenderState> model;
    private final PlayerSkinRenderCache skinRenderCache;

    /**
     * Name -> {@link ResolvableProfile} memo, kept here (not on the per-frame render state - see
     * class javadoc) so repeated lookups for the same echo hit this map instead of allocating a
     * new profile every frame. Naturally bounded: {@code EchoTracker} caps echoes at 3 per player.
     */
    private final Map<String, ResolvableProfile> profileCache = new HashMap<>();

    public EchoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER));
        this.skinRenderCache = context.getPlayerSkinRenderCache();
    }

    @Override
    public EchoRenderState createRenderState() {
        return new EchoRenderState();
    }

    @Override
    public void extractRenderState(EchoEntity entity, EchoRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);

        // Single-yaw approximation, matching EchoEntity's server-side recording: body and head
        // both mirror the recorded look yaw, so there's no independent head-turn to apply here.
        state.bodyRot = entity.getYRot(partialTicks);
        state.yRot = 0.0F;
        state.xRot = entity.getXRot(partialTicks);
        state.scale = 1.0F;
        state.ageScale = 1.0F;
        state.speedValue = 1.0F;
        // Walk cycle driven by EchoEntity's own client-side accumulator (mirrors
        // LivingEntity/WalkAnimationState - see EchoEntity.updateClientWalkAnimation()), since
        // EchoEntity isn't a LivingEntity and has no walkAnimation field of its own to read.
        state.walkAnimationPos = entity.getClientWalkAnimationPos(partialTicks);
        state.walkAnimationSpeed = entity.getClientWalkAnimationSpeed(partialTicks);
        state.isCrouching = entity.getSyncedPose() == EchoFrame.Pose.SNEAKING;

        String ownerName = entity.getSyncedOwnerName();
        if (ownerName.isEmpty()) {
            state.skinRenderType = PlayerSkinRenderCache.DEFAULT_PLAYER_SKIN_RENDER_TYPE;
        } else {
            ResolvableProfile profile = this.profileCache.computeIfAbsent(ownerName, ResolvableProfile::createUnresolved);
            PlayerSkinRenderCache.RenderInfo renderInfo = this.skinRenderCache.getOrDefault(profile);
            state.skinRenderType = renderInfo.renderType();
        }
    }

    @Override
    public Vec3 getRenderOffset(EchoRenderState state) {
        // Mirrors AvatarRenderer.getRenderOffset: crouching players (and so, crouching ghosts)
        // sit slightly lower to match the crouched model pose set up in HumanoidModel.setupAnim.
        Vec3 offset = super.getRenderOffset(state);
        return state.isCrouching ? offset.add(0.0, state.scale * -2.0F / 16.0, 0.0) : offset;
    }

    @Override
    public void submit(EchoRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.scale(state.scale, state.scale, state.scale);
        // Standard humanoid-model convention (mirrored from LivingEntityRenderer.submit): the
        // mesh is authored facing the opposite way and mirrored on X/Y relative to world space.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.bodyRot));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        this.model.setupAnim(state);

        // Floor the block-light component so the ghost stays visible at night/underground - a
        // translucent white-ish model at light level 0 is nearly invisible, which would defeat
        // the "occasional glimpse in the dark" ghost aesthetic entirely.
        int light = state.lightCoords;
        if (LightCoordsUtil.block(light) < MIN_BLOCK_LIGHT) {
            light = LightCoordsUtil.withBlock(light, MIN_BLOCK_LIGHT);
        }

        submitNodeCollector.submitModel(
                this.model, state, poseStack, state.skinRenderType,
                light, OverlayTexture.NO_OVERLAY, TINT_COLOR, null, state.outlineColor, null);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    /**
     * Minimal per-echo render state: a plain {@link HumanoidRenderState} (no need for {@code
     * AvatarRenderState}'s cape/parrot/flight fields) plus the resolved skin render type. Holds no
     * cross-frame memo of its own - see the class javadoc for why that has to live on the renderer
     * instance instead.
     */
    public static class EchoRenderState extends HumanoidRenderState {
        RenderType skinRenderType = PlayerSkinRenderCache.DEFAULT_PLAYER_SKIN_RENDER_TYPE;
    }
}
