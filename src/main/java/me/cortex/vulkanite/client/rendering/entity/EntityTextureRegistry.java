package me.cortex.vulkanite.client.rendering.entity;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EntityTextureRegistry {
    public static final int CAPACITY = 256;
    public static final EntityTextureRegistry INSTANCE = new EntityTextureRegistry();

    private final Map<Identifier, Integer> indices = new HashMap<>();
    private final List<Identifier> textures = new ArrayList<>();

    private EntityTextureRegistry() {
    }

    public synchronized int indexOf(Identifier texture) {
        Integer existing = indices.get(texture);
        if (existing != null) {
            return existing;
        }
        if (textures.size() >= CAPACITY) {
            return -1;
        }
        int index = textures.size();
        textures.add(texture);
        indices.put(texture, index);
        return index;
    }

    public synchronized Identifier get(int index) {
        return index >= 0 && index < textures.size() ? textures.get(index) : null;
    }

    public synchronized int size() {
        return textures.size();
    }
}
