package dev.faus.minecad.sketch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.faus.minecad.ExtrudeCommand.Operation;
import dev.faus.minecad.MineCad;
import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlaneSketchData.SketchObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class ExtrusionWorldData extends SavedData {
    private static final String BLOCK_KIND = "block";
    private static final String PRIMITIVE_KIND = "primitive";
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    private static final Codec<Axis> AXIS_CODEC = Codec.STRING.xmap(ExtrusionWorldData::readAxis, Axis::getName);
    private static final Codec<Operation> OPERATION_CODEC = Codec.STRING.xmap(Operation::fromSerializedName,
            Operation::serializedName);
    private static final Codec<PlanePoint> PLANE_POINT_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.INT.fieldOf("u").forGetter(PlanePoint::u),
                    Codec.INT.fieldOf("v").forGetter(PlanePoint::v))
            .apply(instance, PlanePoint::new));
    private static final Codec<SketchObjectSnapshot> SKETCH_OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.STRING.fieldOf("type").forGetter(SketchObjectSnapshot::type),
                    PLANE_POINT_CODEC.listOf().optionalFieldOf("vertices", List.of())
                            .forGetter(SketchObjectSnapshot::vertices),
                    Codec.BOOL.optionalFieldOf("closed", false).forGetter(SketchObjectSnapshot::closed),
                    PLANE_POINT_CODEC.optionalFieldOf("first", new PlanePoint(0, 0))
                            .forGetter(SketchObjectSnapshot::first),
                    PLANE_POINT_CODEC.optionalFieldOf("second", new PlanePoint(0, 0))
                            .forGetter(SketchObjectSnapshot::second),
                    PLANE_POINT_CODEC.optionalFieldOf("center", new PlanePoint(0, 0))
                            .forGetter(SketchObjectSnapshot::center),
                    Codec.DOUBLE.optionalFieldOf("radius", 0.0D).forGetter(SketchObjectSnapshot::radius))
            .apply(instance, SketchObjectSnapshot::new));
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
    public static final Codec<BlockChange> BLOCK_CHANGE_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(BlockPos.CODEC.fieldOf("pos").forGetter(BlockChange::pos),
                    Codec.STRING.fieldOf("before").forGetter(BlockChange::before),
                    Codec.STRING.fieldOf("after").forGetter(BlockChange::after))
            .apply(instance, BlockChange::new));
    private static final Codec<OperationRecord> OPERATION_RECORD_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUID_CODEC.fieldOf("id").forGetter(OperationRecord::id),
                    Codec.STRING.optionalFieldOf("kind", BLOCK_KIND).forGetter(OperationRecord::kind),
                    UUID_CODEC.fieldOf("sketch_id").forGetter(OperationRecord::sketchId),
                    Codec.INT.listOf().fieldOf("object_indices").forGetter(OperationRecord::objectIndices),
                    OPERATION_CODEC.optionalFieldOf("operation", Operation.ADD).forGetter(OperationRecord::operation),
                    Codec.STRING.optionalFieldOf("block", "").forGetter(OperationRecord::block),
                    Codec.INT.optionalFieldOf("depth", 0).forGetter(OperationRecord::depth),
                    BLOCK_CHANGE_CODEC.listOf().optionalFieldOf("changes", List.of())
                            .forGetter(OperationRecord::changes),
                    SKETCH_OBJECT_CODEC.listOf().optionalFieldOf("before_objects", List.of())
                            .forGetter(OperationRecord::beforeObjects),
                    SKETCH_OBJECT_CODEC.listOf().optionalFieldOf("after_objects", List.of())
                            .forGetter(OperationRecord::afterObjects))
            .apply(instance, OperationRecord::new));

    public static final Codec<ExtrusionWorldData> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(SKETCH_RECORD_CODEC.listOf().optionalFieldOf("sketches", List.of())
                    .forGetter(ExtrusionWorldData::sketches),
                    PRIMITIVE_RECORD_CODEC.listOf().optionalFieldOf("primitives", List.of())
                            .forGetter(ExtrusionWorldData::primitives),
                    OPERATION_RECORD_CODEC.listOf().optionalFieldOf("operations", List.of())
                            .forGetter(ExtrusionWorldData::operations),
                    Codec.INT.optionalFieldOf("applied_operation_count", 0)
                            .forGetter(ExtrusionWorldData::appliedOperationCount))
            .apply(instance, ExtrusionWorldData::new));
    public static final SavedDataType<ExtrusionWorldData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(MineCad.MOD_ID, "extrusions"),
            ExtrusionWorldData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final List<SketchRecord> sketches;
    private final List<PrimitiveRecord> primitives;
    private final List<OperationRecord> operations;
    private int appliedOperationCount;

    public ExtrusionWorldData() {
        this(List.of(), List.of(), List.of(), 0);
    }

    private ExtrusionWorldData(List<SketchRecord> sketches, List<PrimitiveRecord> primitives,
            List<OperationRecord> operations, int appliedOperationCount) {
        this.sketches = new ArrayList<>(sketches);
        this.primitives = new ArrayList<>(primitives);
        this.operations = new ArrayList<>(operations);
        this.appliedOperationCount = Math.clamp(appliedOperationCount, 0, operations.size());
    }

    public static ExtrusionWorldData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public UUID recordOperation(PlaneSketch sketch, List<Integer> objectIndices, Operation operation, String block,
            int depth, List<BlockChange> changes) {
        if (changes.isEmpty()) {
            return null;
        }

        ensureSketch(sketch);
        ensurePrimitives(sketch, objectIndices);
        truncateFuture();

        UUID operationId = UUID.randomUUID();
        operations.add(OperationRecord.block(operationId, sketch.id(), objectIndices, operation, block, depth, changes));
        appliedOperationCount = operations.size();
        setDirty();
        return operationId;
    }

    public UUID recordPrimitiveChange(PlaneSketch before, PlaneSketch after) {
        ensureSketch(after);
        List<Integer> objectIndices = changedObjectIndices(before.objects(), after.objects());
        ensurePrimitives(after, objectIndices);
        truncateFuture();

        UUID operationId = UUID.randomUUID();
        operations.add(OperationRecord.primitive(operationId, after.id(), objectIndices, before.objects(),
                after.objects()));
        appliedOperationCount = operations.size();
        setDirty();
        return operationId;
    }

    public HistoryStep stepBack(ServerLevel level, Optional<PlaneSketchStack> activePlane) {
        if (appliedOperationCount <= 0) {
            return new HistoryStep(false, "message.minecad.history_tool.at_start", appliedOperationCount,
                    operations.size());
        }

        OperationRecord operation = operations.get(appliedOperationCount - 1);
        if (!applyBack(level, activePlane, operation)) {
            return new HistoryStep(false, "message.minecad.history_tool.no_plane", appliedOperationCount,
                    operations.size());
        }

        appliedOperationCount--;
        setDirty();
        return new HistoryStep(true, "message.minecad.history_tool.back", appliedOperationCount, operations.size());
    }

    public HistoryStep stepForward(ServerLevel level, Optional<PlaneSketchStack> activePlane) {
        if (appliedOperationCount >= operations.size()) {
            return new HistoryStep(false, "message.minecad.history_tool.at_end", appliedOperationCount,
                    operations.size());
        }

        OperationRecord operation = operations.get(appliedOperationCount);
        if (!applyForward(level, activePlane, operation)) {
            return new HistoryStep(false, "message.minecad.history_tool.no_plane", appliedOperationCount,
                    operations.size());
        }

        appliedOperationCount++;
        setDirty();
        return new HistoryStep(true, "message.minecad.history_tool.forward", appliedOperationCount,
                operations.size());
    }

    public List<SketchRecord> sketches() {
        return List.copyOf(sketches);
    }

    public List<PrimitiveRecord> primitives() {
        return List.copyOf(primitives);
    }

    public List<OperationRecord> operations() {
        return List.copyOf(operations);
    }

    public List<OperationRecord> activeOperations() {
        return List.copyOf(operations.subList(0, appliedOperationCount));
    }

    public int appliedOperationCount() {
        return appliedOperationCount;
    }

    public Optional<OperationRecord> findOperationContaining(String dimension, BlockPos pos) {
        for (int i = appliedOperationCount - 1; i >= 0; i--) {
            OperationRecord operation = operations.get(i);
            if (operation.isBlockOperation() && dimension.equals(sketchDimension(operation.sketchId()))
                    && operation.changes().stream().anyMatch(change -> change.pos().equals(pos))) {
                return Optional.of(operation);
            }
        }
        return Optional.empty();
    }

    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private boolean applyBack(ServerLevel level, Optional<PlaneSketchStack> activePlane, OperationRecord operation) {
        if (operation.isPrimitiveOperation()) {
            return applyPrimitive(activePlane, operation, operation.beforeObjects());
        }

        for (int i = operation.changes().size() - 1; i >= 0; i--) {
            BlockChange change = operation.changes().get(i);
            level.setBlock(change.pos(), blockState(change.before()), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        return true;
    }

    private boolean applyForward(ServerLevel level, Optional<PlaneSketchStack> activePlane, OperationRecord operation) {
        if (operation.isPrimitiveOperation()) {
            return applyPrimitive(activePlane, operation, operation.afterObjects());
        }

        for (BlockChange change : operation.changes()) {
            level.setBlock(change.pos(), blockState(change.after()), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        return true;
    }

    private boolean applyPrimitive(Optional<PlaneSketchStack> activePlane, OperationRecord operation,
            List<SketchObjectSnapshot> objects) {
        if (activePlane.isEmpty() || !activePlane.get().sketch().id().equals(operation.sketchId())) {
            return false;
        }

        PlaneSketch current = activePlane.get().sketch();
        PlaneSketch updated = new PlaneSketch(current.id(), current.dimension(), current.plane(), current.origin(),
                current.renderOffsetSign(), objects.stream().map(SketchObjectSnapshot::toObject).toList());
        PlaneSketchData.write(activePlane.get().stack(), updated);
        return true;
    }

    private void truncateFuture() {
        if (appliedOperationCount < operations.size()) {
            operations.subList(appliedOperationCount, operations.size()).clear();
        }
    }

    private void ensureSketch(PlaneSketch sketch) {
        if (sketches.stream().noneMatch(record -> record.id().equals(sketch.id()))) {
            sketches.add(new SketchRecord(sketch.id(), sketch.dimension(), sketch.plane().axis(),
                    sketch.plane().coordinate()));
        }
    }

    private void ensurePrimitives(PlaneSketch sketch, List<Integer> objectIndices) {
        for (Integer objectIndex : objectIndices) {
            if (objectIndex >= 0 && objectIndex < sketch.objects().size()) {
                ensurePrimitive(sketch.id(), objectIndex, primitiveType(sketch.objects().get(objectIndex)));
            }
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

    private static List<Integer> changedObjectIndices(List<SketchObject> before, List<SketchObject> after) {
        int max = Math.max(before.size(), after.size());
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            SketchObject beforeObject = i < before.size() ? before.get(i) : null;
            SketchObject afterObject = i < after.size() ? after.get(i) : null;
            if (!java.util.Objects.equals(beforeObject, afterObject)) {
                indices.add(i);
            }
        }
        return indices;
    }

    private static BlockState blockState(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return BuiltInRegistries.BLOCK.getOptional(id)
                .map(block -> block.defaultBlockState())
                .orElseGet(() -> Blocks.AIR.defaultBlockState());
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

    public record BlockChange(BlockPos pos, String before, String after) {
    }

    public record SketchObjectSnapshot(String type, List<PlanePoint> vertices, boolean closed, PlanePoint first,
            PlanePoint second, PlanePoint center, double radius) {
        public SketchObjectSnapshot {
            vertices = List.copyOf(vertices);
        }

        public static SketchObjectSnapshot fromObject(SketchObject object) {
            if (object instanceof SketchObject.Polygon polygon) {
                return new SketchObjectSnapshot("polygon", polygon.vertices(), polygon.closed(), new PlanePoint(0, 0),
                        new PlanePoint(0, 0), new PlanePoint(0, 0), 0.0D);
            }
            if (object instanceof SketchObject.Box box) {
                return new SketchObjectSnapshot("box", List.of(), false, box.first(), box.second(),
                        new PlanePoint(0, 0), 0.0D);
            }
            if (object instanceof SketchObject.Circle circle) {
                return new SketchObjectSnapshot("circle", List.of(), false, new PlanePoint(0, 0),
                        new PlanePoint(0, 0), circle.center(), circle.radius());
            }
            return new SketchObjectSnapshot("unknown", List.of(), false, new PlanePoint(0, 0), new PlanePoint(0, 0),
                    new PlanePoint(0, 0), 0.0D);
        }

        public SketchObject toObject() {
            return switch (type) {
                case "polygon" -> new SketchObject.Polygon(vertices, closed);
                case "box" -> new SketchObject.Box(first, second);
                case "circle" -> new SketchObject.Circle(center, radius);
                default -> new SketchObject.Polygon(List.of(), false);
            };
        }
    }

    public record OperationRecord(UUID id, String kind, UUID sketchId, List<Integer> objectIndices, Operation operation,
            String block, int depth, List<BlockChange> changes, List<SketchObjectSnapshot> beforeObjects,
            List<SketchObjectSnapshot> afterObjects) {
        public OperationRecord {
            objectIndices = List.copyOf(objectIndices);
            changes = List.copyOf(changes);
            beforeObjects = List.copyOf(beforeObjects);
            afterObjects = List.copyOf(afterObjects);
        }

        public static OperationRecord block(UUID id, UUID sketchId, List<Integer> objectIndices, Operation operation,
                String block, int depth, List<BlockChange> changes) {
            return new OperationRecord(id, BLOCK_KIND, sketchId, objectIndices, operation, block, depth, changes,
                    List.of(), List.of());
        }

        public static OperationRecord primitive(UUID id, UUID sketchId, List<Integer> objectIndices,
                List<SketchObject> beforeObjects, List<SketchObject> afterObjects) {
            return new OperationRecord(id, PRIMITIVE_KIND, sketchId, objectIndices, Operation.ADD, "", 0, List.of(),
                    beforeObjects.stream().map(SketchObjectSnapshot::fromObject).toList(),
                    afterObjects.stream().map(SketchObjectSnapshot::fromObject).toList());
        }

        public boolean isBlockOperation() {
            return BLOCK_KIND.equals(kind);
        }

        public boolean isPrimitiveOperation() {
            return PRIMITIVE_KIND.equals(kind);
        }

        public int blockCount() {
            return changes.size();
        }
    }

    public record HistoryStep(boolean changed, String messageKey, int index, int total) {
    }
}
