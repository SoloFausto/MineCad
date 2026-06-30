package dev.faus.minecad;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.ExtrusionWorldData;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.SketchGeometry;
import dev.faus.minecad.sketch.SketchGeometry.Bounds;
import dev.faus.minecad.sketch.SketchGeometry.FaceRegion;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ExtrudeCommand {
    private static final int EXTRUDE_COORDINATE_OFFSET = 0;
    private static final int MAX_BLOCKS = 8192;

    private ExtrudeCommand() {
    }

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
                Commands.literal("extrude")
                        .then(operation("add", Operation.ADD, buildContext))
                        .then(operation("addition", Operation.ADD, buildContext))
                        .then(operation("subtract", Operation.SUBTRACT, buildContext))
                        .then(operation("subtraction", Operation.SUBTRACT, buildContext))
                        .then(operation("substraction", Operation.SUBTRACT, buildContext))
                        .then(operation("difference", Operation.DIFFERENCE, buildContext))
                        .then(Commands.argument("depth", IntegerArgumentType.integer())
                                .executes(context -> extrudeSelectedDepth(
                                        context.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(context, "depth")))
                                .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                                        .executes(context -> extrudeSelectedDepth(
                                                context.getSource().getPlayerOrException(),
                                                IntegerArgumentType.getInteger(context, "depth"),
                                                BlockStateArgument.getBlock(context, "block").getState()))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> operation(
            String name, Operation operation, net.minecraft.commands.CommandBuildContext buildContext) {
        return Commands.literal(name)
                .then(Commands.argument("depth", IntegerArgumentType.integer())
                        .executes(context -> extrudeSelectedDepth(
                                context.getSource().getPlayerOrException(),
                                IntegerArgumentType.getInteger(context, "depth"),
                                operation))
                        .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                                .executes(context -> extrudeSelectedDepth(
                                        context.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(context, "depth"),
                                        operation,
                                        BlockStateArgument.getBlock(context, "block").getState()))));
    }

    private static int extrudeSelectedDepth(ServerPlayer player, int depth) {
        return extrudeSelectedDepth(player, depth, Operation.ADD, Blocks.STONE.defaultBlockState());
    }

    private static int extrudeSelectedDepth(ServerPlayer player, int depth, BlockState blockState) {
        return extrudeSelectedDepth(player, depth, Operation.ADD, blockState);
    }

    private static int extrudeSelectedDepth(ServerPlayer player, int depth, Operation operation) {
        return extrudeSelectedDepth(player, depth, operation, Blocks.STONE.defaultBlockState());
    }

    private static int extrudeSelectedDepth(ServerPlayer player, int depth, Operation operation,
            BlockState blockState) {
        if (depth == 0) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.zero_depth"));
            return 0;
        }

        Optional<SelectToolItem.Selection> selection = selectedFace(player);
        if (selection.isEmpty()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.no_selection"));
            return 0;
        }

        return completeExtrude(player.level(), player, selection.get(), depth, operation, blockState);
    }

    private static Optional<SelectToolItem.Selection> selectedFace(Player player) {
        Optional<SelectToolItem.Selection> mainHand = SelectToolItem.selectedFace(player.getMainHandItem());
        if (mainHand.isPresent()) {
            return mainHand;
        }
        return SelectToolItem.selectedFace(player.getOffhandItem());
    }

    private static int completeExtrude(Level level, Player player, SelectToolItem.Selection selection,
            int depth, Operation operation, BlockState blockState) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return 0;
        }

        PlaneSketch sketch = activePlane.get().sketch();
        if (!selection.planeId().equals(sketch.id())) {
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player,
                        Component.translatable("message.minecad.extrude_tool.changed_plane"));
            }
            return 0;
        }
        int targetCoordinate = sketch.plane().coordinate() + depth;
        List<BlockPos> changedPositions = new ArrayList<>();
        List<FaceRegion> regions = selectedRegions(sketch, selection);
        for (FaceRegion region : regions) {
            if (changedPositions.size() >= MAX_BLOCKS) {
                break;
            }

            List<BlockPos> changedRegionPositions = apply(level, sketch, region, targetCoordinate, operation,
                    blockState,
                    MAX_BLOCKS - changedPositions.size());
            changedPositions.addAll(changedRegionPositions);
            if (level instanceof ServerLevel serverLevel && !changedRegionPositions.isEmpty()) {
                String blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
                ExtrusionWorldData.get(serverLevel).recordBody(sketch, region.objectIndices(), operation, blockId,
                        depth, changedRegionPositions);
            }
        }
        if (!level.isClientSide()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.extrude_tool.complete",
                    changedPositions.size(), Math.abs(depth)));
        }
        return changedPositions.size();
    }

    private static List<FaceRegion> selectedRegions(PlaneSketch sketch, SelectToolItem.Selection selection) {
        List<FaceRegion> selectedRegions = new ArrayList<>();
        for (FaceRegion region : SketchGeometry.faceRegions(sketch)) {
            if (selection.faces().stream().anyMatch(face -> face.matches(region))) {
                selectedRegions.add(region);
            }
        }
        return selectedRegions;
    }

    private static List<BlockPos> apply(Level level, PlaneSketch sketch, FaceRegion region, int targetCoordinate,
            Operation operation, BlockState blockState, int maxBlocks) {
        if (level.isClientSide()) {
            return List.of();
        }

        Bounds bounds = region.bounds();

        int direction = Integer.compare(targetCoordinate, sketch.plane().coordinate());
        int depth = Math.abs(targetCoordinate - sketch.plane().coordinate());
        List<BlockPos> changed = new ArrayList<>();
        for (int step = 0; step < depth; step++) {
            int coordinate = sketch.plane().coordinate() + direction * step + EXTRUDE_COORDINATE_OFFSET;
            for (int u = bounds.minU(); u <= bounds.maxU(); u++) {
                for (int v = bounds.minV(); v <= bounds.maxV(); v++) {
                    if (!SketchGeometry.contains(region, sketch, u, v)) {
                        continue;
                    }
                    if (changed.size() >= maxBlocks) {
                        return changed;
                    }
                    BlockPos pos = unproject(sketch.plane().axis(), coordinate, new PlanePoint(u, v));
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
