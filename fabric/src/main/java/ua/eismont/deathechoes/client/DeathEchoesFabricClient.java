package ua.eismont.deathechoes.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import ua.eismont.deathechoes.echo.ModEntities;

/**
 * Fabric client-only entrypoint (registered under the {@code "client"} key in fabric.mod.json, so
 * fabric-loader only invokes it in a client environment). Runs after the {@code "main"}
 * entrypoint ({@code DeathEchoesFabric}), by which point {@link ModEntities#ECHO} is already
 * registered.
 */
public class DeathEchoesFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        EntityRendererRegistry.register(ModEntities.ECHO, EchoRenderer::new);
    }
}
