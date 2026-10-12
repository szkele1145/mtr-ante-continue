package cn.zbx1425.mtrsteamloco.data;

import net.minecraft.core.BlockPos;
import mtr.data.Rail;
import mtr.data.RailType;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public interface RailExtraSupplier {

    // "": default, "null": hidden
    String getModelKey();

    void setModelKey(String key);

    boolean getRenderReversed();

    void setRenderReversed(boolean value);

    float getVerticalCurveRadius();

    void setVerticalCurveRadius(float value);

    int getHeight();

    Map<Double, Float> getRollAngleMap();

    void setRollAngleMap(Map<Double, Float> rollAngleMap);

    // [续更优化] 供 getRollAngle 使用的排序键缓存；实现在 setRollAngleMap 时失效
    double[] nte$getSortedRollKeys();

    float[] nte$getSortedRollValues();

    Map<String, String> getCustomConfigs();
    
    void setCustomConfigs(Map<String, String> customConfigs);

    Map<String, ConfigResponder> getCustomResponders();

    void setCustomResponders(Map<String, ConfigResponder> customResponders);

    void setOpeningDirection(int direction);

    void setOpeningDirectionRaw(int direction);

    int getOpeningDirection();

    int getOpeningDirectionRaw();

    void setRailType(RailType railType);

    void partialCopyFrom(Rail rail);
    
    void setRollingOffset(float rollingOffset);

    float getRollingOffset();

    boolean isStraightOnly();

    void changePathMode(int mode);

    int getPathMode();

    boolean isBetween(double x, double y, double z, double radius);

    Rail getTransposition(RailType railType);

    void setBezier(BezierCurve bezier);

    boolean couldSwitchModeTo(int mode);

    void sendUpdateC2S();

    BlockPos getPosStart();

    BlockPos getPosEnd();

    static float getVTheta(Rail rail, double verticalCurveRadius) {
        double H = Math.abs(((RailExtraSupplier) rail).getHeight());
        double L = rail.getLength();
        double R = verticalCurveRadius;
        return 2 * (float) Mth.atan2(Math.sqrt(H * H - 4 * R * H + L * L) - L, H - 4 * R);
    }

    static float getRollAngle(Rail rail, double value) {
        RailExtraSupplier supplier = (RailExtraSupplier) rail;
        double[] keys = supplier.nte$getSortedRollKeys();
        final int n = keys.length;
        if (n == 0) return 0;

        boolean reversed = supplier.getRenderReversed();
        float k = reversed ? -1F : 1F;
        if (reversed) value = rail.getLength() - value;

        // [续更修复+优化] 节点未覆盖的区段按 0° 处理（隐式端点），而不是把最近节点的角度
        // 钳制到整条轨道。旧逻辑下单个中点节点会导致整条轨道一起滚转，
        // 且轨道端面带倾角，与相邻未刷滚转的轨道衔接时出现明显断裂。
        // 若用户在端点显式放置了节点，则仍以显式值优先（支持跨轨连续滚转）。
        // 排序键值由实现方缓存（RailMixin），此处二分查找、零分配。
        final float[] values = supplier.nte$getSortedRollValues();
        final double length = rail.getLength();
        final double eps = 1e-4;

        if (value <= 0) return k * (keys[0] > eps ? 0f : values[0]);
        if (value >= length) return k * (keys[n - 1] < length - eps ? 0f : values[n - 1]);

        if (value < keys[0]) {
            return k * interpolate(0f, 0f, keys[0], value, values[0]);
        }
        if (value > keys[n - 1]) {
            return k * interpolate(keys[n - 1], values[n - 1], length, value, 0f);
        }

        int idx = java.util.Arrays.binarySearch(keys, value);
        if (idx >= 0) return k * values[idx];
        idx = -idx - 1;
        return k * interpolate(keys[idx - 1], values[idx - 1], keys[idx], value, values[idx]);
    }

    private static float interpolate(double x0, float y0, double x1, double x, float y1) {
        double t = (x - x0) / (x1 - x0);
        double cosFactor = (1 - Math.cos(t * Math.PI)) / 2.0;
        return (float) (y0 * (1 - cosFactor) + y1 * cosFactor);
    }
}
