package dev.faus.minecad;

import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import net.minecraft.world.item.Item;

public class SketchItems {
    public static void initialize() {
    }

    public static <T extends Item> T register(String name, Function<Item.Properties, T> itemFactory,
            Item.Properties settings) {
        // Create the item key.
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(MineCad.MOD_ID, name));

        // Create the item instance.
        T item = itemFactory.apply(settings.setId(itemKey));

        // Register the item.
        Registry.register(BuiltInRegistries.ITEM, itemKey, item);

        return item;
    }

    public static final Item PLANE = register("plane", PlaneItem::new, new Item.Properties().stacksTo(1));
    public static final Item SKETCH_TOOL = register("sketch_tool", SketchToolItem::new,
            new Item.Properties().stacksTo(1));
    public static final Item BOX_TOOL = register("box_tool", BoxToolItem::new, new Item.Properties().stacksTo(1));
    public static final Item CIRCLE_TOOL = register("circle_tool", CircleToolItem::new,
            new Item.Properties().stacksTo(1));

}
