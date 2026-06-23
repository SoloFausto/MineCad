package dev.faus.minecad.sketch;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;

public record SketchPlane(Axis axis, int coordinate) {
    public static SketchPlane fromClickedFace(BlockPos clickedPos, Direction face) {
        Axis axis = face.getAxis();
        int coordinate = axisCoordinate(clickedPos, axis);
        if (face.getAxisDirection().getStep() > 0) {
            coordinate++;
        }
        return new SketchPlane(axis, coordinate);
    }

    public static BlockPos pointOnClickedFace(BlockPos clickedPos, Direction face) {
        return fromClickedFace(clickedPos, face).unproject(switch (face.getAxis()) {
            case X -> new PlanePoint(clickedPos.getY(), clickedPos.getZ());
            case Y -> new PlanePoint(clickedPos.getX(), clickedPos.getZ());
            case Z -> new PlanePoint(clickedPos.getX(), clickedPos.getY());
        });
    }

    public double signedDistance(BlockPos pos) {
        return switch (axis) {
            case X -> pos.getX() - coordinate;
            case Y -> pos.getY() - coordinate;
            case Z -> pos.getZ() - coordinate;
        };
    }

    public PlanePoint project(BlockPos pos) {
        return switch (axis) {
            case X -> new PlanePoint(pos.getY(), pos.getZ());
            case Y -> new PlanePoint(pos.getX(), pos.getZ());
            case Z -> new PlanePoint(pos.getX(), pos.getY());
        };
    }

    public BlockPos unproject(PlanePoint point) {
        return switch (axis) {
            case X -> new BlockPos(coordinate, point.u(), point.v());
            case Y -> new BlockPos(point.u(), coordinate, point.v());
            case Z -> new BlockPos(point.u(), point.v(), coordinate);
        };
    }

    private static int axisCoordinate(BlockPos pos, Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }
}
