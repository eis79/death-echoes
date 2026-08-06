package ua.eismont.deathechoes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import ua.eismont.deathechoes.echo.EchoEntity;
import ua.eismont.deathechoes.echo.EchoSpawner;
import ua.eismont.deathechoes.echo.ModEntities;
import ua.eismont.deathechoes.echo.RecordingManager;

public class DeathEchoesFabric implements ModInitializer {

    @Override
    public void onInitialize() {

        // This method is invoked by the Fabric mod loader when it is ready
        // to load your mod. You can access Fabric and Common code in this
        // project.

        // Use Fabric to bootstrap the Common mod.
        DeathEchoes.init();

        ModEntities.ECHO = Registry.register(BuiltInRegistries.ENTITY_TYPE, ModEntities.ECHO_KEY,
            ModEntities.configure(EntityType.Builder.of(EchoEntity::new, MobCategory.MISC)).build(ModEntities.ECHO_KEY));

        ServerTickEvents.END_SERVER_TICK.register(RecordingManager::tickServer);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            RecordingManager.clear(handler.getPlayer().getUUID()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                EchoSpawner.onPlayerDeath(player);
            }
        });
    }
}
