package ua.eismont.deathechoes;


import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.eismont.deathechoes.client.EchoClientRenderers;
import ua.eismont.deathechoes.echo.EchoEntity;
import ua.eismont.deathechoes.echo.EchoSpawner;
import ua.eismont.deathechoes.echo.ModEntities;
import ua.eismont.deathechoes.echo.RecordingManager;

@Mod(Constants.MOD_ID)
public class DeathEchoesNeoForge {

    private static final DeferredRegister.Entities ENTITY_TYPES = DeferredRegister.createEntities(Constants.MOD_ID);
    /**
     * Public (not just {@link ModEntities#ECHO}) because client-only renderer registration needs
     * it at {@code EntityRenderersEvent.RegisterRenderers} time, which fires before
     * {@link FMLCommonSetupEvent} - i.e. before {@code ModEntities.ECHO} gets assigned below.
     * {@link net.neoforged.neoforge.registries.DeferredHolder#get()} is safe here regardless,
     * since the entity type registry is already frozen by the time any client rendering
     * registration event can fire.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<EchoEntity>> ECHO =
            ENTITY_TYPES.registerEntityType("echo", EchoEntity::new, MobCategory.MISC, ModEntities::configure);

    public DeathEchoesNeoForge(IEventBus eventBus) {

        // This method is invoked by the NeoForge mod loader when it is ready
        // to load your mod. You can access NeoForge and Common code in this
        // project.

        // Use NeoForge to bootstrap the Common mod.
        DeathEchoes.init();

        ENTITY_TYPES.register(eventBus);
        // Registries are frozen well before FMLCommonSetupEvent, so the holder is safely bound here.
        eventBus.addListener((FMLCommonSetupEvent event) -> ModEntities.ECHO = ECHO.get());

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
            RecordingManager.tickServer(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) ->
            RecordingManager.clear(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                EchoSpawner.onPlayerDeath(player);
            }
        });

        // Renderer registration touches client-only Minecraft classes (EntityRenderer, PlayerModel,
        // etc.) that don't exist on a dedicated server's classpath. Guarding by Dist.CLIENT and
        // delegating to a separate class (EchoClientRenderers) keeps this shared (both-sides)
        // class itself free of any client-only type references.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            eventBus.addListener(EchoClientRenderers::onRegisterRenderers);
        }
    }
}
