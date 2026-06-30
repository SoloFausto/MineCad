package dev.faus.minecad.client;

import java.util.Optional;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.faus.minecad.PlaneItem;
import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.BoxToolItem;
import dev.faus.minecad.CircleToolItem;
import dev.faus.minecad.SelectToolItem;
import dev.faus.minecad.PolygonToolItem;
import dev.faus.minecad.DebugToolItem;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlaneSketchData.SketchObject;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.SketchGeometry;
import dev.faus.minecad.sketch.SketchGeometry.Bounds;
import dev.faus.minecad.sketch.SketchGeometry.FaceRegion;
import dev.faus.minecad.sketch.SketchPlane;
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
    private static final int SELECTED_POLYGON_COLOR = 0xAA55CCFF;
    private static final int FACE_BORDER_COLOR = 0xFF000000;
    private static final int DEBUG_BODY_COLOR = 0x00FFFF00;
    private static final int MAX_SDF_CELLS = 4096;
    private static final int EMPTY_PLANE_RADIUS = 8;
    private static final int PLANE_PADDING = 2;
    private static final double PLANE_RENDER_OFFSET = 0.03D;
    private static final double FACE_BORDER_WIDTH = 0.05D;

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

        boolean holdingSketchItem = isHoldingSketchItem(client);
        if (!holdingSketchItem) {
            return;
        }

        Vec3 camera = client.gameRenderer.getMainCamera().position();
        PoseStack matrices = context.poseStack();

        PlaneSketchStack active = PlaneItem.getActivePlaneSketch(client.player)
                .orElse(null);

        if (active != null && client.level.dimension().identifier().toString().equals(active.sketch().dimension())) {
            PlaneSketch sketch = active.sketch();
            renderSketchPlane(matrices, context, sketch, camera);
            renderSketchObjects(matrices, context, sketch, selectedObjectIndex(client, sketch), camera);
            renderDebugBody(matrices, context, client, camera);
        }

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
        return stack.getItem() instanceof PlaneItem || stack.getItem() instanceof PolygonToolItem
                || stack.getItem() instanceof BoxToolItem || stack.getItem() instanceof CircleToolItem
                || stack.getItem() instanceof SelectToolItem || stack.getItem() instanceof DebugToolItem;
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
            Bounds bounds = SketchGeometry.bounds(object);
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
            Optional<SelectToolItem.Selection> selection, Vec3 camera) {
        for (SketchObject object : sketch.objects()) {
            renderVertices(matrices, context, sketch, object, camera);
        }

        for (FaceRegion region : SketchGeometry.faceRegions(sketch)) {
            Bounds bounds = region.bounds();
            int cells = (bounds.maxU() - bounds.minU() + 1) * (bounds.maxV() - bounds.minV() + 1);
            if (cells > MAX_SDF_CELLS) {
                continue;
            }

            int color = selection.isPresent() && selection.get().faces().stream().anyMatch(face -> face.matches(region))
                    ? SELECTED_POLYGON_COLOR
                    : POLYGON_COLOR;
            renderFaceRegion(matrices, context, sketch, region, color, camera);
            renderFaceBorder(matrices, context, sketch, region, camera);
        }
    }

    private static void renderFaceRegion(PoseStack matrices, LevelRenderContext context, PlaneSketch sketch,
            FaceRegion region, int color, Vec3 camera) {
        VertexConsumer quads = context.bufferSource().getBuffer(RenderTypes.debugQuads());
        Bounds bounds = region.bounds();
        for (int u = bounds.minU(); u <= bounds.maxU(); u++) {
            for (int v = bounds.minV(); v <= bounds.maxV(); v++) {
                if (SketchGeometry.contains(region, sketch, u, v)) {
                    addPlaneQuad(matrices, quads, sketch.plane(), sketch.renderOffsetSign(), u - 0.01D, v - 0.01D,
                            u + 1.01D, v + 1.01D, camera, color);
                }
            }
        }
    }

    private static void renderFaceBorder(PoseStack matrices, LevelRenderContext context, PlaneSketch sketch,
            FaceRegion region, Vec3 camera) {
        VertexConsumer quads = context.bufferSource().getBuffer(RenderTypes.debugQuads());
        Bounds bounds = region.bounds();
        for (int u = bounds.minU(); u <= bounds.maxU(); u++) {
            for (int v = bounds.minV(); v <= bounds.maxV(); v++) {
                if (!SketchGeometry.contains(region, sketch, u, v)) {
                    continue;
                }

                if (!SketchGeometry.contains(region, sketch, u - 1, v)) {
                    addPlaneQuad(matrices, quads, sketch.plane(), sketch.renderOffsetSign(), u - FACE_BORDER_WIDTH,
                            v - FACE_BORDER_WIDTH, u + FACE_BORDER_WIDTH, v + 1.0D + FACE_BORDER_WIDTH, camera,
                            FACE_BORDER_COLOR);
                }
                if (!SketchGeometry.contains(region, sketch, u + 1, v)) {
                    addPlaneQuad(matrices, quads, sketch.plane(), sketch.renderOffsetSign(),
                            u + 1.0D - FACE_BORDER_WIDTH,
                            v - FACE_BORDER_WIDTH, u + 1.0D + FACE_BORDER_WIDTH, v + 1.0D + FACE_BORDER_WIDTH, camera,
                            FACE_BORDER_COLOR);
                }
                if (!SketchGeometry.contains(region, sketch, u, v - 1)) {
                    addPlaneQuad(matrices, quads, sketch.plane(), sketch.renderOffsetSign(), u - FACE_BORDER_WIDTH,
                            v - FACE_BORDER_WIDTH, u + 1.0D + FACE_BORDER_WIDTH, v + FACE_BORDER_WIDTH, camera,
                            FACE_BORDER_COLOR);
                }
                if (!SketchGeometry.contains(region, sketch, u, v + 1)) {
                    addPlaneQuad(matrices, quads, sketch.plane(), sketch.renderOffsetSign(), u - FACE_BORDER_WIDTH,
                            v + 1.0D - FACE_BORDER_WIDTH, u + 1.0D + FACE_BORDER_WIDTH, v + 1.0D + FACE_BORDER_WIDTH,
                            camera, FACE_BORDER_COLOR);
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

    private static void renderDebugBody(PoseStack matrices, LevelRenderContext context, Minecraft client,
            Vec3 camera) {
        int color = blinkingColor(client.level.getGameTime());
        VertexConsumer quads = context.bufferSource().getBuffer(RenderTypes.debugQuads());
        for (BlockPos pos : debugBodyPositions(client)) {
            if (!client.level.getBlockState(pos).isAir()) {
                renderBlockOverlay(matrices, quads, pos, camera, color);
            }
        }
    }

    private static List<BlockPos> debugBodyPositions(Minecraft client) {
        List<BlockPos> mainHand = DebugToolItem.selectedPositions(client.player.getMainHandItem());
        if (!mainHand.isEmpty()) {
            return mainHand;
        }
        return DebugToolItem.selectedPositions(client.player.getOffhandItem());
    }

    private static int blinkingColor(long gameTime) {
        double phase = (Math.sin(gameTime * 0.45D) + 1.0D) * 0.5D;
        int alpha = 48 + (int) Math.round(phase * 128.0D);
        return (alpha << 24) | DEBUG_BODY_COLOR;
    }

    private static void renderBlockOverlay(PoseStack matrices, VertexConsumer consumer, BlockPos pos, Vec3 camera,
            int color) {
        double minX = pos.getX() - camera.x() - 0.002D;
        double minY = pos.getY() - camera.y() - 0.002D;
        double minZ = pos.getZ() - camera.z() - 0.002D;
        double maxX = pos.getX() + 1.0D - camera.x() + 0.002D;
        double maxY = pos.getY() + 1.0D - camera.y() + 0.002D;
        double maxZ = pos.getZ() + 1.0D - camera.z() + 0.002D;

        addQuad(matrices, consumer, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, color);
        addQuad(matrices, consumer, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ, color);
        addQuad(matrices, consumer, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, color);
        addQuad(matrices, consumer, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        addQuad(matrices, consumer, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, color);
        addQuad(matrices, consumer, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color);
    }

    private static void addQuad(PoseStack matrices, VertexConsumer consumer, double x1, double y1, double z1,
            double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4,
            int color) {
        addVertex(matrices, consumer, x1, y1, z1, color);
        addVertex(matrices, consumer, x2, y2, z2, color);
        addVertex(matrices, consumer, x3, y3, z3, color);
        addVertex(matrices, consumer, x4, y4, z4, color);
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

    private static Optional<SelectToolItem.Selection> selectedObjectIndex(Minecraft client, PlaneSketch sketch) {
        Optional<SelectToolItem.Selection> mainHand = SelectToolItem.selectedFace(client.player.getMainHandItem());
        if (mainHand.isPresent() && mainHand.get().planeId().equals(sketch.id())) {
            return mainHand;
        }
        Optional<SelectToolItem.Selection> offHand = SelectToolItem.selectedFace(client.player.getOffhandItem());
        return offHand.filter(selection -> selection.planeId().equals(sketch.id()));
    }

}
