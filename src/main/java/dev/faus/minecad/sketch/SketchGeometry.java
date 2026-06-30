package dev.faus.minecad.sketch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlaneSketchData.SketchObject;

public final class SketchGeometry {
    private SketchGeometry() {
    }

    public static Bounds bounds(SketchObject object) {
        if (object instanceof SketchObject.Polygon polygon && !polygon.vertices().isEmpty()) {
            SketchPolygon sdf = new SketchPolygon(null, polygon.vertices());
            return new Bounds(sdf.minU(), sdf.maxU(), sdf.minV(), sdf.maxV());
        }
        if (object instanceof SketchObject.Box box) {
            return new Bounds(Math.min(box.first().u(), box.second().u()), Math.max(box.first().u(), box.second().u()),
                    Math.min(box.first().v(), box.second().v()), Math.max(box.first().v(), box.second().v()));
        }
        if (object instanceof SketchObject.Circle circle) {
            int radius = (int) Math.ceil(circle.radius());
            return new Bounds(circle.center().u() - radius, circle.center().u() + radius,
                    circle.center().v() - radius, circle.center().v() + radius);
        }
        return null;
    }

    public static Bounds bounds(PlaneSketch sketch) {
        Bounds bounds = null;
        for (SketchObject object : sketch.objects()) {
            Bounds objectBounds = bounds(object);
            if (objectBounds != null) {
                bounds = bounds == null ? objectBounds : bounds.include(objectBounds);
            }
        }
        return bounds;
    }

    public static boolean contains(SketchObject object, double u, double v) {
        return signedDistance(object, u, v) <= 0.0D;
    }

    public static List<Integer> containingObjectIndices(PlaneSketch sketch, PlanePoint point) {
        return containingObjectIndices(sketch, point.u() + 0.5D, point.v() + 0.5D);
    }

    public static List<Integer> containingObjectIndices(PlaneSketch sketch, double u, double v) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < sketch.objects().size(); i++) {
            if (contains(sketch.objects().get(i), u, v)) {
                indices.add(i);
            }
        }
        return List.copyOf(indices);
    }

    public static FaceRegion faceRegionAt(PlaneSketch sketch, PlanePoint point) {
        for (FaceRegion region : faceRegions(sketch)) {
            if (contains(region, point.u(), point.v())) {
                return region;
            }
        }
        return null;
    }

    public static List<FaceRegion> faceRegions(PlaneSketch sketch) {
        Bounds sketchBounds = bounds(sketch);
        if (sketchBounds == null) {
            return List.of();
        }

        Set<PlanePoint> visited = new HashSet<>();
        List<FaceRegion> regions = new ArrayList<>();
        for (int u = sketchBounds.minU(); u <= sketchBounds.maxU(); u++) {
            for (int v = sketchBounds.minV(); v <= sketchBounds.maxV(); v++) {
                PlanePoint seed = new PlanePoint(u, v);
                if (visited.contains(seed)) {
                    continue;
                }

                List<Integer> objectIndices = containingObjectIndices(sketch, u + 0.5D, v + 0.5D);
                if (objectIndices.isEmpty()) {
                    visited.add(seed);
                    continue;
                }

                regions.add(floodRegion(sketch, sketchBounds, seed, objectIndices, visited));
            }
        }
        return List.copyOf(regions);
    }

    public static boolean contains(FaceRegion region, int u, int v) {
        return region.cells().contains(new PlanePoint(u, v));
    }

    public static boolean contains(FaceRegion region, PlaneSketch sketch, int u, int v) {
        return contains(region, u, v);
    }

    private static FaceRegion floodRegion(PlaneSketch sketch, Bounds sketchBounds, PlanePoint seed,
            List<Integer> objectIndices, Set<PlanePoint> visited) {
        ArrayDeque<PlanePoint> pending = new ArrayDeque<>();
        List<PlanePoint> cells = new ArrayList<>();
        Bounds bounds = null;
        pending.add(seed);
        visited.add(seed);

        while (!pending.isEmpty()) {
            PlanePoint cell = pending.removeFirst();
            cells.add(cell);
            Bounds cellBounds = new Bounds(cell.u(), cell.u(), cell.v(), cell.v());
            bounds = bounds == null ? cellBounds : bounds.include(cellBounds);

            addNeighbor(sketch, sketchBounds, objectIndices, visited, pending, cell.u() - 1, cell.v());
            addNeighbor(sketch, sketchBounds, objectIndices, visited, pending, cell.u() + 1, cell.v());
            addNeighbor(sketch, sketchBounds, objectIndices, visited, pending, cell.u(), cell.v() - 1);
            addNeighbor(sketch, sketchBounds, objectIndices, visited, pending, cell.u(), cell.v() + 1);
        }

        return new FaceRegion(objectIndices, bounds, cells);
    }

    private static void addNeighbor(PlaneSketch sketch, Bounds sketchBounds, List<Integer> objectIndices,
            Set<PlanePoint> visited, ArrayDeque<PlanePoint> pending, int u, int v) {
        if (u < sketchBounds.minU() || u > sketchBounds.maxU() || v < sketchBounds.minV() || v > sketchBounds.maxV()) {
            return;
        }

        PlanePoint point = new PlanePoint(u, v);
        if (visited.contains(point)) {
            return;
        }

        if (!objectIndices.equals(containingObjectIndices(sketch, u + 0.5D, v + 0.5D))) {
            return;
        }

        visited.add(point);
        pending.add(point);
    }

    public static double signedDistance(SketchObject object, double u, double v) {
        if (object instanceof SketchObject.Polygon polygon) {
            if (!polygon.closed() || polygon.vertices().size() < 3) {
                return Double.POSITIVE_INFINITY;
            }
            return new SketchPolygon(null, polygon.vertices()).signedDistance(u, v);
        }
        if (object instanceof SketchObject.Box box) {
            double centerU = (box.first().u() + box.second().u() + 1.0D) * 0.5D;
            double centerV = (box.first().v() + box.second().v() + 1.0D) * 0.5D;
            double halfU = (Math.abs(box.first().u() - box.second().u()) + 1.0D) * 0.5D;
            double halfV = (Math.abs(box.first().v() - box.second().v()) + 1.0D) * 0.5D;
            double du = Math.abs(u - centerU) - halfU;
            double dv = Math.abs(v - centerV) - halfV;
            double outsideU = Math.max(du, 0.0D);
            double outsideV = Math.max(dv, 0.0D);
            return Math.hypot(outsideU, outsideV) + Math.min(Math.max(du, dv), 0.0D);
        }
        if (object instanceof SketchObject.Circle circle) {
            return Math.hypot(u - (circle.center().u() + 0.5D), v - (circle.center().v() + 0.5D))
                    - circle.radius();
        }
        return Double.POSITIVE_INFINITY;
    }

    public record Bounds(int minU, int maxU, int minV, int maxV) {
        public Bounds include(Bounds other) {
            return new Bounds(Math.min(minU, other.minU), Math.max(maxU, other.maxU),
                    Math.min(minV, other.minV), Math.max(maxV, other.maxV));
        }
    }

    public record FaceRegion(List<Integer> objectIndices, Bounds bounds, List<PlanePoint> cells) {
        public FaceRegion {
            objectIndices = List.copyOf(objectIndices);
            cells = List.copyOf(cells);
        }

        public PlanePoint seed() {
            return cells.getFirst();
        }
    }
}
