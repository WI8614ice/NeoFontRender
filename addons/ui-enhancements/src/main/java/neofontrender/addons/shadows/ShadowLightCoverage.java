package neofontrender.addons.shadows;

import java.util.ArrayList;
import java.util.List;

/** Conservative light-frustum section coverage, clipped to this 1.12 world's actual terrain height. */
final class ShadowLightCoverage {
    record Required(List<ShadowTerrainCache.Key> keys, String unavailable) {
        Required { keys = List.copyOf(keys); }
    }
    private ShadowLightCoverage() {}

    static Required required(ShadowLightView view, TerrainSceneFrame.Projection origin) {
        float[][] corners = view.corners();
        double low = -origin.originY, high = 256 - origin.originY;
        List<double[]> clipped = new ArrayList<>();
        for (float[] p : corners) if (p[1] >= low && p[1] <= high) clipped.add(new double[] {p[0], p[1], p[2]});
        for (int i = 0; i < 8; i++) for (int bit : new int[] {1, 2, 4}) {
            int j = i ^ bit;
            if (j <= i) continue;
            float[] a = corners[i], b = corners[j];
            for (double plane : new double[] {low, high}) {
                if ((a[1] < plane && b[1] > plane) || (b[1] < plane && a[1] > plane)) {
                    double t = (plane - a[1]) / (b[1] - a[1]);
                    clipped.add(new double[] {a[0] + t * (b[0] - a[0]), plane, a[2] + t * (b[2] - a[2])});
                }
            }
        }
        if (clipped.isEmpty()) return new Required(List.of(), null);
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double[] p : clipped) for (int axis = 0; axis < 3; axis++) {
            double coordinate = p[axis] + (axis == 0 ? origin.originX : axis == 1 ? origin.originY : origin.originZ);
            min[axis] = Math.min(min[axis], coordinate); max[axis] = Math.max(max[axis], coordinate);
        }
        int x0 = section(min[0]), x1 = section(max[0]), z0 = section(min[2]), z1 = section(max[2]);
        int y0 = Math.max(0, section(min[1])), y1 = Math.min(15, section(max[1]));
        long candidates = Math.multiplyExact(Math.multiplyExact((long) x1 - x0 + 1, (long) z1 - z0 + 1),
                Math.max(0, y1 - y0 + 1));
        // A graphics preparation limit, never a motion budget. Extremely grazing sky paths are explicit
        // unavailable rather than performing an unbounded render-thread traversal or assuming unloaded air.
        if (candidates > 65536) return new Required(List.of(), "Light coverage exceeds bounded graphics preparation region: " + candidates);
        var result = new ArrayList<ShadowTerrainCache.Key>();
        float[] matrix = view.matrix();
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            double rx = (long) x * 16 - origin.originX, ry = (long) y * 16 - origin.originY,
                    rz = (long) z * 16 - origin.originZ;
            if (intersects(matrix, rx, ry, rz)) result.add(new ShadowTerrainCache.Key(x, y, z));
        }
        return new Required(result, null);
    }

    private static boolean intersects(float[] m, double x, double y, double z) {
        for (int row = 0; row < 3; row++) for (int sign : new int[] {-1, 1}) {
            double a = m[3] + sign * m[row], b = m[7] + sign * m[4 + row],
                    c = m[11] + sign * m[8 + row], d = m[15] + sign * m[12 + row];
            double maximum = a * (a >= 0 ? x + 16 : x) + b * (b >= 0 ? y + 16 : y)
                    + c * (c >= 0 ? z + 16 : z) + d;
            if (maximum < -1e-4) return false;
        }
        return true;
    }

    private static int section(double coordinate) {
        double value = Math.floor(coordinate / 16);
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Finite representable light section required");
        return (int) value;
    }
}
