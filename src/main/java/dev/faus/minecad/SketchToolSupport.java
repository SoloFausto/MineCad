package dev.faus.minecad;

import java.util.Optional;

import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.SketchPlane;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class SketchToolSupport {
    private static final double RAYCAST_DISTANCE = 64.0D;
    private static final double PARALLEL_EPSILON = 1.0E-6D;

    private SketchToolSupport() {
    }

    static Optional<PlanePoint> pointFromUseOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return Optional.empty();
        }

        Optional<PlaneSketchStack> activePlane = activePlane(context.getLevel(), player);
        if (activePlane.isEmpty()) {
            return Optional.empty();
        }

        BlockPos pos = SketchPlane.pointOnClickedFace(context.getClickedPos(), context.getClickedFace());
        SketchPlane plane = activePlane.get().sketch().plane();
        if (Math.abs(plane.signedDistance(pos)) > 0.0D) {
            if (!context.getLevel().isClientSide()) {
                sendMessage(player, Component.translatable("message.minecad.sketch_tool.axis_mismatch",
                        plane.axis().getName().toUpperCase(), plane.coordinate()));
            }
            return Optional.empty();
        }

        return Optional.of(plane.project(pos));
    }

    static Optional<PlanePoint> pointFromRaycast(Level level, Player player) {
        Optional<PlaneSketchStack> activePlane = activePlane(level, player);
        if (activePlane.isEmpty()) {
            return Optional.empty();
        }

        Optional<PlanePoint> hit = raycastPlanePoint(player, activePlane.get().sketch().plane());
        if (hit.isEmpty() && !level.isClientSide()) {
            sendMessage(player, Component.translatable("message.minecad.sketch_tool.raycast_miss"));
        }
        return hit;
    }

    static Optional<PlaneSketchStack> activePlane(Level level, Player player) {
        Optional<PlaneSketchStack> activePlane = PlaneItem.getActivePlaneSketch(player);
        if (activePlane.isEmpty() || !level.dimension().identifier().toString().equals(activePlane.get().sketch().dimension())) {
            if (!level.isClientSide()) {
                sendMessage(player, Component.translatable("message.minecad.sketch_tool.no_plane"));
            }
            return Optional.empty();
        }
        return activePlane;
    }

    static void sendMessage(Player player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private static Optional<PlanePoint> raycastPlanePoint(Player player, SketchPlane plane) {
        Vec3 origin = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0F);
        double originCoordinate = coordinate(origin, plane);
        double directionCoordinate = coordinate(direction, plane);
        if (Math.abs(directionCoordinate) < PARALLEL_EPSILON) {
            return Optional.empty();
        }

        double distance = (plane.coordinate() - originCoordinate) / directionCoordinate;
        if (distance < 0.0D || distance > RAYCAST_DISTANCE) {
            return Optional.empty();
        }

        Vec3 hit = origin.add(direction.scale(distance));
        return Optional.of(projectHit(plane, hit));
    }

    private static double coordinate(Vec3 vec, SketchPlane plane) {
        return switch (plane.axis()) {
            case X -> vec.x();
            case Y -> vec.y();
            case Z -> vec.z();
        };
    }

    private static PlanePoint projectHit(SketchPlane plane, Vec3 hit) {
        return switch (plane.axis()) {
            case X -> new PlanePoint(floor(hit.y()), floor(hit.z()));
            case Y -> new PlanePoint(floor(hit.x()), floor(hit.z()));
            case Z -> new PlanePoint(floor(hit.x()), floor(hit.y()));
        };
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
