package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StorageBlockListSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RpcBaseFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Rendering");

    private final Setting<List<BlockEntityType<?>>> targetBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("target-blocks")
        .description("Block entities that count towards a base.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Integer> minimumCount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-blocks")
        .description("Minimum counted block entities in a chunk before it is marked as a base.")
        .defaultValue(6)
        .min(1)
        .sliderMin(1)
        .build()
    );

    private final Setting<Integer> minimumDistance = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-distance")
        .description("Minimum distance from spawn for a base to be highlighted.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Send a push notification when a new base is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> notificationMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("How to deliver base notifications.")
        .defaultValue(Mode.Both)
        .visible(notify::get)
        .build()
    );

    private final Setting<Double> renderDistance = sgRender.add(new DoubleSetting.Builder()
        .name("render-distance")
        .description("Maximum distance to render base overlays.")
        .defaultValue(256)
        .min(16)
        .sliderMax(512)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How to render found bases.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Fill color for detected bases.")
        .defaultValue(new SettingColor(255, 0, 0, 35))
        .visible(() -> shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color for detected bases.")
        .defaultValue(new SettingColor(255, 32, 32, 200))
        .visible(() -> shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Map<ChunkPos, Integer> foundBases = new HashMap<>();

    public RpcBaseFinder() {
        super(Categories.Render, "rpc-base-finder", "Scans chunks for base-like block entities and highlights them.");
    }

    @Override
    public void onActivate() {
        foundBases.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (event.chunk() == null || mc.world == null) return;

        double chunkXAbs = Math.abs(event.chunk().getPos().x * 16.0);
        double chunkZAbs = Math.abs(event.chunk().getPos().z * 16.0);
        if (Math.sqrt(chunkXAbs * chunkXAbs + chunkZAbs * chunkZAbs) < minimumDistance.get()) return;

        int countedBlocks = 0;
        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            if (!targetBlocks.get().contains(blockEntity.getType())) continue;
            countedBlocks++;
        }

        if (countedBlocks < minimumCount.get()) return;

        ChunkPos pos = event.chunk().getPos();
        Integer previous = foundBases.put(pos, countedBlocks);

        if (notify.get() && (previous == null || previous != countedBlocks)) {
            switch (notificationMode.get()) {
                case Chat -> info("(highlight)Base(default) gefunden bei (highlight)%s(default), (highlight)%s(default) mit (highlight)%d(default) Block-Entitaeten.", pos.x, pos.z, countedBlocks);
                case Toast -> {
                    MeteorToast toast = new MeteorToast.Builder(title).icon(Items.RED_BED).text("Base entdeckt!").build();
                    mc.getToastManager().add(toast);
                }
                case Both -> {
                    info("(highlight)Base(default) gefunden bei (highlight)%s(default), (highlight)%s(default) mit (highlight)%d(default) Block-Entitaeten.", pos.x, pos.z, countedBlocks);
                    MeteorToast toast = new MeteorToast.Builder(title).icon(Items.RED_BED).text("Base entdeckt!").build();
                    mc.getToastManager().add(toast);
                }
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        Color sides = new Color(fillColor.get());
        Color lines = new Color(lineColor.get());

        for (Map.Entry<ChunkPos, Integer> entry : foundBases.entrySet()) {
            ChunkPos chunkPos = entry.getKey();
            double x1 = chunkPos.getStartX();
            double z1 = chunkPos.getStartZ();
            double x2 = chunkPos.getEndX() + 1;
            double z2 = chunkPos.getEndZ() + 1;

            double centerX = (x1 + x2) / 2.0;
            double centerZ = (z1 + z2) / 2.0;
            double distanceSq = mc.player.squaredDistanceTo(centerX, mc.player.getY(), centerZ);
            if (distanceSq > renderDistance.get() * renderDistance.get()) continue;

            Box box = new Box(x1, mc.world.getBottomY(), z1, x2, mc.world.getTopY(), z2);
            event.renderer.box(box, sides, lines, shapeMode.get(), 0);
        }
    }

    public enum Mode {
        Chat,
        Toast,
        Both
    }
}
