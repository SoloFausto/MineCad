package dev.faus.minecad;

import java.util.Optional;
import java.util.function.Consumer;

import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.ExtrusionWorldData;
import dev.faus.minecad.sketch.PlaneSketchData;
import dev.faus.minecad.sketch.PlaneSketchData.PolygonAppendResult;
import dev.faus.minecad.sketch.PlanePoint;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class PolygonToolItem extends Item {
    public PolygonToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }

        Optional<PlanePoint> point = SketchToolSupport.pointFromUseOn(context);
        if (point.isEmpty()) {
            return InteractionResult.FAIL;
        }
        return addVertex(context.getLevel(), context.getPlayer(), point.get());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        Optional<PlanePoint> hit = SketchToolSupport.pointFromRaycast(level, player);
        if (hit.isEmpty()) {
            return InteractionResult.FAIL;
        }

        return addVertex(level, player, hit.get());
    }

    private static InteractionResult addVertex(Level level, Player player, PlanePoint vertex) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return InteractionResult.FAIL;
        }

        var beforeSketch = activePlane.get().sketch();
        PolygonAppendResult result = PlaneSketchData.appendPolygonVertex(activePlane.get().stack(), vertex)
                .orElse(null);
        if (result == null) {
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide()) {
            ExtrusionWorldData.get((net.minecraft.server.level.ServerLevel) level)
                    .recordPrimitiveChange(beforeSketch, result.sketch());
            var pos = result.sketch().plane().unproject(vertex);
            String messageKey = result.closed()
                    ? "message.minecad.sketch_tool.closed"
                    : "message.minecad.sketch_tool.vertex";
            sendMessage(player, Component.translatable(messageKey,
                    result.vertexCount(), pos.getX(), pos.getY(), pos.getZ()));
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.minecad.sketch_tool"));
    }

    private static void sendMessage(Player player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }
}
