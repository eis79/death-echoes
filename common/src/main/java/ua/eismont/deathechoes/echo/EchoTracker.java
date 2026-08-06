package ua.eismont.deathechoes.echo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import ua.eismont.deathechoes.Constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, server-global registry of which echoes belong to which player, capping each owner
 * at {@link #MAX_ECHOES} simultaneous echoes and evicting the oldest one once that cap is
 * exceeded.
 *
 * <p>Stored once per server (keyed by {@link #ID}) in the overworld's {@code SavedDataStorage},
 * so all dimensions share the same tracker.
 *
 * <p>Main server thread only; not thread-safe.
 */
public class EchoTracker extends SavedData {

    public static final int MAX_ECHOES = 3;

    private static final Identifier ID = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tracker");

    /** Codec for a single owner's entry: their UUID plus their ordered (oldest-first) echo UUIDs. */
    private record OwnerEntry(UUID owner, List<UUID> echoes) {
        static final Codec<OwnerEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(OwnerEntry::owner),
                UUIDUtil.CODEC.listOf().fieldOf("echoes").forGetter(OwnerEntry::echoes)
        ).apply(instance, OwnerEntry::new));
    }

    /** Whole-tracker codec: a plain list of {@link OwnerEntry} records. */
    public static final Codec<EchoTracker> CODEC = OwnerEntry.CODEC.listOf()
            .xmap(EchoTracker::fromEntries, EchoTracker::toEntries);

    public static final SavedDataType<EchoTracker> TYPE =
            new SavedDataType<>(ID, EchoTracker::new, CODEC, DataFixTypes.LEVEL);

    private final Map<UUID, List<UUID>> echoesByOwner;

    public EchoTracker() {
        this(new LinkedHashMap<>());
    }

    private EchoTracker(Map<UUID, List<UUID>> echoesByOwner) {
        this.echoesByOwner = echoesByOwner;
    }

    /** Resolves the single, server-global tracker instance via the overworld's data storage. */
    public static EchoTracker get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Registers {@code echoId} as owned by {@code owner}.
     *
     * @return the evicted oldest echo UUID if this registration pushed the owner over
     *     {@link #MAX_ECHOES}, else {@code null}
     */
    public UUID register(UUID owner, UUID echoId) {
        List<UUID> echoes = echoesByOwner.computeIfAbsent(owner, u -> new ArrayList<>());
        echoes.add(echoId);
        UUID evicted = echoes.size() > MAX_ECHOES ? echoes.remove(0) : null;
        setDirty();
        return evicted;
    }

    /** Returns whether {@code echoId} is currently tracked as owned by {@code owner}. */
    public boolean contains(UUID owner, UUID echoId) {
        List<UUID> echoes = echoesByOwner.get(owner);
        return echoes != null && echoes.contains(echoId);
    }

    /** Removes {@code echoId} from {@code owner}'s tracked echoes, if present. */
    public void unregister(UUID owner, UUID echoId) {
        List<UUID> echoes = echoesByOwner.get(owner);
        if (echoes != null && echoes.remove(echoId)) {
            if (echoes.isEmpty()) {
                echoesByOwner.remove(owner);
            }
            setDirty();
        }
    }

    private List<OwnerEntry> toEntries() {
        List<OwnerEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, List<UUID>> entry : echoesByOwner.entrySet()) {
            entries.add(new OwnerEntry(entry.getKey(), new ArrayList<>(entry.getValue())));
        }
        return entries;
    }

    private static EchoTracker fromEntries(List<OwnerEntry> entries) {
        Map<UUID, List<UUID>> map = new LinkedHashMap<>();
        for (OwnerEntry entry : entries) {
            map.put(entry.owner(), new ArrayList<>(entry.echoes()));
        }
        return new EchoTracker(map);
    }
}
