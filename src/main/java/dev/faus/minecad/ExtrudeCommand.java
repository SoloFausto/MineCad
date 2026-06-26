package dev.faus.minecad;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.Commands;

public final class ExtrudeCommand {
    private ExtrudeCommand() {
    }

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
                Commands.literal("extrude")
                        .then(operation("add", ExtrudeToolItem.Operation.ADD, buildContext))
                        .then(operation("addition", ExtrudeToolItem.Operation.ADD, buildContext))
                        .then(operation("subtract", ExtrudeToolItem.Operation.SUBTRACT, buildContext))
                        .then(operation("subtraction", ExtrudeToolItem.Operation.SUBTRACT, buildContext))
                        .then(operation("substraction", ExtrudeToolItem.Operation.SUBTRACT, buildContext))
                        .then(operation("difference", ExtrudeToolItem.Operation.DIFFERENCE, buildContext))
                        .executes(context -> ExtrudeToolItem.extrudeSelected(
                                context.getSource().getPlayerOrException(),
                                ExtrudeToolItem.Operation.ADD,
                                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()))
                        .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                                .executes(context -> ExtrudeToolItem.extrudeSelected(
                                        context.getSource().getPlayerOrException(),
                                        ExtrudeToolItem.Operation.ADD,
                                        BlockStateArgument.getBlock(context, "block").getState())))
                        .then(Commands.argument("depth", IntegerArgumentType.integer())
                                .executes(context -> ExtrudeToolItem.extrudeSelectedDepth(
                                        context.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(context, "depth")))
                                .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                                        .executes(context -> ExtrudeToolItem.extrudeSelectedDepth(
                                                context.getSource().getPlayerOrException(),
                                                IntegerArgumentType.getInteger(context, "depth"),
                                                BlockStateArgument.getBlock(context, "block").getState()))))));

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
            String text = message.signedContent().trim();
            if (!text.matches("[+-]?\\d+") || !ExtrudeToolItem.hasPendingSelection(sender)) {
                return true;
            }

            try {
                ExtrudeToolItem.extrudeSelectedDepth(sender, Integer.parseInt(text));
                return false;
            } catch (NumberFormatException exception) {
                return true;
            }
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> operation(
            String name, ExtrudeToolItem.Operation operation, net.minecraft.commands.CommandBuildContext buildContext) {
        return Commands.literal(name)
                .executes(context -> ExtrudeToolItem.extrudeSelected(
                        context.getSource().getPlayerOrException(),
                        operation,
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()))
                .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                        .executes(context -> ExtrudeToolItem.extrudeSelected(
                                context.getSource().getPlayerOrException(),
                                operation,
                                BlockStateArgument.getBlock(context, "block").getState())))
                .then(Commands.argument("depth", IntegerArgumentType.integer())
                        .executes(context -> ExtrudeToolItem.extrudeSelectedDepth(
                                context.getSource().getPlayerOrException(),
                                IntegerArgumentType.getInteger(context, "depth"),
                                operation))
                        .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                                .executes(context -> ExtrudeToolItem.extrudeSelectedDepth(
                                        context.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(context, "depth"),
                                        operation,
                                        BlockStateArgument.getBlock(context, "block").getState()))));
    }
}
