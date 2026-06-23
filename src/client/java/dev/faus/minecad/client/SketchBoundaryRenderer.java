package dev.faus.minecad.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.faus.minecad.PlaneItem;
import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.BoxToolItem;
import dev.faus.minecad.CircleToolItem;
import dev.faus.minecad.SketchToolItem;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlaneSketchData.SketchObject;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.SketchPlane;
import dev.faus.minecad.sketch.SketchPolygon;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

public final class SketchBoundaryRenderer {
    private static final int GHOST_BLOCK_COLOR = 0x99FFFFFF;
    private static final int SKETCH_PLANE_COLOR = 0x33FFFFFF;
    private static final int POLYGON_COLOR = 0x66FFFFFF;
    private static final int MAX_SDF_CELLS = 4096;
    private static final int EMPTY_PLANE_RADIUS = 8;
    private static final int PLANE_PADDING = 2;
    private static final double PLANE_RENDER_OFFSET = 0.03D;

    private SketchBoundaryRenderer() {
    }

    public static void initialize() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(SketchBoundaryRenderer::render);
    }

    private static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        if (!isHoldingSketchItem(client)) {
            return;
        }

        PlaneSketchStack active = PlaneItem.getActivePlaneSketch(client.player)
                .orElse(null);
        if (active == null || !client.level.dimension().identifier().toString().equals(active.sketch().dimension())) {
            return;
        }

        Vec3 camera = client.gameRenderer.getMainCamera().position();
        PoseStack matrices = context.poseStack();

        PlaneSketch sketch = active.sketch();
        renderSketchPlane(matrices, context, sketch, camera);
        renderSketchObjects(matrices, context, sketch, camera);

        context.bufferSource().endBatch(RenderTypes.debugQuads());
        context.bufferSource().endBatch(RenderTypes.linesTranslucent());
    }

    private static boolean isHoldingSketchItem(Minecraft client) {
        ItemStack mainHand = client.player.getMainHandItem();
        if (isSketchItem(mainHand)) {
            return true;
        }

        ItemStack offHand = client.player.getOffhandItem();
        return isSketchItem(offHand);
    }

    private static boolean isSketchItem(ItemStack stack) {
        return stack.getItem() instanceof PlaneItem || stack.getItem() instanceof SketchToolItem
                || stack.getItem() instanceof BoxToolItem || stack.getItem() instanceof CircleToolItem;
    }

    private static void renderGhostBlock(PoseStack matrices, LevelRenderContext context, BlockPos pos, Vec3 camera) {
        VertexConsumer lines = context.bufferSource().getBuffer(RenderTypes.linesTranslucent());
        ShapeRenderer.renderShape(matrices, lines, Shapes.block(),
                pos.getX() - camera.x(), pos.getY() - camera.y(), pos.getZ() - camera.z(),
                GHOST_BLOCK_COLOR, 1.0F);
    }

    private static void renderSketchPlane(PoseStack matrices, LevelRenderContext context, PlaneSketch sketch,
            Vec3 camera) {
        PlanePoint origin = sketch.origin();
        int minU = origin.u() - EMPTY_PLANE_RADIUS;
        int maxU = origin.u() + EMPTY_PLANE_RADIUS + 1;
        int minV = origin.v() - EMPTY_PLANE_RADIUS;
        int maxV = origin.v() + EMPTY_PLANE_RADIUS + 1;

        for (SketchObject object : sketch.objects()) {
            Bounds bounds = bounds(object);
            if (bounds != null) {
                minU = Math.min(minU, bounds.minU() - PLANE_PADDING);
                maxU = Math.max(maxU, bounds.maxU() + PLANE_PADDING + 1);
                minV = Math.min(minV, bounds.minV() - PLANE_PADDING);
                maxV = Math.max(maxV, bounds.maxV() + PLANE_PADDING + 1);
            }
        }

        VertexConsumer quads = context.bufferSource().getBuffer(RenderTypes.debugQuads());
        addPlaneQuad(matrices, quads, sketch.plane(), sketch.renderOffsetSign(), minU, minV, maxU, maxV, camera,
                SKETCH_PLANE_COLOR);
    }

    private static void renderSketchObjects(PoseStack matrices, LevelRenderContext context, PlaneSketch sketch,
            Vec3 camera) {
        for (SketchObject object : sketch.objects()) {
            renderVertices(matrices, context, sketch, object, camera);
            Bounds bounds = bounds(object);
            if (bounds != null) {
                renderObjectSdf(matrices, context, sketch, object, bounds, camera);
            }
        }
    }

    private static void renderObjectSdf(PoseStack matrices, LevelRenderContext context, PlaneSketch sketch,
            SketchObject object, Bounds bounds, Vec3 camera) {
        if (object instanceof SketchObject.Polygon polygon && polygon.vertices().size() < 3) {
            return;
        }

        int cells = (bounds.maxU() - bounds.minU() + 1) * (bounds.maxV() - bounds.minV() + 1);
        if (cells > MAX_SDF_CELLS) {
            return;
        }

        VertexConsumer quads = context.bufferSource().getBuffer(RenderTypes.debugQuads());
        for (int u = bounds.minU(); u <= bounds.maxU(); u++) {
            for (int v = bounds.minV(); v <= bounds.maxV(); v++) {
                if (signedDistance(object, u + 0.5D, v + 0.5D) <= 0.0D) {
                    addPlaneQuad(matrices, quads, sketch.plane(), sketch.renderOffsetSign(), u - 0.01D, v - 0.01D,
                            u + 1.01D, v + 1.01D, camera, POLYGON_COLOR);
                }
            }
        }
    }

    private static void renderVertices(PoseStack matrices, LevelRenderContext context, PlaneSketch sketch,
            SketchObject object, Vec3 camera) {
        if (object instanceof SketchObject.Polygon polygon) {
            for (PlanePoint vertex : polygon.vertices()) {
                renderGhostBlock(matrices, context, sketch.plane().unproject(vertex), camera);
            }
        } else if (object instanceof SketchObject.Box box) {
            renderGhostBlock(matrices, context, sketch.plane().unproject(box.first()), camera);
            renderGhostBlock(matrices, context, sketch.plane().unproject(box.second()), camera);
        } else if (object instanceof SketchObject.Circle circle) {
            renderGhostBlock(matrices, context, sketch.plane().unproject(circle.center()), camera);
        }
    }

    private static Bounds bounds(SketchObject object) {
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

    private static double signedDistance(SketchObject object, double u, double v) {
        if (object instanceof SketchObject.Polygon polygon) {
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

    private static void addPlaneQuad(PoseStack matrices, VertexConsumer consumer, SketchPlane plane,
            int renderOffsetSign, double minU, double minV, double maxU, double maxV, Vec3 camera, int color) {
        double planeCoordinate = plane.coordinate() + PLANE_RENDER_OFFSET * renderOffsetSign;

        switch (plane.axis()) {
            case X -> addDoubleSidedQuad(matrices, consumer,
                    planeCoordinate - camera.x(), minU - camera.y(), minV - camera.z(),
                    planeCoordinate - camera.x(), maxU - camera.y(), maxV - camera.z(), plane.axis(), color);
            case Y -> addDoubleSidedQuad(matrices, consumer,
                    minU - camera.x(), planeCoordinate - camera.y(), minV - camera.z(),
                    maxU - camera.x(), planeCoordinate - camera.y(), maxV - camera.z(), plane.axis(), color);
            case Z -> addDoubleSidedQuad(matrices, consumer,
                    minU - camera.x(), minV - camera.y(), planeCoordinate - camera.z(),
                    maxU - camera.x(), maxV - camera.y(), planeCoordinate - camera.z(), plane.axis(), color);
        }
    }

    private static void addDoubleSidedQuad(PoseStack matrices, VertexConsumer consumer, double x1, double y1,
            double z1, double x2, double y2, double z2, Axis axis, int color) {
        switch (axis) {
            case X -> {
                addVertex(matrices, consumer, x1, y1, z1, color);
                addVertex(matrices, consumer, x1, y2, z1, color);
                addVertex(matrices, consumer, x1, y2, z2, color);
                addVertex(matrices, consumer, x1, y1, z2, color);
                addVertex(matrices, consumer, x1, y1, z2, color);
                addVertex(matrices, consumer, x1, y2, z2, color);
                addVertex(matrices, consumer, x1, y2, z1, color);
                addVertex(matrices, consumer, x1, y1, z1, color);
            }
            case Y -> {
                addVertex(matrices, consumer, x1, y1, z1, color);
                addVertex(matrices, consumer, x1, y1, z2, color);
                addVertex(matrices, consumer, x2, y1, z2, color);
                addVertex(matrices, consumer, x2, y1, z1, color);
                addVertex(matrices, consumer, x2, y1, z1, color);
                addVertex(matrices, consumer, x2, y1, z2, color);
                addVertex(matrices, consumer, x1, y1, z2, color);
                addVertex(matrices, consumer, x1, y1, z1, color);
            }
            case Z -> {
                addVertex(matrices, consumer, x1, y1, z1, color);
                addVertex(matrices, consumer, x2, y1, z1, color);
                addVertex(matrices, consumer, x2, y2, z1, color);
                addVertex(matrices, consumer, x1, y2, z1, color);
                addVertex(matrices, consumer, x1, y2, z1, color);
                addVertex(matrices, consumer, x2, y2, z1, color);
                addVertex(matrices, consumer, x2, y1, z1, color);
                addVertex(matrices, consumer, x1, y1, z1, color);
            }
        }
    }

    private static void addVertex(PoseStack matrices, VertexConsumer consumer, double x, double y, double z,
            int color) {
        consumer.addVertex(matrices.last(), (float) x, (float) y, (float) z).setColor(color);
    }

    private record Bounds(int minU, int maxU, int minV, int maxV) {
    }

}
