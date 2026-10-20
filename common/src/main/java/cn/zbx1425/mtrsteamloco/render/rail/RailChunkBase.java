package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.data.RailModelRegistry;
import cn.zbx1425.sowcer.batch.BatchManager;
import cn.zbx1425.sowcer.batch.ShaderProp;
import cn.zbx1425.sowcer.math.Matrix4f;
import mtr.data.Rail;
import mtr.data.RailAngle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import cn.zbx1425.mtrsteamloco.scripting.util.client.DynamicModelHolder;
import cn.zbx1425.sowcerext.reuse.DrawScheduler;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.ConcurrentLinkedQueue;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class RailChunkBase implements Closeable {

    protected static ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    protected static ConcurrentLinkedQueue<Runnable> UPLOAD_QUEUE = new ConcurrentLinkedQueue<>();

    public Long chunkId;
    // [修复 2026-10-21] boundingBox 由后台 EXECUTOR 线程写（setBoundingBox）、
    // 渲染线程读（RailRenderDispatcher 的视锥剔除与相机距离计算）。
    // 原为普通字段，无 happens-before 关系 ⇒ 渲染线程可能读到**半更新**的 AABB
    // （minX 是新值、maxX 还是旧值）⇒ 轨道被错误剔除或错误渲染。
    // AABB 是不可变对象，volatile 整体替换引用即可保证可见性。
    public volatile AABB boundingBox;
    public HashMap<BakedRail, ArrayList<Matrix4f>> containingRails = new HashMap<>();
    public HashMap<BakedRail, ArrayList<Matrix4f>> containingRailsWriting = new HashMap<>();

    public final String modelKey;

    protected float modelYMin;
    protected float modelYMax;

    public boolean isDirty = false;
    public boolean bufferBuilt = false;
    public boolean bufferBuilding = false;
    public double cameraDistManhattanXZ = 0;

    public RailChunkBase(long chunkId, String modelKey) {
        this.chunkId = chunkId;
        this.modelKey = modelKey;
        long boundary = RailModelRegistry.getProperty(modelKey).boundingBox;
        modelYMin = Float.intBitsToFloat((int)(boundary >> 32));
        modelYMax = Float.intBitsToFloat((int)(boundary & 0xFFFFFFFFL));
        setBoundingBox(0, 0);
    }

    protected void setBoundingBox(float yMin, float yMax) {
        int posXMin = (int)(chunkId >> 32) << (4 + BakedRail.POS_SHIFT);
        int posZMin = (int)(chunkId & 0xFFFFFFFFL) << (4 + BakedRail.POS_SHIFT);
        int span = 1 << (4 + BakedRail.POS_SHIFT);
        boundingBox = new AABB(posXMin, yMin + modelYMin - 1, posZMin,
                posXMin + span, yMax + modelYMax + 1, posZMin + span);
    }

    public boolean isEven() { // Just for ease of debugging to show a checkerboard pattern.
        return ((int)(chunkId >> 32) + (int)(chunkId & 0xFFFFFFFFL)) % 2 == 0;
    }

    public ChunkPos getChunkPos() {
        return new ChunkPos((int)(chunkId >> 32) << BakedRail.POS_SHIFT, (int)(chunkId & 0xFFFFFFFFL) << BakedRail.POS_SHIFT);
    }

    // [续更修复] 判断区块更新（方块/光照）的 Y 区段是否与本轨道 chunk 的 Y 范围重叠。
    // 旧实现条件写反（"区段在范围外"才返回 true）：同层的轨道永不重烘焙（光照更新丢失），
    // 而远处无关更新反而触发重建，大型存档下形成持续重建风暴。
    // 现按标准重叠判定：区段顶 > 范围底 且 区段底 < 范围顶。
    public boolean containsYSection(int yMin, int yMax) {
        return ((yMax + 1) << 4) > boundingBox.minY && (yMin << 4) < boundingBox.maxY;
    }

    public double getCameraDistManhattanXZ(Vec3 cameraPos) {
        cameraDistManhattanXZ = Math.abs(cameraPos.x - (boundingBox.minX + boundingBox.maxX) / 2)
                + Math.abs(cameraPos.z - (boundingBox.minZ + boundingBox.maxZ) / 2);
        return cameraDistManhattanXZ;
    }

    public void addRail(BakedRail rail) {
        containingRailsWriting.put(rail, rail.coveredChunks.get(chunkId));
        isDirty = true;
    }

    public void removeRail(BakedRail rail) {
        containingRailsWriting.remove(rail);
        isDirty = true;
    }

    public void rebuildBuffer(Level world) {
        bufferBuilding = true;
        isDirty = false;
        bufferBuilt = true;
        containingRails.clear();
        containingRails.putAll(containingRailsWriting);
    }
    public abstract void enqueue(BatchManager batchManager, ShaderProp shaderProp);

    // private static long lastUploadTime = 0L;
    public static void upload() {
        if (!UPLOAD_QUEUE.isEmpty()) {
            // if (lastUploadTime < System.currentTimeMillis() - 100) {
                UPLOAD_QUEUE.poll().run();
                // lastUploadTime = System.currentTimeMillis();
            // }
        }
    }

    @Override
    public abstract void close();
}
