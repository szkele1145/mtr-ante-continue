package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import cn.zbx1425.mtrsteamloco.data.RailModelRegistry;
import cn.zbx1425.mtrsteamloco.gui.SelectListScreen;
import cn.zbx1425.mtrsteamloco.mixin.LevelRendererAccessor;
import cn.zbx1425.sowcer.batch.BatchManager;
import cn.zbx1425.sowcer.batch.ShaderProp;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcerext.reuse.DrawScheduler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTRClient;
import mtr.client.ClientData;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.TransportMode;
import mtr.mappings.Utilities;
import mtr.mappings.UtilitiesClient;
import mtr.render.RenderTrains;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import cn.zbx1425.mtrsteamloco.data.RailModelProperties;
import cn.zbx1425.mtrsteamloco.gui.DirectNodeScreen;
import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.render.RenderUtil;

import java.util.*;

public class RailRenderDispatcher {

    private final HashMap<Rail, BakedRail> railRefMap = new HashMap<>();
    private final HashMap<String, HashMap<Long, RailChunkBase>> railChunkMap = new HashMap<>();
    private final List<RailChunkBase> railChunkList = new LinkedList<>();
    private boolean isInstanced;

    private final HashSet<Rail> currentFrameRails = new HashSet<>();

    // [续更优化] 排序结果缓存：相机跨 chunk 或 chunk 结构变化时才重新排序，
    // 避免大型存档下每帧 O(n log n) 排序；复用差集容器减少每帧 GC 压力
    private int structureVersion = 0;
    private int lastSortedVersion = -1;
    private long lastSortedCameraChunk = Long.MIN_VALUE;
    private final HashSet<Rail> scratchRailsToAdd = new HashSet<>();
    private final HashSet<Rail> scratchRailsToRemove = new HashSet<>();

    public static boolean isHoldingRailItem = false;
    public static boolean isHoldingBrush = false;
    public static boolean isHoldingRailItemOrBrush = false;
    public static boolean isPreviewingModel = false;

    private void addRail(Rail rail) {
        if (railRefMap.containsKey(rail)) return;
        structureVersion++;
        BakedRail bakedRail = new BakedRail(rail);
        railRefMap.put(rail, bakedRail);
        HashMap<Long, RailChunkBase> chunkMap = railChunkMap.get(bakedRail.modelKey);
        if (chunkMap == null) return;
        for (long chunkId : bakedRail.coveredChunks.keySet()) {
            if (chunkMap.containsKey(chunkId)) {
                chunkMap.get(chunkId).addRail(bakedRail);
            } else {
                RailChunkBase newChunk;
                if (isInstanced) {
                    newChunk = new InstancedRailChunk(chunkId, bakedRail.modelKey);
                } else {
                    newChunk = new MeshBuildingRailChunk(chunkId, bakedRail.modelKey);
                }
                newChunk.addRail(bakedRail);
                chunkMap.put(chunkId, newChunk);
                railChunkList.add(newChunk);
            }
        }
    }

    private void removeRail(Rail rail) {
        if (!railRefMap.containsKey(rail)) return;
        structureVersion++;
        BakedRail bakedRail = railRefMap.get(rail);
        railRefMap.remove(rail);
        HashMap<Long, RailChunkBase> chunkMap = railChunkMap.get(bakedRail.modelKey);
        if (chunkMap == null) return;
        for (long chunkId : bakedRail.coveredChunks.keySet()) {
            RailChunkBase chunk = chunkMap.get(chunkId);
            if (chunk == null) continue;
            chunk.removeRail(bakedRail);
        }
        bakedRail.dispose();
    }

    public boolean registerRail(Rail rail) {
        if (getModelKeyForRender(rail).isEmpty() || rail.railType == RailType.NONE) return false;
        currentFrameRails.add(rail);
        return true;
    }

    public void clearRail() {
        currentFrameRails.clear();
        structureVersion++;
        Collection<BakedRail> bakedRails = railRefMap.values();
        for (BakedRail rail : bakedRails) {
            rail.dispose();
        }
        railRefMap.clear();
        for (HashMap<Long, RailChunkBase> chunkMap : railChunkMap.values()) {
            for (RailChunkBase chunk : chunkMap.values()) {
                chunk.close();
            }
            chunkMap.clear();
        }
        railChunkMap.clear();
        railChunkList.clear();
        for (String key : RailModelRegistry.ELEMENTS.keySet()) {
            railChunkMap.put(key, new HashMap<>());
        }
    }

    public void registerLightUpdate(int x, int yMin, int yMax, int z) {
        long chunkId = BakedRail.chunkIdFromSectPos(x, z);
        for (HashMap<Long, RailChunkBase> chunkMap : railChunkMap.values()) {
            RailChunkBase chunk = chunkMap.get(chunkId);
            if (chunk != null && !chunk.isDirty && chunk.containsYSection(yMin, yMax)) {
                chunk.isDirty = true;
            }
        }
    }

    public void prepareDraw() {
        if (Minecraft.getInstance().player == null) return;
        Screen currentScreen = Minecraft.getInstance().screen;
        isPreviewingModel = currentScreen instanceof SelectListScreen && ((SelectListScreen)currentScreen).isSelecting();
        if (!isPreviewingModel) {
            isHoldingRailItem = RenderTrains.isHoldingRailRelated(Minecraft.getInstance().player);
            isHoldingBrush = Utilities.isHolding(Minecraft.getInstance().player, (item) -> item.equals(mtr.Items.BRUSH.get())) ||
                Utilities.isHolding(Minecraft.getInstance().player, (item) -> item.equals(Main.COMPOUND_CREATOR.get())) ||
                Utilities.isHolding(Minecraft.getInstance().player, (item) -> item.equals(Main.DISPLACEMENT_TOOL.get())) || Utilities.isHolding(Minecraft.getInstance().player, (item) -> item.equals(Main.RAIL_PATH_EDITOR.get()));
            isHoldingRailItemOrBrush = isHoldingRailItem || isHoldingBrush;
        } else {
            isHoldingRailItem = false;
            isHoldingBrush = false;
            isHoldingRailItemOrBrush = false;
        }
    }

    public void drawRails(Level level, DrawScheduler drawScheduler, Matrix4f viewMatrix) {
        BatchManager batchManager = drawScheduler.batchManager;
        boolean shouldBeInstanced = ClientConfig.getRailRenderLevel() == 3;
        if (isInstanced != shouldBeInstanced) clearRail();
        isInstanced = shouldBeInstanced;

        HashSet<Rail> railsToAdd = scratchRailsToAdd;
        HashSet<Rail> railsToRemove = scratchRailsToRemove;
        railsToAdd.clear();
        railsToAdd.addAll(currentFrameRails);
        railsToAdd.removeAll(railRefMap.keySet());
        for (Rail rail : railsToAdd) addRail(rail);
        railsToRemove.clear();
        railsToRemove.addAll(railRefMap.keySet());
        railsToRemove.removeAll(currentFrameRails);
        for (Rail rail : railsToRemove) removeRail(rail);
        currentFrameRails.clear();

        Vec3 cameraBlockPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vector3f cameraPos = new Vector3f(cameraBlockPos);

        // [续更优化] 排序与距离缓存：相机没跨 chunk 且结构未变时跳过每帧全量重排
        // （大型存档 railChunkList 可能上千，每帧 sort 是稳定的帧率毛刺来源）
        final long cameraChunk = BakedRail.chunkIdFromWorldPos((float) cameraBlockPos.x, (float) cameraBlockPos.z);
        final boolean needsResort = structureVersion != lastSortedVersion || cameraChunk != lastSortedCameraChunk;
        if (needsResort) {
            railChunkList.sort(Comparator.comparingDouble(chunk -> chunk.getCameraDistManhattanXZ(cameraBlockPos)));
            lastSortedVersion = structureVersion;
            lastSortedCameraChunk = cameraChunk;
        }

        Frustum cullingFrustum = ((LevelRendererAccessor)Minecraft.getInstance().levelRenderer).getCullingFrustum();
        ShaderProp shaderProp = new ShaderProp().setViewMatrix(viewMatrix);

        int maxRailDistance = MTRClient.isReplayMod() ? 64 * 16 : (UtilitiesClient.getRenderDistance() + 3) * 16;

        RailChunkBase.upload();

        for (Iterator<RailChunkBase> it = railChunkList.iterator(); it.hasNext(); ) {
            RailChunkBase chunk = it.next();
            if (chunk.containingRails.isEmpty() && chunk.containingRailsWriting.isEmpty()) {
                chunk.close();
                it.remove();
                railChunkMap.get(chunk.modelKey).remove(chunk.chunkId);
                continue;
            }
            // [续更优化] 距离逐 chunk 实时计算（开销可忽略）：排序结果已缓存，
            // 不能再依赖"排好序后首次超距即整体退出"的旧短路逻辑，
            // 否则相机小幅移动时会在渲染距离边界产生错误剔除
            if (chunk.getCameraDistManhattanXZ(cameraBlockPos) > maxRailDistance) {
                continue;
            }
            if (!chunk.bufferBuilding && (chunk.isDirty || !chunk.bufferBuilt)) {
                chunk.rebuildBuffer(level);
                // RenderUtil.displayStatusMessage("Rebuilt: " + chunk.getChunkPos().toString());
            }
            if (chunk.bufferBuilt && cullingFrustum.isVisible(chunk.boundingBox)) {
                chunk.enqueue(batchManager, shaderProp);
            }
        }        
    }

    public void drawRailNodes(Level level, DrawScheduler drawScheduler, Matrix4f viewMatrix) {
        if (isHoldingRailItemOrBrush) {
            HashSet<BlockPos> drawnNodes = new HashSet<>();
            for (Map.Entry<BlockPos, Map<BlockPos, Rail>> entryStart : ClientData.RAILS.entrySet()) {
                for (Map.Entry<BlockPos, Rail> entryEnd : entryStart.getValue().entrySet()) {
                    if (drawnNodes.add(entryStart.getKey())) {
                        Matrix4f nodePose = viewMatrix.copy();
                        nodePose.translate(entryStart.getKey().getX() + 0.5f,
                                entryStart.getKey().getY(), entryStart.getKey().getZ() + 0.5f);
                        nodePose.rotateY(-(float) entryEnd.getValue().facingStart.angleRadians + (float) Math.PI / 2);
                        final int light = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, entryStart.getKey()),
                                level.getBrightness(LightLayer.SKY, entryStart.getKey()));
                        drawScheduler.enqueue(RailModelRegistry.railNodeModel, nodePose, light);
                    }
                    if (drawnNodes.add(entryEnd.getKey())) {
                        Matrix4f nodePose = viewMatrix.copy();
                        nodePose.translate(entryEnd.getKey().getX() + 0.5f,
                                entryEnd.getKey().getY(), entryEnd.getKey().getZ() + 0.5f);
                        nodePose.rotateY(-(float) entryEnd.getValue().facingEnd.angleRadians + (float) Math.PI / 2);
                        final int light = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, entryEnd.getKey()),
                                level.getBrightness(LightLayer.SKY, entryEnd.getKey()));
                        drawScheduler.enqueue(RailModelRegistry.railNodeModel, nodePose, light);
                    }
                }
            }
        }
    }

    // "null": hidden, "": use MTR's default pipeline
    public static String getModelKeyForRender(Rail rail) {
        String customModelKey = ((RailExtraSupplier)rail).getModelKey();
        if (customModelKey.equals("") || !RailModelRegistry.ELEMENTS.containsKey(customModelKey)) {
            if (rail.transportMode == TransportMode.TRAIN) {
                if (rail.railType == RailType.SIDING) {
                    return "nte_builtin_depot";
                } else {
                    return "nte_builtin_concrete_sleeper";
                }
            } else {
                return "";
            }
        } else {
            if (customModelKey.equals("null")) {
                return isHoldingRailItem ? "" : "null";
            } else {
                return customModelKey;
            }
        }
    }

    public void drawBoundingBoxes(PoseStack matrixStack, VertexConsumer buffer) {
        for (RailChunkBase chunk : railChunkList) {
            boolean isChunkEven = chunk.isEven();
            LevelRenderer.renderLineBox(matrixStack, buffer, chunk.boundingBox,
                    1.0f, isChunkEven ? 1.0f : 0.0f, isChunkEven ? 0.0f : 1.0f, 1.0f);
#if DEBUG
            for (ArrayList<Matrix4f> rail : chunk.containingRails.values()) {
                for (Matrix4f pieceMat : rail) {
                    final Vector3f lightPos = pieceMat.getTranslationPart();
                    final BlockPos lightBlockPos = new BlockPos(lightPos.x(), lightPos.y() + 0.1, lightPos.z());
                    LevelRenderer.renderLineBox(matrixStack, buffer, new AABB(lightBlockPos),
                            1.0f, isChunkEven ? 1.0f : 0.0f, isChunkEven ? 0.0f : 1.0f, 1.0f);
                }
            }
#endif
        }
    }
}
