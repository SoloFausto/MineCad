package dev.faus.minecad.sketch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.faus.minecad.ExtrudeToolItem.Operation;
import dev.faus.minecad.MineCad;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlaneSketchData.SketchObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class ExtrusionWorldData extends SavedData {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    private static final Codec<Axis> AXIS_CODEC = Codec.STRING.xmap(ExtrusionWorldData::readAxis, Axis::getName);
    private static final Codec<Operation> OPERATION_CODEC = Codec.STRING.xmap(Operation::fromSerializedName,
            Operation::serializedName);
    private static final Codec<SketchRecord> SKETCH_RECORD_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUID_CODEC.fieldOf("id").forGetter(SketchRecord::id),
                    Codec.STRING.fieldOf("dimension").forGetter(SketchRecord::dimension),
                    AXIS_CODEC.fieldOf("axis").forGetter(SketchRecord::axis),
                    Codec.INT.fieldOf("coordinate").forGetter(SketchRecord::coordinate))
            .apply(instance, SketchRecord::new));
    private static final Codec<PrimitiveRecord> PRIMITIVE_RECORD_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUID_CODEC.fieldOf("sketch_id").forGetter(PrimitiveRecord::sketchId),
                    Codec.INT.fieldOf("object_index").forGetter(PrimitiveRecord::objectIndex),
                    Codec.STRING.fieldOf("type").forGetter(PrimitiveRecord::type))
            .apply(instance, PrimitiveRecord::new));
    private static final Codec<BodyRecord> BODY_RECORD_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUID_CODEC.fieldOf("id").forGetter(BodyRecord::id),
                    UUID_CODEC.fieldOf("sketch_id").forGetter(BodyRecord::sketchId),
                    Codec.INT.fieldOf("object_index").forGetter(BodyRecord::objectIndex),
                    OPERATION_CODEC.fieldOf("operation").forGetter(BodyRecord::operation),
                    Codec.STRING.fieldOf("block").forGetter(BodyRecord::block),
                    Codec.INT.fieldOf("depth").forGetter(BodyRecord::depth),
                    BlockPos.CODEC.listOf().fieldOf("positions").forGetter(BodyRecord::positions))
            .apply(instance, BodyRecord::new));

    public static final Codec<ExtrusionWorldData> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(SKETCH_RECORD_CODEC.listOf().optionalFieldOf("sketches", List.of())
                    .forGetter(ExtrusionWorldData::sketches),
                    PRIMITIVE_RECORD_CODEC.listOf().optionalFieldOf("primitives", List.of())
                            .forGetter(ExtrusionWorldData::primitives),
                    BODY_RECORD_CODEC.listOf().optionalFieldOf("bodies", List.of()).forGetter(ExtrusionWorldData::bodies))
            .apply(instance, ExtrusionWorldData::new));
    public static final SavedDataType<ExtrusionWorldData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(MineCad.MOD_ID, "extrusions"),
            ExtrusionWorldData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final List<SketchRecord> sketches;
    private final List<PrimitiveRecord> primitives;
    private final List<BodyRecord> bodies;

    public ExtrusionWorldData() {
        this(List.of(), List.of(), List.of());
    }

    private ExtrusionWorldData(List<SketchRecord> sketches, List<PrimitiveRecord> primitives,
            List<BodyRecord> bodies) {
        this.sketches = new ArrayList<>(sketches);
        this.primitives = new ArrayList<>(primitives);
        this.bodies = new ArrayList<>(bodies);
    }

    public static ExtrusionWorldData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public UUID recordBody(PlaneSketch sketch, int objectIndex, SketchObject object, Operation operation, String block, int depth,
            List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return null;
        }

        ensureSketch(sketch);
        ensurePrimitive(sketch.id(), objectIndex, primitiveType(object));
        UUID bodyId = UUID.randomUUID();
        bodies.add(new BodyRecord(bodyId, sketch.id(), objectIndex, operation, block, depth, List.copyOf(positions)));
        setDirty();
        return bodyId;
    }

    public List<SketchRecord> sketches() {
        return List.copyOf(sketches);
    }

    public List<PrimitiveRecord> primitives() {
        return List.copyOf(primitives);
    }

    public List<BodyRecord> bodies() {
        return List.copyOf(bodies);
    }

    public Optional<BodyRecord> findBodyContaining(String dimension, BlockPos pos) {
        for (int i = bodies.size() - 1; i >= 0; i--) {
            BodyRecord body = bodies.get(i);
            if (dimension.equals(sketchDimension(body.sketchId())) && body.positions().contains(pos)) {
                return Optional.of(body);
            }
        }
        return Optional.empty();
    }

    private void ensureSketch(PlaneSketch sketch) {
        if (sketches.stream().noneMatch(record -> record.id().equals(sketch.id()))) {
            sketches.add(new SketchRecord(sketch.id(), sketch.dimension(), sketch.plane().axis(),
                    sketch.plane().coordinate()));
        }
    }

    private void ensurePrimitive(UUID sketchId, int objectIndex, String type) {
        if (primitives.stream().noneMatch(primitive -> primitive.sketchId().equals(sketchId)
                && primitive.objectIndex() == objectIndex)) {
            primitives.add(new PrimitiveRecord(sketchId, objectIndex, type));
        }
    }

    private String sketchDimension(UUID sketchId) {
        return sketches.stream()
                .filter(sketch -> sketch.id().equals(sketchId))
                .map(SketchRecord::dimension)
                .findFirst()
                .orElse("");
    }

    private static String primitiveType(SketchObject object) {
        if (object instanceof SketchObject.Polygon) {
            return "polygon";
        }
        if (object instanceof SketchObject.Box) {
            return "box";
        }
        if (object instanceof SketchObject.Circle) {
            return "circle";
        }
        return "unknown";
    }

    private static Axis readAxis(String axisName) {
        for (Axis axis : Axis.values()) {
            if (axis.getName().equals(axisName)) {
                return axis;
            }
        }
        return Axis.Y;
    }

    public record SketchRecord(UUID id, String dimension, Axis axis, int coordinate) {
    }

    public record PrimitiveRecord(UUID sketchId, int objectIndex, String type) {
    }

    public record BodyRecord(UUID id, UUID sketchId, int objectIndex, Operation operation, String block, int depth,
            List<BlockPos> positions) {
        public BodyRecord {
            positions = List.copyOf(positions);
        }
    }
}
