package ua.eismont.deathechoes.echo;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

/**
 * A single tick of a recorded player death echo: position, look direction, pose and held item.
 */
public record EchoFrame(double x, double y, double z, float yaw, float pitch, Pose pose, ItemStack mainHand) {

    public enum Pose {
        STANDING,
        SNEAKING,
        SPRINTING
    }

    public CompoundTag toTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putFloat("yaw", yaw);
        tag.putFloat("pitch", pitch);
        tag.putByte("pose", (byte) pose.ordinal());
        if (!mainHand.isEmpty()) {
            tag.store("item", ItemStack.CODEC, registries.createSerializationContext(NbtOps.INSTANCE), mainHand);
        }
        return tag;
    }

    public static EchoFrame fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        double x = tag.getDoubleOr("x", 0.0);
        double y = tag.getDoubleOr("y", 0.0);
        double z = tag.getDoubleOr("z", 0.0);
        float yaw = tag.getFloatOr("yaw", 0f);
        float pitch = tag.getFloatOr("pitch", 0f);
        byte poseOrdinal = tag.getByteOr("pose", (byte) 0);
        Pose[] poses = Pose.values();
        Pose pose = (poseOrdinal >= 0 && poseOrdinal < poses.length) ? poses[poseOrdinal] : Pose.STANDING;
        ItemStack mainHand = tag.read("item", ItemStack.CODEC, registries.createSerializationContext(NbtOps.INSTANCE))
                .orElse(ItemStack.EMPTY);
        return new EchoFrame(x, y, z, yaw, pitch, pose, mainHand);
    }
}
