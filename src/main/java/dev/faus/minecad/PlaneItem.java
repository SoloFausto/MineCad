package dev.faus.minecad;

import java.util.Optional;
import java.util.function.Consumer;

import dev.faus.minecad.sketch.PlaneSketchData;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.SketchPlane;
import dev.faus.minecad.sketch.SketchSessionStore;
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

public class PlaneItem extends Item {
    public PlaneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        Optional<PlaneSketch> existingSketch = PlaneSketchData.read(context.getItemInHand());
        if (existingSketch.isPresent()) {
            activateSketch(player, existingSketch.get(), level);
            return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }

        SketchPlane plane = SketchPlane.fromClickedFace(context.getClickedPos(), context.getClickedFace());
        PlanePoint origin = plane.project(SketchPlane.pointOnClickedFace(context.getClickedPos(), context.getClickedFace()));
        String dimension = level.dimension().identifier().toString();
        PlaneSketch sketch = PlaneSketchData.create(context.getItemInHand(), dimension, plane, origin,
                context.getClickedFace().getAxisDirection().getStep());

        activateSketch(player, sketch, level);

        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        Optional<PlaneSketch> sketch = PlaneSketchData.read(player.getItemInHand(hand));
        if (sketch.isEmpty()) {
            return InteractionResult.PASS;
        }

        activateSketch(player, sketch.get(), level);
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        Optional<PlaneSketch> sketch = PlaneSketchData.read(stack);
        if (sketch.isEmpty()) {
            tooltip.accept(Component.translatable("tooltip.minecad.plane"));
            return;
        }

        tooltip.accept(Component.translatable("tooltip.minecad.plane.active",
                sketch.get().plane().axis().getName().toUpperCase(), sketch.get().plane().coordinate()));
        tooltip.accept(Component.translatable("tooltip.minecad.plane.objects", sketch.get().objects().size()));
    }

    public static Optional<PlaneSketchStack> getActivePlaneSketch(Player player) {
        HeldPlaneSketch held = getHeldPlaneSketch(player);
        if (held.hasPlaneItem()) {
            return held.sketch();
        }

        Optional<PlaneSketchStack> active = SketchSessionStore.getActivePlane(player)
                .flatMap(id -> findPlaneSketch(player, id));
        if (active.isPresent()) {
            return active;
        }

        return Optional.empty();
    }

    private static HeldPlaneSketch getHeldPlaneSketch(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof PlaneItem) {
            Optional<PlaneSketch> sketch = PlaneSketchData.read(mainHand);
            if (sketch.isPresent()) {
                SketchSessionStore.setActivePlane(player, sketch.get().id());
                return new HeldPlaneSketch(true, Optional.of(new PlaneSketchStack(mainHand, sketch.get())));
            }
            return new HeldPlaneSketch(true, Optional.empty());
        }

        ItemStack offHand = player.getOffhandItem();
        if (offHand.getItem() instanceof PlaneItem) {
            Optional<PlaneSketch> sketch = PlaneSketchData.read(offHand);
            if (sketch.isPresent()) {
                SketchSessionStore.setActivePlane(player, sketch.get().id());
                return new HeldPlaneSketch(true, Optional.of(new PlaneSketchStack(offHand, sketch.get())));
            }
            return new HeldPlaneSketch(true, Optional.empty());
        }

        return new HeldPlaneSketch(false, Optional.empty());
    }

    private static Optional<PlaneSketchStack> findPlaneSketch(Player player, java.util.UUID id) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof PlaneItem) {
                Optional<PlaneSketch> sketch = PlaneSketchData.read(stack);
                if (sketch.isPresent() && sketch.get().id().equals(id)) {
                    return Optional.of(new PlaneSketchStack(stack, sketch.get()));
                }
            }
        }
        return Optional.empty();
    }

    private static void activateSketch(Player player, PlaneSketch sketch, Level level) {
        SketchSessionStore.setActivePlane(player, sketch.id());
        if (!level.isClientSide()) {
            player.sendSystemMessage(Component.translatable("message.minecad.plane.set",
                    sketch.plane().axis().getName().toUpperCase(), sketch.plane().coordinate()));
        }
    }

    public record PlaneSketchStack(ItemStack stack, PlaneSketch sketch) {
    }

    private record HeldPlaneSketch(boolean hasPlaneItem, Optional<PlaneSketchStack> sketch) {
    }
}
