package ua.eismont.deathechoes.client;

import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import ua.eismont.deathechoes.DeathEchoesNeoForge;

/**
 * Kept in its own class - separate from {@code DeathEchoesNeoForge} - so that this mod's shared
 * (both-sides) entry class never mentions client-only Minecraft/NeoForge render types in its own
 * bytecode. {@code DeathEchoesNeoForge} only ever references this class via a method reference,
 * and only inside an {@code if (FMLEnvironment.getDist() == Dist.CLIENT)} guard, so a dedicated
 * server never needs to resolve {@link EntityRenderersEvent.RegisterRenderers} (whose type isn't
 * even on a dedicated server's classpath).
 *
 * <p>Deliberately reads the entity type from {@link DeathEchoesNeoForge#ECHO} (the {@code
 * DeferredHolder}) rather than {@code ModEntities.ECHO} - verified via a boot-check crash that
 * {@code EntityRenderersEvent.RegisterRenderers} fires <em>before</em> {@code
 * FMLCommonSetupEvent}, which is where {@code ModEntities.ECHO} gets assigned. Registering with
 * the not-yet-assigned {@code ModEntities.ECHO} silently passed {@code null} as the entity type,
 * which blew up later during resource-pack (re)load with a Guava
 * {@code NullPointerException: null key in entry: ...} out of {@code EntityRenderers.createEntityRenderers}.
 */
public final class EchoClientRenderers {

    private EchoClientRenderers() {}

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {

        event.registerEntityRenderer(DeathEchoesNeoForge.ECHO.get(), EchoRenderer::new);
    }
}
