package ua.eismont.deathechoes.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import ua.eismont.deathechoes.echo.EchoTracker;

import java.util.UUID;

public class EchoTrackerGameTest {

    @GameTest
    public void trackerEvictsOldest(GameTestHelper helper) {
        var level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        UUID e1 = UUID.randomUUID(), e2 = UUID.randomUUID(),
            e3 = UUID.randomUUID(), e4 = UUID.randomUUID();
        EchoTracker tracker = EchoTracker.get(level);
        if (tracker.register(owner, e1) != null) helper.fail("no eviction expected for #1");
        tracker.register(owner, e2); tracker.register(owner, e3);
        UUID evicted = tracker.register(owner, e4);
        if (!e1.equals(evicted)) helper.fail("expected e1 evicted, got " + evicted);
        tracker.unregister(owner, e2);
        UUID e5 = UUID.randomUUID();
        if (tracker.register(owner, e5) != null) helper.fail("after unregister there is room - no eviction expected");

        // Fully drain this owner's echoes (e3, e4, e5) and confirm the owner entry is cleaned
        // up: the next registration must behave like a brand-new owner (no eviction), not carry
        // over any stale state from the removed entry.
        tracker.unregister(owner, e3);
        tracker.unregister(owner, e4);
        tracker.unregister(owner, e5);
        if (tracker.register(owner, UUID.randomUUID()) != null) {
            helper.fail("owner had zero echoes after full unregister - no eviction expected on first new registration");
        }
        helper.succeed();
    }

    @GameTest
    public void trackerStateRoundTripsThroughCodec(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID e1 = UUID.randomUUID(), e2 = UUID.randomUUID();

        EchoTracker original = new EchoTracker();
        original.register(owner, e1);
        original.register(owner, e2);

        Tag encoded = EchoTracker.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        EchoTracker reloaded = EchoTracker.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        // Owner had exactly 2 echoes before encoding, so there should still be room for one more.
        if (reloaded.register(owner, UUID.randomUUID()) != null) {
            helper.fail("reloaded tracker should still have room for a 3rd echo (had 2 before round-trip)");
        }
        // A 4th registration should now evict e1, proving order and contents survived the round-trip.
        UUID evicted = reloaded.register(owner, UUID.randomUUID());
        if (!e1.equals(evicted)) {
            helper.fail("expected e1 to be the oldest surviving echo after round-trip, got " + evicted);
        }
        helper.succeed();
    }
}
