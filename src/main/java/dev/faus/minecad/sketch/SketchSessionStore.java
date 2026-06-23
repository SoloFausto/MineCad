package dev.faus.minecad.sketch;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.world.entity.player.Player;

public final class SketchSessionStore {
    private static final ConcurrentMap<UUID, UUID> ACTIVE_PLANES = new ConcurrentHashMap<>();

    private SketchSessionStore() {
    }

    public static void setActivePlane(Player player, UUID planeSketchId) {
        ACTIVE_PLANES.put(player.getUUID(), planeSketchId);
    }

    public static Optional<UUID> getActivePlane(Player player) {
        return Optional.ofNullable(ACTIVE_PLANES.get(player.getUUID()));
    }
}
