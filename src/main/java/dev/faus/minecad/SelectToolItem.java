package dev.faus.minecad;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import dev.faus.minecad.PlaneItem.PlaneSketchStack;
import dev.faus.minecad.sketch.PlanePoint;
import dev.faus.minecad.sketch.PlaneSketchData.PlaneSketch;
import dev.faus.minecad.sketch.SketchGeometry;
import dev.faus.minecad.sketch.SketchGeometry.FaceRegion;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

public class SelectToolItem extends Item {
    private static final String TOOL_TAG = "minecad_select_tool";
    private static final String PLANE_ID_TAG = "plane_id";
    private static final String OBJECT_INDEX_TAG = "object_index";
    private static final String OBJECT_INDICES_TAG = "object_indices";
    private static final String FACES_TAG = "faces";
    private static final String SEED_TAG = "seed";
    private static final String U_TAG = "u";
    private static final String V_TAG = "v";

    public SelectToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        Optional<PlanePoint> point = SketchToolSupport.pointFromUseOn(context);
        return point.map(planePoint -> updateSelection(context.getLevel(), player, context.getItemInHand(), planePoint,
                player.isShiftKeyDown()))
                .orElse(InteractionResult.FAIL);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        Optional<PlanePoint> point = SketchToolSupport.pointFromRaycast(level, player);
        return point.map(planePoint -> updateSelection(level, player, player.getItemInHand(hand), planePoint,
                player.isShiftKeyDown()))
                .orElse(InteractionResult.FAIL);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.minecad.select_tool"));
    }

    public static Optional<Selection> selectedFace(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompoundOrEmpty(TOOL_TAG);
        if (!tag.contains(PLANE_ID_TAG)) {
            return Optional.empty();
        }

        try {
            UUID planeId = UUID.fromString(tag.getStringOr(PLANE_ID_TAG, ""));
            List<FaceSelection> faces = readFaces(tag);
            if (faces.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Selection(planeId, faces));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static FaceSelection faceSelection(FaceRegion region) {
        return new FaceSelection(region.objectIndices(), region.seed());
    }

    public static boolean hasSelectedFace(Player player) {
        return selectedFace(player.getMainHandItem()).isPresent() || selectedFace(player.getOffhandItem()).isPresent();
    }

    private static InteractionResult updateSelection(Level level, Player player, ItemStack toolStack, PlanePoint point,
            boolean unselect) {
        Optional<PlaneSketchStack> activePlane = SketchToolSupport.activePlane(level, player);
        if (activePlane.isEmpty()) {
            return InteractionResult.FAIL;
        }

        PlaneSketch sketch = activePlane.get().sketch();
        FaceRegion region = SketchGeometry.faceRegionAt(sketch, point);
        if (region == null) {
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.select_tool.no_face"));
            }
            return InteractionResult.FAIL;
        }

        List<FaceSelection> selectedFaces = selectedFace(toolStack)
                .filter(selection -> selection.planeId().equals(sketch.id()))
                .map(selection -> new ArrayList<>(selection.faces()))
                .orElseGet(ArrayList::new);
        FaceSelection selectedRegion = faceSelection(region);
        if (unselect) {
            if (!selectedFaces.remove(selectedRegion)) {
                return InteractionResult.FAIL;
            }
            writeSelection(toolStack, sketch.id(), selectedFaces);
            if (!level.isClientSide()) {
                SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.select_tool.unselected"));
            }
            return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }

        if (!selectedFaces.contains(selectedRegion)) {
            selectedFaces.add(selectedRegion);
        }
        writeSelection(toolStack, sketch.id(), selectedFaces);
        if (!level.isClientSide()) {
            SketchToolSupport.sendMessage(player, Component.translatable("message.minecad.select_tool.selected"));
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    private static List<FaceSelection> readFaces(CompoundTag tag) {
        if (tag.contains(FACES_TAG)) {
            List<FaceSelection> faces = new ArrayList<>();
            ListTag faceTags = tag.getListOrEmpty(FACES_TAG);
            for (int i = 0; i < faceTags.size(); i++) {
                Optional<FaceSelection> face = readFace(faceTags.getCompoundOrEmpty(i));
                face.ifPresent(faces::add);
            }
            return List.copyOf(faces);
        }

        List<Integer> legacyFace = readObjectIndices(tag);
        return legacyFace.isEmpty() ? List.of() : List.of(new FaceSelection(legacyFace, null));
    }

    private static Optional<FaceSelection> readFace(CompoundTag tag) {
        List<Integer> objectIndices = readObjectIndices(tag);
        if (objectIndices.isEmpty()) {
            return Optional.empty();
        }

        if (!tag.contains(SEED_TAG)) {
            return Optional.of(new FaceSelection(objectIndices, null));
        }

        CompoundTag seedTag = tag.getCompoundOrEmpty(SEED_TAG);
        return Optional.of(new FaceSelection(objectIndices,
                new PlanePoint(seedTag.getIntOr(U_TAG, 0), seedTag.getIntOr(V_TAG, 0))));
    }

    private static List<Integer> readObjectIndices(CompoundTag tag) {
        if (tag.contains(OBJECT_INDICES_TAG)) {
            List<Integer> indices = new ArrayList<>();
            ListTag indexTags = tag.getListOrEmpty(OBJECT_INDICES_TAG);
            for (int i = 0; i < indexTags.size(); i++) {
                CompoundTag indexTag = indexTags.getCompoundOrEmpty(i);
                indices.add(indexTag.getIntOr(OBJECT_INDEX_TAG, -1));
            }
            return indices.stream().filter(index -> index >= 0).toList();
        }
        if (tag.contains(OBJECT_INDEX_TAG)) {
            return List.of(tag.getIntOr(OBJECT_INDEX_TAG, -1)).stream().filter(index -> index >= 0).toList();
        }
        return List.of();
    }

    private static void writeSelection(ItemStack stack, UUID planeId, List<FaceSelection> faces) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (faces.isEmpty()) {
                tag.remove(TOOL_TAG);
                return;
            }

            CompoundTag tool = new CompoundTag();
            tool.putString(PLANE_ID_TAG, planeId.toString());
            tool.put(FACES_TAG, writeFaces(faces));
            tag.put(TOOL_TAG, tool);
        });
    }

    private static ListTag writeFaces(List<FaceSelection> faces) {
        ListTag tags = new ListTag();
        for (FaceSelection face : faces) {
            CompoundTag faceTag = new CompoundTag();
            faceTag.put(OBJECT_INDICES_TAG, writeObjectIndices(face.objectIndices()));
            if (face.seed() != null) {
                faceTag.put(SEED_TAG, writePoint(face.seed()));
            }
            tags.add(faceTag);
        }
        return tags;
    }

    private static ListTag writeObjectIndices(List<Integer> objectIndices) {
        ListTag tags = new ListTag();
        for (Integer objectIndex : objectIndices) {
            CompoundTag indexTag = new CompoundTag();
            indexTag.putInt(OBJECT_INDEX_TAG, objectIndex);
            tags.add(indexTag);
        }
        return tags;
    }

    private static CompoundTag writePoint(PlanePoint point) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(U_TAG, point.u());
        tag.putInt(V_TAG, point.v());
        return tag;
    }

    public record Selection(UUID planeId, List<FaceSelection> faces) {
        public Selection {
            faces = faces.stream()
                    .map(face -> new FaceSelection(face.objectIndices(), face.seed()))
                    .toList();
        }
    }

    public record FaceSelection(List<Integer> objectIndices, PlanePoint seed) {
        public FaceSelection {
            objectIndices = List.copyOf(objectIndices);
        }

        public boolean matches(FaceRegion region) {
            if (!objectIndices.equals(region.objectIndices())) {
                return false;
            }
            return seed == null || seed.equals(region.seed());
        }
    }
}
