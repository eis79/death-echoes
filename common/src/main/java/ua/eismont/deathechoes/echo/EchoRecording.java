package ua.eismont.deathechoes.echo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * A ring buffer of the last {@link #MAX_FRAMES} {@link EchoFrame}s recorded for a player, used to
 * replay a ghost's final moments after death.
 *
 * <p>Main server thread only; not thread-safe.
 */
public class EchoRecording {

    public static final int MAX_FRAMES = 200;

    private final ArrayDeque<EchoFrame> frames = new ArrayDeque<>(MAX_FRAMES);

    // Cached snapshot for O(1) indexed reads; invalidated on every push since replay reads
    // frame(int) every tick and a fresh ArrayList per push would be wasteful otherwise.
    private List<EchoFrame> cache;

    public void push(EchoFrame frame) {
        frames.addLast(frame);
        while (frames.size() > MAX_FRAMES) {
            frames.removeFirst();
        }
        cache = null;
    }

    public int size() {
        return frames.size();
    }

    public EchoFrame frame(int index) {
        if (cache == null) {
            cache = new ArrayList<>(frames);
        }
        return cache.get(index);
    }

    public CompoundTag toTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (EchoFrame frame : frames) {
            list.add(frame.toTag(registries));
        }
        tag.put("frames", list);
        return tag;
    }

    public static EchoRecording fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        EchoRecording recording = new EchoRecording();
        ListTag list = tag.getListOrEmpty("frames");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag frameTag = list.getCompoundOrEmpty(i);
            recording.push(EchoFrame.fromTag(frameTag, registries));
        }
        return recording;
    }
}
