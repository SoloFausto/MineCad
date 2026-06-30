package dev.faus.minecad;

import java.util.function.Consumer;

import dev.faus.minecad.sketch.ExtrusionWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class HistoryToolItem extends Item {
    public HistoryToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        return stepHistory(context.getLevel(), player);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return stepHistory(level, player);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.minecad.history_tool"));
    }

    private static InteractionResult stepHistory(Level level, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.FAIL;
        }

        var activePlane = PlaneItem.getActivePlaneSketch(player);
        ExtrusionWorldData.HistoryStep step = player.isShiftKeyDown()
                ? ExtrusionWorldData.get(serverLevel).stepForward(serverLevel, activePlane)
                : ExtrusionWorldData.get(serverLevel).stepBack(serverLevel, activePlane);
        if (!step.changed()) {
            SketchToolSupport.sendMessage(player, Component.translatable(step.messageKey()));
            return InteractionResult.FAIL;
        }

        SketchToolSupport.sendMessage(player, Component.translatable(step.messageKey(), step.index(), step.total()));
        return InteractionResult.SUCCESS_SERVER;
    }
}
