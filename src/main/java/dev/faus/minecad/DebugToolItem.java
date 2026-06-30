package dev.faus.minecad;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.ExtrusionWorldData;
import dev.faus.minecad.sketch.ExtrusionWorldData.OperationRecord;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlaneSketchData.SketchObject;
import dev.faus.minecad.sketch.SketchGeometry;
import dev.faus.minecad.sketch.SketchGeometry.Bounds;
import dev.faus.minecad.sketch.SketchGeometry.FaceRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

public class DebugToolItem extends Item {
    private static final String TOOL_TAG = "minecad_debug_tool";
    private static final String POSITIONS_TAG = "positions";
    private static final String X_TAG = "x";
    private static final String Y_TAG = "y";
    private static final String Z_TAG = "z";

    public DebugToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        return selectBody(context.getLevel(), player, context.getItemInHand(), context.getClickedPos());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        sendSketchTable(level, player);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.minecad.debug_tool"));
    }

    public static List<BlockPos> selectedPositions(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompoundOrEmpty(TOOL_TAG);
        List<BlockPos> positions = new ArrayList<>();
        ListTag positionTags = tag.getListOrEmpty(POSITIONS_TAG);
        for (int i = 0; i < positionTags.size(); i++) {
            CompoundTag positionTag = positionTags.getCompoundOrEmpty(i);
            positions.add(new BlockPos(positionTag.getIntOr(X_TAG, 0), positionTag.getIntOr(Y_TAG, 0),
                    positionTag.getIntOr(Z_TAG, 0)));
        }
        return List.copyOf(positions);
    }

    private static InteractionResult selectBody(Level level, Player player, ItemStack toolStack, BlockPos pos) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.FAIL;
        }

        Optional<OperationRecord> operation = ExtrusionWorldData.get(serverLevel).findOperationContaining(
                level.dimension().identifier().toString(), pos);
        if (operation.isEmpty()) {
            clearSelection(toolStack);
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.debug_tool.no_body"));
            return InteractionResult.FAIL;
        }

        List<BlockPos> positions = operation.get().changes().stream()
                .map(ExtrusionWorldData.BlockChange::pos)
                .toList();
        if (positions.isEmpty()) {
            clearSelection(toolStack);
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.debug_tool.no_body"));
            return InteractionResult.FAIL;
        }

        writeSelection(toolStack, positions);
        SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.debug_tool.body_selected",
                positions.size()));
        return InteractionResult.SUCCESS_SERVER;
    }

    private static void sendSketchTable(Level level, Player player) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return;
        }

        PlaneSketch sketch = activePlane.get().sketch();
        SketchToolSupport.sendMessage(player, Component.literal("MineCad sketch geometry"));
        SketchToolSupport.sendMessage(player, Component.literal("plane | axis=%s coordinate=%s objects=%s"
                .formatted(sketch.plane().axis().getName(), sketch.plane().coordinate(), sketch.objects().size())));
        SketchToolSupport.sendMessage(player, Component.literal("# | type | state | geometry"));
        for (int i = 0; i < sketch.objects().size(); i++) {
            SketchToolSupport.sendMessage(player, Component.literal(row(i, sketch.objects().get(i))));
        }

        List<FaceRegion> faces = SketchGeometry.faceRegions(sketch);
        SketchToolSupport.sendMessage(player, Component.literal("faces"));
        SketchToolSupport.sendMessage(player, Component.literal("# | objects | bounds | cells | seed"));
        for (int i = 0; i < faces.size(); i++) {
            FaceRegion face = faces.get(i);
            SketchToolSupport.sendMessage(player, Component.literal("%s | %s | %s | %s | %s"
                    .formatted(i, face.objectIndices(), formatBounds(face.bounds()), face.cells().size(), face.seed())));
        }

        if (level instanceof ServerLevel serverLevel) {
            List<OperationRecord> operations = ExtrusionWorldData.get(serverLevel).activeOperations().stream()
                    .filter(operation -> operation.sketchId().equals(sketch.id()))
                    .toList();
            SketchToolSupport.sendMessage(player, Component.literal("operations"));
            SketchToolSupport.sendMessage(player, Component.literal("# | objects | op | block | depth | blocks"));
            for (int i = 0; i < operations.size(); i++) {
                OperationRecord operation = operations.get(i);
                SketchToolSupport.sendMessage(player, Component.literal("%s | %s | %s | %s | %s | %s"
                        .formatted(i, operation.objectIndices(), operation.operation().serializedName(), operation.block(),
                                operation.depth(), operation.blockCount())));
            }
        }
    }

    private static String row(int index, SketchObject object) {
        Bounds bounds = SketchGeometry.bounds(object);
        if (object instanceof SketchObject.Polygon polygon) {
            return "%s | polygon | %s | vertices=%s bounds=%s"
                    .formatted(index, polygon.closed() ? "closed" : "open", polygon.vertices(), formatBounds(bounds));
        }
        if (object instanceof SketchObject.Box box) {
            return "%s | box | closed | first=%s second=%s bounds=%s"
                    .formatted(index, box.first(), box.second(), formatBounds(bounds));
        }
        if (object instanceof SketchObject.Circle circle) {
            return "%s | circle | closed | center=%s radius=%s bounds=%s"
                    .formatted(index, circle.center(), circle.radius(), formatBounds(bounds));
        }
        return "%s | unknown | - | -".formatted(index);
    }

    private static String formatBounds(Bounds bounds) {
        if (bounds == null) {
            return "-";
        }
        return "[%s..%s, %s..%s]".formatted(bounds.minU(), bounds.maxU(), bounds.minV(), bounds.maxV());
    }

    private static void writeSelection(ItemStack stack, List<BlockPos> positions) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag tool = new CompoundTag();
            tool.put(POSITIONS_TAG, writePositions(positions));
            tag.put(TOOL_TAG, tool);
        });
    }

    private static ListTag writePositions(List<BlockPos> positions) {
        ListTag tags = new ListTag();
        for (BlockPos pos : positions) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(X_TAG, pos.getX());
            tag.putInt(Y_TAG, pos.getY());
            tag.putInt(Z_TAG, pos.getZ());
            tags.add(tag);
        }
        return tags;
    }

    private static void clearSelection(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(TOOL_TAG));
    }
}
