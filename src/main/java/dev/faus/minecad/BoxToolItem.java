package dev.faus.minecad;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.PlaneSketchData;
import dev.faus.minecad.sketch.PlanePoint;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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

public class BoxToolItem extends Item {
    private static final String TOOL_TAG = "minecad_box_tool";
    private static final String PLANE_ID_TAG = "plane_id";
    private static final String FIRST_U_TAG = "first_u";
    private static final String FIRST_V_TAG = "first_v";

    public BoxToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }

        Optional<PlanePoint> point = SketchToolSupport.pointFromUseOn(context);
        return point.map(planePoint -> usePoint(context.getLevel(), context.getPlayer(), context.getItemInHand(), planePoint))
                .orElse(InteractionResult.FAIL);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        Optional<PlanePoint> point = SketchToolSupport.pointFromRaycast(level, player);
        return point.map(planePoint -> usePoint(level, player, player.getItemInHand(hand), planePoint))
                .orElse(InteractionResult.FAIL);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.minecad.box_tool"));
    }

    private static InteractionResult usePoint(Level level, Player player, ItemStack toolStack, PlanePoint point) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return InteractionResult.FAIL;
        }

        PendingPoint pending = readPending(toolStack).orElse(null);
        UUID planeId = activePlane.get().sketch().id();
        if (pending == null || !pending.planeId().equals(planeId)) {
            writePending(toolStack, planeId, point);
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.box_tool.first"));
            }
            return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }

        PlaneSketchData.appendBox(activePlane.get().stack(), pending.point(), point);
        clearPending(toolStack);
        if (!level.isClientSide()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.box_tool.complete"));
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    private static Optional<PendingPoint> readPending(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompoundOrEmpty(TOOL_TAG);
        if (!tag.contains(PLANE_ID_TAG)) {
            return Optional.empty();
        }

        try {
            UUID planeId = UUID.fromString(tag.getStringOr(PLANE_ID_TAG, ""));
            return Optional.of(new PendingPoint(planeId,
                    new PlanePoint(tag.getIntOr(FIRST_U_TAG, 0), tag.getIntOr(FIRST_V_TAG, 0))));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static void writePending(ItemStack stack, UUID planeId, PlanePoint point) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag tool = new CompoundTag();
            tool.putString(PLANE_ID_TAG, planeId.toString());
            tool.putInt(FIRST_U_TAG, point.u());
            tool.putInt(FIRST_V_TAG, point.v());
            tag.put(TOOL_TAG, tool);
        });
    }

    private static void clearPending(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(TOOL_TAG));
    }

    private record PendingPoint(UUID planeId, PlanePoint point) {
    }
}
