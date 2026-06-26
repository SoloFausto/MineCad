package dev.faus.minecad;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.ExtrusionWorldData;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlaneSketchData.SketchObject;
import dev.faus.minecad.sketch.SketchGeometry;
import dev.faus.minecad.sketch.SketchGeometry.Bounds;
import dev.faus.minecad.sketch.SketchPlane;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class ExtrudeToolItem extends Item {
    private static final String TOOL_TAG = "minecad_extrude_tool";
    private static final String PLANE_ID_TAG = "plane_id";
    private static final String OBJECT_INDEX_TAG = "object_index";
    private static final String DEPTH_TAG = "depth";
    private static final int EXTRUDE_COORDINATE_OFFSET = -1;
    private static final int MAX_BLOCKS = 8192;

    public ExtrudeToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        ItemStack toolStack = context.getItemInHand();
        Optional<PendingExtrude> pending = readPending(toolStack);
        if (pending.isPresent()) {
            return storeDepth(context.getLevel(), player, toolStack, pending.get(), context.getClickedPos(),
                    context.getClickedFace());
        }

        Optional<PlanePoint> point = SketchToolSupport.pointFromUseOn(context);
        return point.map(planePoint -> selectObject(context.getLevel(), player, toolStack, planePoint))
                .orElse(InteractionResult.FAIL);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        Optional<PlanePoint> point = SketchToolSupport.pointFromRaycast(level, player);
        return point.map(planePoint -> selectObject(level, player, player.getItemInHand(hand), planePoint))
                .orElse(InteractionResult.FAIL);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.minecad.extrude_tool"));
    }

    public static Optional<Integer> selectedObjectIndex(ItemStack stack, UUID planeId) {
        Optional<PendingExtrude> pending = readPending(stack);
        if (pending.isEmpty() || !pending.get().planeId().equals(planeId)) {
            return Optional.empty();
        }
        return Optional.of(pending.get().objectIndex());
    }

    public static int extrudeSelectedDepth(ServerPlayer player, int depth) {
        return extrudeSelectedDepth(player, depth, Operation.ADD, Blocks.STONE.defaultBlockState());
    }

    public static int extrudeSelectedDepth(ServerPlayer player, int depth, BlockState blockState) {
        return extrudeSelectedDepth(player, depth, Operation.ADD, blockState);
    }

    public static int extrudeSelectedDepth(ServerPlayer player, int depth, Operation operation) {
        return extrudeSelectedDepth(player, depth, operation, Blocks.STONE.defaultBlockState());
    }

    public static int extrudeSelectedDepth(ServerPlayer player, int depth, Operation operation, BlockState blockState) {
        if (depth == 0) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.zero_depth"));
            return 0;
        }

        ItemStack toolStack = heldExtrudeTool(player).orElse(null);
        if (toolStack == null) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.no_tool"));
            return 0;
        }

        Optional<PendingExtrude> pending = readPending(toolStack);
        if (pending.isEmpty()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.no_selection"));
            return 0;
        }

        return completeExtrude(player.level(), player, toolStack, pending.get(), depth, operation, blockState);
    }

    public static int extrudeSelected(ServerPlayer player, Operation operation, BlockState blockState) {
        ItemStack toolStack = heldExtrudeTool(player).orElse(null);
        if (toolStack == null) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.no_tool"));
            return 0;
        }

        Optional<PendingExtrude> pending = readPending(toolStack);
        if (pending.isEmpty()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.no_selection"));
            return 0;
        }
        if (pending.get().depth().isEmpty()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.no_depth"));
            return 0;
        }

        return completeExtrude(player.level(), player, toolStack, pending.get(), pending.get().depth().get(),
                operation, blockState);
    }

    public static boolean hasPendingSelection(Player player) {
        Optional<ItemStack> toolStack = heldExtrudeTool(player);
        return toolStack.isPresent() && readPending(toolStack.get()).isPresent();
    }

    private static InteractionResult selectObject(Level level, Player player, ItemStack toolStack, PlanePoint point) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return InteractionResult.FAIL;
        }

        PlaneSketch sketch = activePlane.get().sketch();
        int objectIndex = findObject(sketch, point);
        if (objectIndex < 0) {
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.no_object"));
            }
            return InteractionResult.FAIL;
        }

        writePending(toolStack, sketch.id(), objectIndex);
        if (!level.isClientSide()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.selected"));
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    private static InteractionResult storeDepth(Level level, Player player, ItemStack toolStack,
            PendingExtrude pending, BlockPos clickedPos, Direction clickedFace) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return InteractionResult.FAIL;
        }

        PlaneSketch sketch = activePlane.get().sketch();
        if (!pending.planeId().equals(sketch.id())) {
            clearPending(toolStack);
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.changed_plane"));
            }
            return InteractionResult.FAIL;
        }
        if (clickedFace.getAxis() != sketch.plane().axis()) {
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.axis_mismatch",
                        sketch.plane().axis().getName().toUpperCase()));
            }
            return InteractionResult.FAIL;
        }
        if (pending.objectIndex() < 0 || pending.objectIndex() >= sketch.objects().size()) {
            clearPending(toolStack);
            return InteractionResult.FAIL;
        }

        int depth = SketchPlane.fromClickedFace(clickedPos, clickedFace).coordinate() - sketch.plane().coordinate();
        if (depth == 0) {
            return InteractionResult.FAIL;
        }

        writePending(toolStack, pending.planeId(), pending.objectIndex(), depth);
        if (!level.isClientSide()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.depth_selected",
                    Math.abs(depth)));
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    private static int completeExtrude(Level level, Player player, ItemStack toolStack, PendingExtrude pending,
            int depth, boolean subtract, BlockState blockState) {
        return completeExtrude(level, player, toolStack, pending, depth,
                subtract ? Operation.SUBTRACT : Operation.ADD, blockState);
    }

    private static int completeExtrude(Level level, Player player, ItemStack toolStack, PendingExtrude pending,
            int depth, Operation operation, BlockState blockState) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return 0;
        }

        PlaneSketch sketch = activePlane.get().sketch();
        if (!pending.planeId().equals(sketch.id())) {
            clearPending(toolStack);
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.changed_plane"));
            }
            return 0;
        }
        if (pending.objectIndex() < 0 || pending.objectIndex() >= sketch.objects().size()) {
            clearPending(toolStack);
            return 0;
        }

        SketchObject object = sketch.objects().get(pending.objectIndex());
        int targetCoordinate = sketch.plane().coordinate() + depth;
        List<BlockPos> changedPositions = apply(level, sketch.plane(), object, targetCoordinate, operation, blockState);
        clearPending(toolStack);
        if (level instanceof ServerLevel serverLevel && !changedPositions.isEmpty()) {
            String blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
            ExtrusionWorldData.get(serverLevel).recordBody(sketch, pending.objectIndex(), object, operation, blockId,
                    depth, changedPositions);
        }
        if (!level.isClientSide()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.complete",
                    changedPositions.size(), Math.abs(depth)));
        }
        return changedPositions.size();
    }

    private static int findObject(PlaneSketch sketch, PlanePoint point) {
        for (int i = sketch.objects().size() - 1; i >= 0; i--) {
            SketchObject object = sketch.objects().get(i);
            if (SketchGeometry.contains(object, point.u() + 0.5D, point.v() + 0.5D)) {
                return i;
            }
        }
        return -1;
    }

    private static List<BlockPos> apply(Level level, SketchPlane plane, SketchObject object, int targetCoordinate,
            Operation operation,
            BlockState blockState) {
        if (level.isClientSide()) {
            return List.of();
        }

        Bounds bounds = SketchGeometry.bounds(object);
        if (bounds == null) {
            return List.of();
        }

        int direction = Integer.compare(targetCoordinate, plane.coordinate());
        int depth = Math.abs(targetCoordinate - plane.coordinate());
        List<BlockPos> changed = new ArrayList<>();
        for (int step = 0; step < depth; step++) {
            int coordinate = plane.coordinate() + direction * step + EXTRUDE_COORDINATE_OFFSET;
            for (int u = bounds.minU(); u <= bounds.maxU(); u++) {
                for (int v = bounds.minV(); v <= bounds.maxV(); v++) {
                    if (!SketchGeometry.contains(object, u + 0.5D, v + 0.5D)) {
                        continue;
                    }
                    if (changed.size() >= MAX_BLOCKS) {
                        return changed;
                    }
                    BlockPos pos = unproject(plane.axis(), coordinate, new PlanePoint(u, v));
                    boolean blockChanged = applyOperation(level, pos, operation, blockState);
                    if (blockChanged) {
                        changed.add(pos);
                    }
                }
            }
        }
        return changed;
    }

    private static boolean applyOperation(Level level, BlockPos pos, Operation operation, BlockState blockState) {
        return switch (operation) {
            case ADD -> placeBlock(level, pos, blockState);
            case SUBTRACT -> removeBlock(level, pos);
            case DIFFERENCE -> level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced()
                    ? placeBlock(level, pos, blockState)
                    : removeBlock(level, pos);
        };
    }

    private static boolean placeBlock(Level level, BlockPos pos, BlockState blockState) {
        if (level.getBlockState(pos).canBeReplaced()) {
            return level.setBlock(pos, blockState, Block.UPDATE_ALL);
        }
        return false;
    }

    private static boolean removeBlock(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        return false;
    }

    private static BlockPos unproject(Axis axis, int coordinate, PlanePoint point) {
        return switch (axis) {
            case X -> new BlockPos(coordinate, point.u(), point.v());
            case Y -> new BlockPos(point.u(), coordinate, point.v());
            case Z -> new BlockPos(point.u(), point.v(), coordinate);
        };
    }

    private static Optional<ItemStack> heldExtrudeTool(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof ExtrudeToolItem) {
            return Optional.of(mainHand);
        }

        ItemStack offHand = player.getOffhandItem();
        if (offHand.getItem() instanceof ExtrudeToolItem) {
            return Optional.of(offHand);
        }
        return Optional.empty();
    }

    private static Optional<PendingExtrude> readPending(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompoundOrEmpty(TOOL_TAG);
        if (!tag.contains(PLANE_ID_TAG)) {
            return Optional.empty();
        }

        try {
            UUID planeId = UUID.fromString(tag.getStringOr(PLANE_ID_TAG, ""));
            Optional<Integer> depth = tag.contains(DEPTH_TAG)
                    ? Optional.of(tag.getIntOr(DEPTH_TAG, 0))
                    : Optional.empty();
            return Optional.of(new PendingExtrude(planeId, tag.getIntOr(OBJECT_INDEX_TAG, -1), depth));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static void writePending(ItemStack stack, UUID planeId, int objectIndex) {
        writePending(stack, planeId, objectIndex, null);
    }

    private static void writePending(ItemStack stack, UUID planeId, int objectIndex, Integer depth) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag tool = new CompoundTag();
            tool.putString(PLANE_ID_TAG, planeId.toString());
            tool.putInt(OBJECT_INDEX_TAG, objectIndex);
            if (depth != null) {
                tool.putInt(DEPTH_TAG, depth);
            }
            tag.put(TOOL_TAG, tool);
        });
    }

    private static void clearPending(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(TOOL_TAG));
    }

    private record PendingExtrude(UUID planeId, int objectIndex, Optional<Integer> depth) {
    }

    public enum Operation {
        ADD("add"),
        SUBTRACT("subtract"),
        DIFFERENCE("difference");

        private final String serializedName;

        Operation(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static Operation fromSerializedName(String name) {
            for (Operation operation : values()) {
                if (operation.serializedName.equals(name)) {
                    return operation;
                }
            }
            return ADD;
        }
    }
}
