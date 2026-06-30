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
        public static final Item POLYGON_TOOL = register("polygon_tool", PolygonToolItem::new,
                        new Item.Properties().stacksTo(1));
        public static final Item BOX_TOOL = register("box_tool", BoxToolItem::new, new Item.Properties().stacksTo(1));
        public static final Item CIRCLE_TOOL = register("circle_tool", CircleToolItem::new,
                        new Item.Properties().stacksTo(1));
        public static final Item SELECT_TOOL = register("select_tool", SelectToolItem::new,
                        new Item.Properties().stacksTo(1));
        public static final Item DEBUG_TOOL = register("debug_tool", DebugToolItem::new,
                        new Item.Properties().stacksTo(1));

}
