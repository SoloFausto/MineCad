package dev.faus.minecad;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import dev.faus.minecad.sketch.ExtrusionWorldData;
import dev.faus.minecad.sketch.ExtrusionWorldData.BodyRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class SelectToolItem extends Item {
    private static final String TOOL_TAG = "minecad_select_tool";
    private static final String BODY_ID_TAG = "body_id";
    private static final String POSITIONS_TAG = "positions";
    private static final String X_TAG = "x";
    private static final String Y_TAG = "y";
    private static final String Z_TAG = "z";

    public SelectToolItem(Properties properties) {
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
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.minecad.select_tool"));
    }

    public static List<BlockPos> selectedPositions(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompoundOrEmpty(TOOL_TAG);
        if (!tag.contains(BODY_ID_TAG)) {
            return List.of();
        }

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

        Optional<BodyRecord> body = ExtrusionWorldData.get(serverLevel).findBodyContaining(
                level.dimension().identifier().toString(), pos);
        if (body.isEmpty()) {
            clearSelection(toolStack);
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.select_tool.no_body"));
            return InteractionResult.FAIL;
        }

        List<BlockPos> positions = solidPositions(level, body.get().positions());
        if (positions.isEmpty()) {
            clearSelection(toolStack);
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.select_tool.no_body"));
            return InteractionResult.FAIL;
        }

        writeSelection(toolStack, body.get().id(), positions);
        SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.select_tool.selected",
                positions.size()));
        return InteractionResult.SUCCESS_SERVER;
    }

    private static List<BlockPos> solidPositions(Level level, List<BlockPos> positions) {
        return positions.stream()
                .filter(pos -> !level.getBlockState(pos).isAir())
                .toList();
    }

    private static void writeSelection(ItemStack stack, UUID bodyId, List<BlockPos> positions) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag tool = new CompoundTag();
            tool.putString(BODY_ID_TAG, bodyId.toString());
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
