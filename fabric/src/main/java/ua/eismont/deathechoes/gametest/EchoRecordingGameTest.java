package ua.eismont.deathechoes.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ua.eismont.deathechoes.echo.EchoFrame;
import ua.eismont.deathechoes.echo.EchoRecording;
import ua.eismont.deathechoes.echo.RecordingManager;

public class EchoRecordingGameTest {

    @GameTest
    public void recordingCapsAt200AndRoundTrips(GameTestHelper helper) {
        EchoRecording rec = new EchoRecording();
        for (int i = 0; i < 250; i++) {
            rec.push(new EchoFrame(i, 64.0, 0.0, 90f, 10f, EchoFrame.Pose.STANDING, ItemStack.EMPTY));
        }
        if (rec.size() != 200) helper.fail("expected 200 frames, got " + rec.size());
        if (rec.frame(0).x() != 50.0) helper.fail("oldest frame should be i=50, got x=" + rec.frame(0).x());
        CompoundTag tag = rec.toTag(helper.getLevel().registryAccess());
        EchoRecording back = EchoRecording.fromTag(tag, helper.getLevel().registryAccess());
        if (back.size() != 200) helper.fail("round-trip size mismatch: " + back.size());
        if (back.frame(199).yaw() != 90f) helper.fail("round-trip yaw mismatch");
        helper.succeed();
    }

    @GameTest
    public void roundTripPreservesMainHandItem(GameTestHelper helper) {
        EchoRecording rec = new EchoRecording();
        rec.push(new EchoFrame(0.0, 64.0, 0.0, 0f, 0f, EchoFrame.Pose.STANDING, new ItemStack(Items.IRON_SWORD)));
        CompoundTag tag = rec.toTag(helper.getLevel().registryAccess());
        EchoRecording back = EchoRecording.fromTag(tag, helper.getLevel().registryAccess());
        if (!back.frame(0).mainHand().is(Items.IRON_SWORD)) {
            helper.fail("expected main hand item to round-trip as iron sword, got " + back.frame(0).mainHand());
        }
        helper.succeed();
    }

    @GameTest
    public void malformedNbtDegradesGracefully(GameTestHelper helper) {
        CompoundTag emptyTag = new CompoundTag();
        EchoRecording emptyRecording = EchoRecording.fromTag(emptyTag, helper.getLevel().registryAccess());
        if (emptyRecording.size() != 0) {
            helper.fail("expected empty recording for a tag missing \"frames\", got size=" + emptyRecording.size());
        }

        CompoundTag badFrame = new CompoundTag();
        badFrame.putByte("pose", (byte) 99);
        ListTag frames = new ListTag();
        frames.add(badFrame);
        CompoundTag badPoseTag = new CompoundTag();
        badPoseTag.put("frames", frames);
        EchoRecording badPoseRecording = EchoRecording.fromTag(badPoseTag, helper.getLevel().registryAccess());
        if (badPoseRecording.size() != 1) {
            helper.fail("expected 1 frame for malformed pose tag, got size=" + badPoseRecording.size());
        }
        if (badPoseRecording.frame(0).pose() != EchoFrame.Pose.STANDING) {
            helper.fail("expected malformed pose byte to degrade to STANDING, got " + badPoseRecording.frame(0).pose());
        }
        helper.succeed();
    }

    @GameTest
    public void managerRecordsPlayer(GameTestHelper helper) {
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.setPos(1.5, 64, 2.5);
        for (int i = 0; i < 250; i++) RecordingManager.tickPlayer(player);
        var rec = RecordingManager.recordingFor(player.getUUID());
        if (rec == null || rec.size() != 200) helper.fail("expected 200 recorded frames, got " + (rec == null ? "null" : rec.size()));
        if (Math.abs(rec.frame(199).x() - 1.5) > 0.001) helper.fail("frame x mismatch");
        RecordingManager.clear(player.getUUID());
        if (RecordingManager.recordingFor(player.getUUID()) != null) helper.fail("clear failed");
        helper.succeed();
    }
}
