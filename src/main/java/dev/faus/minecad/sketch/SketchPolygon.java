package dev.faus.minecad.sketch;

import java.util.List;

public record SketchPolygon(SketchPlane plane, List<PlanePoint> vertices) {
    public SketchPolygon {
        vertices = List.copyOf(vertices);
    }

    public double signedDistance(double u, double v) {
        if (vertices.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double minDistanceSquared = Double.POSITIVE_INFINITY;
        boolean inside = false;

        for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
            PlanePoint a = vertices.get(j);
            PlanePoint b = vertices.get(i);
            minDistanceSquared = Math.min(minDistanceSquared, distanceToSegmentSquared(u, v, a, b));

            if (((a.v() > v) != (b.v() > v))
                    && (u < (double) (b.u() - a.u()) * (v - a.v()) / (b.v() - a.v()) + a.u())) {
                inside = !inside;
            }
        }

        double distance = Math.sqrt(minDistanceSquared);
        return inside ? -distance : distance;
    }

    public int minU() {
        return vertices.stream().mapToInt(PlanePoint::u).min().orElse(0);
    }

    public int maxU() {
        return vertices.stream().mapToInt(PlanePoint::u).max().orElse(0);
    }

    public int minV() {
        return vertices.stream().mapToInt(PlanePoint::v).min().orElse(0);
    }

    public int maxV() {
        return vertices.stream().mapToInt(PlanePoint::v).max().orElse(0);
    }

    private static double distanceToSegmentSquared(double u, double v, PlanePoint a, PlanePoint b) {
        double du = b.u() - a.u();
        double dv = b.v() - a.v();
        double lengthSquared = du * du + dv * dv;
        if (lengthSquared == 0.0D) {
            return distanceSquared(u, v, a.u(), a.v());
        }

        double t = ((u - a.u()) * du + (v - a.v()) * dv) / lengthSquared;
        t = Math.clamp(t, 0.0D, 1.0D);
        return distanceSquared(u, v, a.u() + t * du, a.v() + t * dv);
    }

    private static double distanceSquared(double u1, double v1, double u2, double v2) {
        double du = u1 - u2;
        double dv = v1 - v2;
        return du * du + dv * dv;
    }
}
