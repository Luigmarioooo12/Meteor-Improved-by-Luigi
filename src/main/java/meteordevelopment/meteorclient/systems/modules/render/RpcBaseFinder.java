package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final Map<ChunkPos, Set<BlockPos>> countedBlockPositions = new HashMap<>();
    private final Map<ChunkPos, Integer> foundBases = new HashMap<>();

    public RpcBaseFinder() {
        super(Categories.Render, "rpc-base-finder", "Scans chunks for base-like block entities and highlights them.");
    }

    @Override
    public void onActivate() {
        countedBlockPositions.clear();
        foundBases.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world != null && mc.player != null) return;

        countedBlockPositions.clear();
        foundBases.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            ChunkPos pos = new ChunkPos(packet.getChunkX(), packet.getChunkZ());

            if (withinMinimumDistance(pos)) return;

            Set<BlockPos> chunkBlockEntities = countedBlockPositions.computeIfAbsent(pos, p -> new HashSet<>());
            chunkBlockEntities.clear();

            packet.getChunkData().getBlockEntities(packet.getChunkX(), packet.getChunkZ()).accept((blockPos, blockEntityType, nbt) -> {
                trackBlockEntity(pos, blockPos, blockEntityType, chunkBlockEntities);
            });

            if (chunkBlockEntities.size() < minimumCount.get()) return;

            handleBaseDetection(pos, chunkBlockEntities.size());
        }

        if (event.packet instanceof UnloadChunkS2CPacket packet) {
            ChunkPos pos = new ChunkPos(packet.pos().x, packet.pos().z);
            removeChunk(pos);
            return;
        }

        if (!(event.packet instanceof BlockEntityUpdateS2CPacket blockEntityUpdate)) return;

        ChunkPos pos = new ChunkPos(blockEntityUpdate.getPos());

        if (withinMinimumDistance(pos)) return;

        Set<BlockPos> chunkBlockEntities = countedBlockPositions.computeIfAbsent(pos, p -> new HashSet<>());
        trackBlockEntity(pos, blockEntityUpdate.getPos(), blockEntityUpdate.getBlockEntityType(), chunkBlockEntities);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (event.chunk() == null || mc.world == null) return;

        if (withinMinimumDistance(event.chunk().getPos())) return;

        Set<BlockPos> chunkBlockEntities = countedBlockPositions.computeIfAbsent(event.chunk().getPos(), p -> new HashSet<>());
        chunkBlockEntities.clear();

        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            trackBlockEntity(event.chunk().getPos(), blockEntity.getPos(), blockEntity.getType(), chunkBlockEntities);
        }

        if (chunkBlockEntities.size() < minimumCount.get()) return;

        handleBaseDetection(event.chunk().getPos(), chunkBlockEntities.size());
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

            int topY = mc.world.getBottomY() + mc.world.getHeight();
            Box box = new Box(x1, mc.world.getBottomY(), z1, x2, topY, z2);
            event.renderer.box(box, sides, lines, shapeMode.get(), 0);
        }
    }

    private void handleBaseDetection(ChunkPos pos, int countedBlocks) {
        boolean alreadyDetected = foundBases.containsKey(pos);
        foundBases.put(pos, countedBlocks);

        if (!notify.get() || alreadyDetected) return;

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

    private void trackBlockEntity(ChunkPos chunkPos, BlockPos blockPos, BlockEntityType<?> type, Set<BlockPos> chunkBlockEntities) {
        if (!targetBlocks.get().contains(type)) return;

        if (!chunkBlockEntities.contains(blockPos)) chunkBlockEntities.add(blockPos.toImmutable());

        if (chunkBlockEntities.size() < minimumCount.get()) return;

        handleBaseDetection(chunkPos, chunkBlockEntities.size());
    }

    private void removeChunk(ChunkPos pos) {
        countedBlockPositions.remove(pos);
        foundBases.remove(pos);
    }

    private boolean withinMinimumDistance(ChunkPos pos) {
        double chunkXAbs = Math.abs(pos.x * 16.0);
        double chunkZAbs = Math.abs(pos.z * 16.0);
        return Math.sqrt(chunkXAbs * chunkXAbs + chunkZAbs * chunkZAbs) < minimumDistance.get();
    }

    public enum Mode {
        Chat,
        Toast,
        Both
    }
}
