package dev.faus.minecad.sketch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.Direction.Axis;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class PlaneSketchData {
    private static final String SKETCH_TAG = "minecad_sketch";
    private static final String ID_TAG = "id";
    private static final String DIMENSION_TAG = "dimension";
    private static final String AXIS_TAG = "axis";
    private static final String PLANE_COORDINATE_TAG = "plane_coordinate";
    private static final String ORIGIN_U_TAG = "origin_u";
    private static final String ORIGIN_V_TAG = "origin_v";
    private static final String RENDER_OFFSET_SIGN_TAG = "render_offset_sign";
    private static final String OBJECTS_TAG = "objects";
    private static final String TYPE_TAG = "type";
    private static final String POLYGON_TYPE = "polygon";
    private static final String BOX_TYPE = "box";
    private static final String CIRCLE_TYPE = "circle";
    private static final String VERTICES_TAG = "vertices";
    private static final String FIRST_TAG = "first";
    private static final String SECOND_TAG = "second";
    private static final String CENTER_TAG = "center";
    private static final String RADIUS_TAG = "radius";
    private static final String U_TAG = "u";
    private static final String V_TAG = "v";

    private PlaneSketchData() {
    }

    public static PlaneSketch create(ItemStack stack, String dimension, SketchPlane plane, PlanePoint origin,
            int renderOffsetSign) {
        UUID id = UUID.randomUUID();
        PlaneSketch sketch = new PlaneSketch(id, dimension, plane, origin, renderOffsetSign, List.of());
        write(stack, sketch);
        return sketch;
    }

    public static Optional<PlaneSketch> read(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompoundOrEmpty(SKETCH_TAG);
        if (!tag.contains(ID_TAG) || !tag.contains(AXIS_TAG) || !tag.contains(PLANE_COORDINATE_TAG)) {
            return Optional.empty();
        }

        UUID id;
        try {
            id = UUID.fromString(tag.getStringOr(ID_TAG, ""));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        Axis axis = readAxis(tag.getStringOr(AXIS_TAG, ""));
        SketchPlane plane = new SketchPlane(axis, tag.getIntOr(PLANE_COORDINATE_TAG, 0));
        PlanePoint origin = new PlanePoint(tag.getIntOr(ORIGIN_U_TAG, 0), tag.getIntOr(ORIGIN_V_TAG, 0));
        int renderOffsetSign = tag.getIntOr(RENDER_OFFSET_SIGN_TAG, 1);
        String dimension = tag.getStringOr(DIMENSION_TAG, "");

        return Optional.of(new PlaneSketch(id, dimension, plane, origin, renderOffsetSign, readObjects(tag)));
    }

    public static Optional<PlaneSketch> appendPolygonVertex(ItemStack stack, PlanePoint vertex) {
        Optional<PlaneSketch> maybeSketch = read(stack);
        if (maybeSketch.isEmpty()) {
            return Optional.empty();
        }

        PlaneSketch sketch = maybeSketch.get();
        List<SketchObject> objects = new ArrayList<>(sketch.objects());
        int polygonIndex = firstPolygonIndex(objects);
        SketchObject.Polygon polygon = polygonIndex >= 0
                ? (SketchObject.Polygon) objects.get(polygonIndex)
                : new SketchObject.Polygon(List.of());

        List<PlanePoint> vertices = new ArrayList<>(polygon.vertices());
        vertices.add(vertex);
        SketchObject.Polygon updatedPolygon = new SketchObject.Polygon(vertices);
        if (polygonIndex >= 0) {
            objects.set(polygonIndex, updatedPolygon);
        } else {
            objects.add(updatedPolygon);
        }

        PlaneSketch updated = new PlaneSketch(sketch.id(), sketch.dimension(), sketch.plane(), sketch.origin(),
                sketch.renderOffsetSign(), objects);
        write(stack, updated);
        return Optional.of(updated);
    }

    public static Optional<PlaneSketch> appendBox(ItemStack stack, PlanePoint first, PlanePoint second) {
        return appendObject(stack, new SketchObject.Box(first, second));
    }

    public static Optional<PlaneSketch> appendCircle(ItemStack stack, PlanePoint center, double radius) {
        return appendObject(stack, new SketchObject.Circle(center, radius));
    }

    public static void write(ItemStack stack, PlaneSketch sketch) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(SKETCH_TAG, writeSketch(sketch)));
    }

    private static Optional<PlaneSketch> appendObject(ItemStack stack, SketchObject object) {
        Optional<PlaneSketch> maybeSketch = read(stack);
        if (maybeSketch.isEmpty()) {
            return Optional.empty();
        }

        PlaneSketch sketch = maybeSketch.get();
        List<SketchObject> objects = new ArrayList<>(sketch.objects());
        objects.add(object);
        PlaneSketch updated = new PlaneSketch(sketch.id(), sketch.dimension(), sketch.plane(), sketch.origin(),
                sketch.renderOffsetSign(), objects);
        write(stack, updated);
        return Optional.of(updated);
    }

    private static CompoundTag writeSketch(PlaneSketch sketch) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ID_TAG, sketch.id().toString());
        tag.putString(DIMENSION_TAG, sketch.dimension());
        tag.putString(AXIS_TAG, sketch.plane().axis().getName());
        tag.putInt(PLANE_COORDINATE_TAG, sketch.plane().coordinate());
        tag.putInt(ORIGIN_U_TAG, sketch.origin().u());
        tag.putInt(ORIGIN_V_TAG, sketch.origin().v());
        tag.putInt(RENDER_OFFSET_SIGN_TAG, sketch.renderOffsetSign());
        tag.put(OBJECTS_TAG, writeObjects(sketch.objects()));
        return tag;
    }

    private static List<SketchObject> readObjects(CompoundTag tag) {
        List<SketchObject> objects = new ArrayList<>();
        ListTag objectTags = tag.getListOrEmpty(OBJECTS_TAG);
        for (int i = 0; i < objectTags.size(); i++) {
            CompoundTag objectTag = objectTags.getCompoundOrEmpty(i);
            if (POLYGON_TYPE.equals(objectTag.getStringOr(TYPE_TAG, ""))) {
                objects.add(new SketchObject.Polygon(readVertices(objectTag)));
            } else if (BOX_TYPE.equals(objectTag.getStringOr(TYPE_TAG, ""))) {
                objects.add(new SketchObject.Box(readPoint(objectTag.getCompoundOrEmpty(FIRST_TAG)),
                        readPoint(objectTag.getCompoundOrEmpty(SECOND_TAG))));
            } else if (CIRCLE_TYPE.equals(objectTag.getStringOr(TYPE_TAG, ""))) {
                objects.add(new SketchObject.Circle(readPoint(objectTag.getCompoundOrEmpty(CENTER_TAG)),
                        objectTag.getDoubleOr(RADIUS_TAG, 0.0D)));
            }
        }
        return objects;
    }

    private static ListTag writeObjects(List<SketchObject> objects) {
        ListTag objectTags = new ListTag();
        for (SketchObject object : objects) {
            if (object instanceof SketchObject.Polygon polygon) {
                CompoundTag tag = new CompoundTag();
                tag.putString(TYPE_TAG, POLYGON_TYPE);
                tag.put(VERTICES_TAG, writeVertices(polygon.vertices()));
                objectTags.add(tag);
            } else if (object instanceof SketchObject.Box box) {
                CompoundTag tag = new CompoundTag();
                tag.putString(TYPE_TAG, BOX_TYPE);
                tag.put(FIRST_TAG, writePoint(box.first()));
                tag.put(SECOND_TAG, writePoint(box.second()));
                objectTags.add(tag);
            } else if (object instanceof SketchObject.Circle circle) {
                CompoundTag tag = new CompoundTag();
                tag.putString(TYPE_TAG, CIRCLE_TYPE);
                tag.put(CENTER_TAG, writePoint(circle.center()));
                tag.putDouble(RADIUS_TAG, circle.radius());
                objectTags.add(tag);
            }
        }
        return objectTags;
    }

    private static List<PlanePoint> readVertices(CompoundTag objectTag) {
        List<PlanePoint> vertices = new ArrayList<>();
        ListTag tags = objectTag.getListOrEmpty(VERTICES_TAG);
        for (int i = 0; i < tags.size(); i++) {
            CompoundTag vertex = tags.getCompoundOrEmpty(i);
            vertices.add(new PlanePoint(vertex.getIntOr(U_TAG, 0), vertex.getIntOr(V_TAG, 0)));
        }
        return vertices;
    }

    private static ListTag writeVertices(List<PlanePoint> vertices) {
        ListTag list = new ListTag();
        for (PlanePoint vertex : vertices) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(U_TAG, vertex.u());
            tag.putInt(V_TAG, vertex.v());
            list.add(tag);
        }
        return list;
    }

    private static PlanePoint readPoint(CompoundTag tag) {
        return new PlanePoint(tag.getIntOr(U_TAG, 0), tag.getIntOr(V_TAG, 0));
    }

    private static CompoundTag writePoint(PlanePoint point) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(U_TAG, point.u());
        tag.putInt(V_TAG, point.v());
        return tag;
    }

    private static Axis readAxis(String axisName) {
        for (Axis axis : Axis.values()) {
            if (axis.getName().equals(axisName)) {
                return axis;
            }
        }
        return Axis.Y;
    }

    private static int firstPolygonIndex(List<SketchObject> objects) {
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i) instanceof SketchObject.Polygon) {
                return i;
            }
        }
        return -1;
    }

    public record PlaneSketch(UUID id, String dimension, SketchPlane plane, PlanePoint origin, int renderOffsetSign,
            List<SketchObject> objects) {
        public PlaneSketch {
            objects = List.copyOf(objects);
        }

        public Optional<SketchObject.Polygon> firstPolygon() {
            return objects.stream()
                    .filter(SketchObject.Polygon.class::isInstance)
                    .map(SketchObject.Polygon.class::cast)
                    .findFirst();
        }
    }

    public sealed interface SketchObject permits SketchObject.Polygon, SketchObject.Box, SketchObject.Circle {
        record Polygon(List<PlanePoint> vertices) implements SketchObject {
            public Polygon {
                vertices = List.copyOf(vertices);
            }
        }

        record Box(PlanePoint first, PlanePoint second) implements SketchObject {
        }

        record Circle(PlanePoint center, double radius) implements SketchObject {
        }
    }
}
