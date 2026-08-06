package ua.eismont.deathechoes.echo;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import ua.eismont.deathechoes.Constants;

/**
 * Common holder for the {@link EchoEntity} type. Each loader registers the entity type into its
 * own registry (Fabric: direct {@code Registry.register}; NeoForge: {@code DeferredRegister}) and
 * assigns {@link #ECHO} once registration completes, so common code can reference {@code
 * ModEntities.ECHO} without depending on either loader's registration API.
 */
public final class ModEntities {

    public static final ResourceKey<EntityType<?>> ECHO_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "echo"));

    /** Assigned by whichever loader finishes registering the entity type. */
    public static EntityType<EchoEntity> ECHO;

    private ModEntities() {}

    /** Shared dimensions/behavior so both loaders build an identically configured {@link EntityType}. */
    public static EntityType.Builder<EchoEntity> configure(EntityType.Builder<EchoEntity> builder) {
        return builder
                .sized(0.6f, 1.8f)
                .clientTrackingRange(10)
                .fireImmune()
                .noSummon();
    }
}
