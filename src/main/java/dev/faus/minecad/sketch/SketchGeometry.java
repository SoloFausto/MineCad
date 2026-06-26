package dev.faus.minecad.sketch;

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

    public static boolean contains(SketchObject object, double u, double v) {
        return signedDistance(object, u, v) <= 0.0D;
    }

    public static double signedDistance(SketchObject object, double u, double v) {
        if (object instanceof SketchObject.Polygon polygon) {
            if (polygon.vertices().size() < 3) {
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
    }
}
