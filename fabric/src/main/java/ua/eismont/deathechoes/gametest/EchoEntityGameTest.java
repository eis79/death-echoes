package ua.eismont.deathechoes.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import ua.eismont.deathechoes.echo.EchoEntity;
import ua.eismont.deathechoes.echo.EchoFrame;
import ua.eismont.deathechoes.echo.EchoRecording;
import ua.eismont.deathechoes.echo.EchoSpawner;
import ua.eismont.deathechoes.echo.EchoTracker;
import ua.eismont.deathechoes.echo.ModEntities;
import ua.eismont.deathechoes.echo.RecordingManager;

import java.util.List;
import java.util.UUID;

public class EchoEntityGameTest {

    private static List<EchoEntity> echoesInStructure(GameTestHelper helper) {
        return helper.getLevel().getEntities(EntityTypeTest.forClass(EchoEntity.class), helper.getBounds().inflate(4), e -> true);
    }

    @GameTest
    public void deathSpawnsEchoWithXp(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)));
        player.totalExperience = 100;
        for (int i = 0; i < 20; i++) RecordingManager.tickPlayer(player);

        EchoSpawner.onPlayerDeath(player);

        List<EchoEntity> echoes = echoesInStructure(helper);
        if (echoes.size() != 1) helper.fail("expected 1 echo, got " + echoes.size());
        EchoEntity echo = echoes.get(0);
        if (echo.getStoredXp() != 50) helper.fail("expected 50 xp, got " + echo.getStoredXp());
        if (!player.getUUID().equals(echo.getOwnerUUID())) helper.fail("owner mismatch");

        echo.discard();
        RecordingManager.clear(player.getUUID());
        helper.succeed();
    }

    @GameTest
    public void fourthDeathEvictsOldestEcho(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)));

        UUID firstEchoUuid = null;
        for (int death = 0; death < 4; death++) {
            for (int i = 0; i < 5; i++) RecordingManager.tickPlayer(player);
            EchoSpawner.onPlayerDeath(player);
            if (death == 0) {
                List<EchoEntity> afterFirstDeath = echoesInStructure(helper);
                if (afterFirstDeath.size() != 1) {
                    helper.fail("expected 1 echo after first death, got " + afterFirstDeath.size());
                }
                firstEchoUuid = afterFirstDeath.get(0).getUUID();
            }
        }

        List<EchoEntity> finalEchoes = echoesInStructure(helper);
        if (finalEchoes.size() != 3) helper.fail("expected 3 echoes remaining, got " + finalEchoes.size());
        for (EchoEntity echo : finalEchoes) {
            if (echo.getUUID().equals(firstEchoUuid)) helper.fail("expected first echo to be evicted, but it's still present");
            echo.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void echoReplaysLoop(GameTestHelper helper) {
        // 200 frames interpolating x from 1.0 to 5.0 (y=2, z=1 fixed), placed in absolute world
        // space so the assertions below can compare against echo.getX() directly.
        Vec3 origin = helper.absoluteVec(Vec3.ZERO);
        EchoRecording recording = new EchoRecording();
        for (int i = 0; i < EchoRecording.MAX_FRAMES; i++) {
            double x = origin.x + 1.0 + 4.0 * i / (EchoRecording.MAX_FRAMES - 1);
            recording.push(new EchoFrame(x, origin.y + 2, origin.z + 1, 0f, 0f, EchoFrame.Pose.STANDING, ItemStack.EMPTY));
        }

        UUID owner = UUID.randomUUID();
        EchoEntity echo = new EchoEntity(ModEntities.ECHO, helper.getLevel());
        echo.configure(recording, 0, owner, "Test");
        EchoFrame first = recording.frame(0);
        echo.setPos(first.x(), first.y(), first.z());
        helper.getLevel().addFreshEntity(echo);
        // Registered like the real spawn flow: this loop runs the echo past tickCount=200, which
        // is exactly when orphanSelfCheck() first re-verifies tracker membership. An unregistered
        // echo would get discarded right at that tick and this test would be asserting a stale
        // position - registering keeps the replay alive for the full loop under test.
        EchoTracker.get(helper.getLevel()).register(owner, echo.getUUID());

        // EchoEntity.tick() indexes the replay by Entity.tickCount, which is normally advanced by
        // ServerLevel.tickNonPassenger *before* calling tick() every real server tick. Calling
        // tick() directly here bypasses that, so we replicate the increment manually to mirror
        // production behavior exactly.
        for (int t = 0; t < 200; t++) {
            echo.tickCount++;
            echo.tick();
            if (t == 0) {
                double expected = recording.frame(0).x();
                if (Math.abs(echo.getX() - expected) > 0.01) {
                    helper.fail("after 1 tick expected x=" + expected + ", got " + echo.getX());
                }
            }
        }
        double expectedLast = recording.frame(199).x();
        if (Math.abs(echo.getX() - expectedLast) > 0.01) {
            helper.fail("after 200 ticks expected x=" + expectedLast + ", got " + echo.getX());
        }

        for (int t = 0; t < 41; t++) {
            echo.tickCount++;
            echo.tick();
        }
        double expectedLoop = recording.frame(0).x();
        if (Math.abs(echo.getX() - expectedLoop) > 0.01) {
            helper.fail("after pause, expected loop restart x=" + expectedLoop + ", got " + echo.getX());
        }

        echo.discard();
        EchoTracker.get(helper.getLevel()).unregister(owner, echo.getUUID());
        helper.succeed();
    }

    @GameTest
    public void ownerInteractGrantsXpAndDiscards(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.totalExperience = 0;

        EchoEntity echo = new EchoEntity(ModEntities.ECHO, helper.getLevel());
        echo.configure(new EchoRecording(), 50, player.getUUID(), "Test");
        echo.setPos(helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)));
        helper.getLevel().addFreshEntity(echo);
        // Mirrors the real spawn flow (EchoSpawner registers after addFreshEntity).
        EchoTracker.get(helper.getLevel()).register(player.getUUID(), echo.getUUID());

        InteractionResult result = echo.interact(player, InteractionHand.MAIN_HAND, echo.position());

        if (player.totalExperience != 50) helper.fail("expected 50 xp granted, got " + player.totalExperience);
        if (!echo.isRemoved()) helper.fail("expected echo to be discarded after owner interaction");
        if (EchoTracker.get(helper.getLevel()).contains(player.getUUID(), echo.getUUID())) {
            helper.fail("expected echo to be unregistered from tracker after collection");
        }
        if (!result.consumesAction()) helper.fail("expected a consuming interaction result, got " + result);

        // Guard against xp duplication from duplicate interact packets landing in the same tick:
        // the echo is already discarded, so interacting again must be a no-op.
        InteractionResult secondResult = echo.interact(player, InteractionHand.MAIN_HAND, echo.position());
        if (player.totalExperience != 50) {
            helper.fail("expected xp to stay at 50 after a duplicate interact, got " + player.totalExperience);
        }
        if (secondResult.consumesAction()) {
            helper.fail("expected a non-consuming result for a duplicate interact, got " + secondResult);
        }

        helper.succeed();
    }

    @GameTest
    public void strangerInteractIgnored(GameTestHelper helper) {
        Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
        stranger.totalExperience = 0;
        UUID owner = UUID.randomUUID();

        EchoEntity echo = new EchoEntity(ModEntities.ECHO, helper.getLevel());
        echo.configure(new EchoRecording(), 50, owner, "Owner");
        echo.setPos(helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)));
        helper.getLevel().addFreshEntity(echo);
        EchoTracker.get(helper.getLevel()).register(owner, echo.getUUID());

        InteractionResult result = echo.interact(stranger, InteractionHand.MAIN_HAND, echo.position());

        if (!InteractionResult.PASS.equals(result)) helper.fail("expected PASS for a non-owner, got " + result);
        if (echo.isRemoved()) helper.fail("expected echo to remain alive for a stranger interaction");
        if (stranger.totalExperience != 0) helper.fail("expected stranger xp unchanged, got " + stranger.totalExperience);

        echo.discard();
        EchoTracker.get(helper.getLevel()).unregister(owner, echo.getUUID());
        helper.succeed();
    }
}
