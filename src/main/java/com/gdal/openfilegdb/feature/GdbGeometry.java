package com.gdal.openfilegdb.feature;

import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.io.ByteReader;
import com.gdal.openfilegdb.io.ByteWriter;
import com.gdal.openfilegdb.util.GdbNumbers;
import java.util.Arrays;

/**
 * 一个几何对象(内部模型),支持与 OGC WKB 互转。
 *
 * <p>对标 OGR 的 {@code OGRGeometry} 家族。本类用<b>扁平数组</b>表示几何:
 * 所有部件的所有顶点顺序存在 {@code x/y/z/m} 里,部件边界由
 * {@link #partOffsets} 标出。这与 GDAL 内部的做法一致 —— 避免为百万级顶点的
 * 点串创建百万个小对象。
 *
 * <p><b>坐标数组约定</b>
 * <ul>
 *   <li>{@code x}/{@code y} 长度 = 顶点总数</li>
 *   <li>{@code z}/{@code m}:与 XY 等长,分别仅当 {@link #hasZ()}/{@link #hasM()}
 *       为 true 时有效({@code null} 表示无该维)</li>
 *   <li>{@code partOffsets} 长度 = 部件数 + 1,第 {@code i} 个部件的顶点区间为
 *       {@code [partOffsets[i], partOffsets[i + 1])}</li>
 *   <li>{@code ringGroup}(仅面):长度 = 环数,{@code ringGroup[i]} 是第 {@code i}
 *       个环所属的多边形序号,用于把"外环 + 内环"重新组装成 Polygon /
 *       MultiPolygon。为 {@code null} 时表示所有环同属一个多边形</li>
 *   <li>{@code partShapeTypes}(仅 multipatch):每个部件的 Esri multipatch
 *       部件类型(TriangleStrip=0 / TriangleFan=1 / OuterRing=2 / InnerRing=3 /
 *       FirstRing=4 / Ring=5)</li>
 * </ul>
 *
 * <p><b>WKB 变体</b>:输出固定为小端序(NDR)+ ISO 变体({@code +1000} = Z、
 * {@code +2000} = M、{@code +3000} = ZM),与 GDAL
 * {@code OGRGeometry::exportToWkb(wkbNDR, &amp;sWkb, wkbVariantIso)} 的默认产物一致。
 * 读取时同时兼容 ISO 变体与 PostGIS 的 EWKB 标志位写法
 * ({@code 0x80000000} = Z、{@code 0x40000000} = M)。
 */
public final class GdbGeometry {

    private final GeometryType type;
    private final boolean hasZ;
    private final boolean hasM;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private final double[] m;
    private final int[] partOffsets;
    private final int[] ringGroup;
    private final int[] partShapeTypes;

    /**
     * 通用构造器(几何编解码器与工厂方法使用)。
     *
     * @param type           几何类型
     * @param hasZ           是否带 Z
     * @param hasM           是否带 M
     * @param x              X 坐标数组
     * @param y              Y 坐标数组
     * @param z              Z 坐标数组,可为 null
     * @param m              M 坐标数组,可为 null
     * @param partOffsets    部件边界(长度 = 部件数 + 1),null 表示单部件
     * @param ringGroup      环的多边形归属(仅面),可为 null
     * @param partShapeTypes multipatch 部件类型,可为 null
     */
    public GdbGeometry(GeometryType type, boolean hasZ, boolean hasM,
                       double[] x, double[] y, double[] z, double[] m,
                       int[] partOffsets, int[] ringGroup, int[] partShapeTypes) {
        this.type = type;
        this.hasZ = hasZ;
        this.hasM = hasM;
        this.x = x == null ? new double[0] : x;
        this.y = y == null ? new double[0] : y;
        this.z = z;
        this.m = m;
        this.partOffsets = partOffsets == null
                ? new int[]{0, this.x.length} : partOffsets;
        this.ringGroup = ringGroup;
        this.partShapeTypes = partShapeTypes;
    }

    // ==================================================================
    // 静态工厂
    // ==================================================================

    /**
     * 二维点。
     *
     * @param px X
     * @param py Y
     * @return 点几何
     */
    public static GdbGeometry point(double px, double py) {
        return new GdbGeometry(GeometryType.POINT, false, false,
                new double[]{px}, new double[]{py}, null, null, new int[]{0, 1}, null, null);
    }

    /**
     * 三维点。
     *
     * @param px X
     * @param py Y
     * @param pz Z
     * @return 点几何
     */
    public static GdbGeometry pointZ(double px, double py, double pz) {
        return new GdbGeometry(GeometryType.POINT, true, false,
                new double[]{px}, new double[]{py}, new double[]{pz}, null,
                new int[]{0, 1}, null, null);
    }

    /**
     * 单部件线。
     *
     * @param xs X 数组
     * @param ys Y 数组
     * @return 线几何
     */
    public static GdbGeometry lineString(double[] xs, double[] ys) {
        return new GdbGeometry(GeometryType.LINESTRING, false, false, xs, ys,
                null, null, new int[]{0, xs.length}, null, null);
    }

    /**
     * 多部件线。
     *
     * @param xs          X 数组
     * @param ys          Y 数组
     * @param partOffsets 部件边界
     * @return 多线几何
     */
    public static GdbGeometry multiLineString(double[] xs, double[] ys, int[] partOffsets) {
        return new GdbGeometry(GeometryType.MULTILINESTRING, false, false, xs, ys,
                null, null, partOffsets, null, null);
    }

    /**
     * 面几何(一个或多个多边形;{@code ringGroup} 给出环归属)。
     *
     * @param xs        X 数组
     * @param ys        Y 数组
     * @param ringOff   环边界
     * @param ringGroup 环 → 多边形归属;{@code null} 表示全部属于第一个多边形
     * @return 面几何(单个多边形时为 {@code POLYGON},否则 {@code MULTIPOLYGON})
     */
    public static GdbGeometry polygon(double[] xs, double[] ys, int[] ringOff, int[] ringGroup) {
        int groups = 1;
        if (ringGroup != null) {
            for (int g : ringGroup) {
                groups = Math.max(groups, g + 1);
            }
        }
        GeometryType t = groups > 1 ? GeometryType.MULTIPOLYGON : GeometryType.POLYGON;
        return new GdbGeometry(t, false, false, xs, ys, null, null, ringOff, ringGroup, null);
    }

    /**
     * 多点。
     *
     * @param xs X 数组
     * @param ys Y 数组
     * @return 多点几何
     */
    public static GdbGeometry multiPoint(double[] xs, double[] ys) {
        return new GdbGeometry(GeometryType.MULTIPOINT, false, false, xs, ys,
                null, null, new int[]{0, xs.length}, null, null);
    }

    /**
     * 带 Z/M 的通用几何(供编解码器使用)。
     *
     * @param type        类型
     * @param hasZ        是否带 Z
     * @param hasM        是否带 M
     * @param x           X
     * @param y           Y
     * @param z           Z
     * @param m           M
     * @param partOffsets 部件边界
     * @param ringGroup   环归属
     * @return 几何
     */
    public static GdbGeometry of(GeometryType type, boolean hasZ, boolean hasM,
                                 double[] x, double[] y, double[] z, double[] m,
                                 int[] partOffsets, int[] ringGroup) {
        return new GdbGeometry(type, hasZ, hasM, x, y, z, m, partOffsets, ringGroup, null);
    }

    // ==================================================================
    // 只读访问
    // ==================================================================

    /** 几何类型。 */
    public GeometryType getType() {
        return type;
    }

    /** 是否带 Z。 */
    public boolean hasZ() {
        return hasZ;
    }

    /** 是否带 M。 */
    public boolean hasM() {
        return hasM;
    }

    /** 顶点总数。 */
    public int numPoints() {
        return x.length;
    }

    /** 部件数。 */
    public int numParts() {
        return Math.max(0, partOffsets.length - 1);
    }

    /**
     * 第 {@code i} 个部件的起始顶点下标。
     *
     * @param i 部件序号
     * @return 起始下标
     */
    public int partStart(int i) {
        return partOffsets[i];
    }

    /**
     * 第 {@code i} 个部件的结束顶点下标(不含)。
     *
     * @param i 部件序号
     * @return 结束下标
     */
    public int partEnd(int i) {
        return partOffsets[i + 1];
    }

    /**
     * 第 {@code i} 个部件的顶点数。
     *
     * @param i 部件序号
     * @return 顶点数
     */
    public int partSize(int i) {
        return partOffsets[i + 1] - partOffsets[i];
    }

    /**
     * 第 {@code i} 个 multipatch 部件的类型。
     *
     * @param i 部件序号
     * @return 部件类型码;无 multipatch 信息时返回 -1
     */
    public int partShapeType(int i) {
        return partShapeTypes == null || i >= partShapeTypes.length ? -1 : partShapeTypes[i];
    }

    /**
     * 第 {@code i} 个环所属的多边形序号。
     *
     * @param i 环序号
     * @return 多边形序号;无分组信息时返回 0
     */
    public int ringGroupOf(int i) {
        return ringGroup == null || i >= ringGroup.length ? 0 : ringGroup[i];
    }

    /** 是否有环分组信息。 */
    public boolean hasRingGroup() {
        return ringGroup != null;
    }

    /**
     * 取顶点 X。
     *
     * @param i 顶点下标
     * @return X
     */
    public double getX(int i) {
        return x[i];
    }

    /**
     * 取顶点 Y。
     *
     * @param i 顶点下标
     * @return Y
     */
    public double getY(int i) {
        return y[i];
    }

    /**
     * 取顶点 Z。
     *
     * @param i 顶点下标
     * @return Z;无 Z 时返回 NaN
     */
    public double getZ(int i) {
        return z == null ? Double.NaN : z[i];
    }

    /**
     * 取顶点 M。
     *
     * @param i 顶点下标
     * @return M;无 M 时返回 NaN
     */
    public double getM(int i) {
        return m == null ? Double.NaN : m[i];
    }

    /** X 数组(内部引用,勿修改)。 */
    public double[] xArray() {
        return x;
    }

    /** Y 数组(内部引用,勿修改)。 */
    public double[] yArray() {
        return y;
    }

    /** Z 数组(可能为 null)。 */
    public double[] zArray() {
        return z;
    }

    /** M 数组(可能为 null)。 */
    public double[] mArray() {
        return m;
    }

    /** 部件边界数组副本。 */
    public int[] partOffsets() {
        return Arrays.copyOf(partOffsets, partOffsets.length);
    }

    /** 是否为空几何(无顶点)。 */
    public boolean isEmpty() {
        return x.length == 0;
    }

    /**
     * 计算包围盒。
     *
     * @return {@code [xmin, ymin, xmax, ymax]};空几何返回 {@code null}
     */
    public double[] envelope() {
        if (x.length == 0) {
            return null;
        }
        double xmin = x[0];
        double xmax = x[0];
        double ymin = y[0];
        double ymax = y[0];
        for (int i = 1; i < x.length; i++) {
            if (x[i] < xmin) {
                xmin = x[i];
            }
            if (x[i] > xmax) {
                xmax = x[i];
            }
            if (y[i] < ymin) {
                ymin = y[i];
            }
            if (y[i] > ymax) {
                ymax = y[i];
            }
        }
        return new double[]{xmin, ymin, xmax, ymax};
    }

    // ==================================================================
    // 空间关系(与 OGR 的 OGRGeometry::Intersects / Contains 同口径)
    // ==================================================================

    /**
     * 是否与给定矩形<b>真正相交</b>(精确到几何本身,不是只看包围盒)。
     *
     * <p>对应 OGR 的 {@code m_poFilterGeom->Intersects(poGeom)} —— GDAL 的
     * {@code SetSpatialFilterRect()} 用的就是这个语义,而不是"包围盒相交"。
     * 两者的差别在凹多边形上会露出来:一个 L 形多边形的包围盒可能搭上查询矩形,
     * 但多边形实体根本没进那块区域(实测 2024 年国土行政区划.gdb 的村行政区划层,
     * bbox 取 extent 左下四分之一时,两者差 1 条 / 6095 条)。
     *
     * <p>判定顺序(先快后慢):
     * <ol>
     *   <li>包围盒不相交 → 直接 {@code false}(这一步挡掉绝大多数要素);</li>
     *   <li>点:包围盒就是点本身 → {@code true};多点:逐点判;</li>
     *   <li>线 / 多线:逐段做线段-矩形裁剪(Liang–Barsky);</li>
     *   <li>面 / 多面:顶点落在矩形内 <b>或</b> 边与矩形相交 <b>或</b>
     *       矩形中心落在多边形内(多边形把矩形整个包住的情形)。</li>
     * </ol>
     * 面里的"中心落在多边形内"用奇偶规则数穿越次数,<b>所有环一起数</b> ——
     * 这样天然处理内环(洞):外环内 1 次 + 洞内 1 次 = 2 次 = 偶数 = 在多边形外。
     *
     * @param xmin 矩形 X 最小
     * @param ymin 矩形 Y 最小
     * @param xmax 矩形 X 最大
     * @param ymax 矩形 Y 最大
     * @return 是否相交
     */
    public boolean intersectsRect(double xmin, double ymin, double xmax, double ymax) {
        if (x.length == 0) {
            return false;
        }
        double[] env = envelope();
        if (env[2] < xmin || env[0] > xmax || env[3] < ymin || env[1] > ymax) {
            return false;
        }
        switch (type) {
            case POINT:
                return true;
            case MULTIPOINT:
                for (int i = 0; i < x.length; i++) {
                    if (inRect(x[i], y[i], xmin, ymin, xmax, ymax)) {
                        return true;
                    }
                }
                return false;
            case LINESTRING:
            case MULTILINESTRING:
                for (int p = 0; p < numParts(); p++) {
                    int start = partStart(p);
                    int n = partSize(p);
                    for (int i = start; i + 1 < start + n; i++) {
                        if (segmentHitsRect(i, i + 1, xmin, ymin, xmax, ymax)) {
                            return true;
                        }
                    }
                }
                return false;
            case POLYGON:
            case MULTIPOLYGON:
                for (int i = 0; i < x.length; i++) {
                    if (inRect(x[i], y[i], xmin, ymin, xmax, ymax)) {
                        return true;
                    }
                }
                for (int p = 0; p < numParts(); p++) {
                    int start = partStart(p);
                    int n = partSize(p);
                    if (n < 2) {
                        continue;
                    }
                    for (int i = start; i < start + n; i++) {
                        int j = (i + 1 < start + n) ? i + 1 : start;
                        if (segmentHitsRect(i, j, xmin, ymin, xmax, ymax)) {
                            return true;
                        }
                    }
                }
                // 多边形把矩形整个包住:没有一个顶点在矩形内、也没有边穿过矩形
                return containsPoint((xmin + xmax) / 2, (ymin + ymax) / 2);
            default:
                // MULTIPATCH 等本库不做实体判定的类型,退化为包围盒结果
                return true;
        }
    }

    /**
     * 点是否落在多边形内(奇偶规则,所有环一起数)。
     *
     * <p>只对 {@link GeometryType#POLYGON} / {@link GeometryType#MULTIPOLYGON} 有意义;
     * 其它类型(点 / 线)总是返回 {@code false}。
     *
     * <p>内环(洞)不需要特殊处理:外环内部穿越奇数次、洞内再穿一次变偶数次,
     * 正好得到"洞里不算在多边形内"。
     *
     * @param px 点 X
     * @param py 点 Y
     * @return 是否落在多边形实体内
     */
    public boolean containsPoint(double px, double py) {
        if (type != GeometryType.POLYGON && type != GeometryType.MULTIPOLYGON) {
            return false;
        }
        int crossings = 0;
        for (int p = 0; p < numParts(); p++) {
            int start = partStart(p);
            int n = partSize(p);
            if (n < 2) {
                continue;
            }
            for (int i = start; i < start + n; i++) {
                int j = (i + 1 < start + n) ? i + 1 : start;
                double yi = y[i];
                double yj = y[j];
                if ((yi > py) == (yj > py)) {
                    continue;
                }
                double xCross = x[i] + (py - yi) / (yj - yi) * (x[j] - x[i]);
                if (px < xCross) {
                    crossings++;
                }
            }
        }
        return (crossings & 1) == 1;
    }

    /** 点是否在矩形内(闭区间)。 */
    private static boolean inRect(double px, double py,
                                  double xmin, double ymin, double xmax, double ymax) {
        return px >= xmin && px <= xmax && py >= ymin && py <= ymax;
    }

    /** 线段是否与矩形有公共点(Liang–Barsky 参数裁剪)。 */
    private boolean segmentHitsRect(int i, int j,
                                    double xmin, double ymin, double xmax, double ymax) {
        double x0 = x[i];
        double y0 = y[i];
        double x1 = x[j];
        double y1 = y[j];
        double dx = x1 - x0;
        double dy = y1 - y0;
        double[] t = {0.0, 1.0};
        return clip(-dx, x0 - xmin, t) && clip(dx, xmax - x0, t)
                && clip(-dy, y0 - ymin, t) && clip(dy, ymax - y0, t);
    }

    /** Liang–Barsky 的单条约束 {@code p·t <= q};维护 {@code t[0] <= t[1]} 的可行区间。 */
    private static boolean clip(double p, double q, double[] t) {
        if (p == 0.0) {
            return q >= 0.0;
        }
        double r = q / p;
        if (p < 0.0) {
            if (r > t[1]) {
                return false;
            }
            if (r > t[0]) {
                t[0] = r;
            }
        } else {
            if (r < t[0]) {
                return false;
            }
            if (r < t[1]) {
                t[1] = r;
            }
        }
        return true;
    }

    /** 部件的多边形归属数(仅面有意义)。 */
    private int polygonGroupCount() {
        int groups = 1;
        for (int p = 0; p < numParts(); p++) {
            groups = Math.max(groups, ringGroupOf(p) + 1);
        }
        return groups;
    }

    // ==================================================================
    // WKB 输出(小端 NDR + ISO 变体)
    // ==================================================================

    /**
     * 导出为 WKB(小端 NDR + ISO 变体)。
     *
     * @return WKB 字节
     */
    public byte[] toWkb() {
        ByteWriter w = new ByteWriter(64 + x.length * 24);
        w.u8(1);
        writeBody(w);
        return w.toByteArray();
    }

    /** 写入除字节序标志之外的全部内容。 */
    private void writeBody(ByteWriter w) {
        int code = type.wkbCode();
        if (hasZ && hasM) {
            code += 3000;
        } else if (hasZ) {
            code += 1000;
        } else if (hasM) {
            code += 2000;
        }
        w.u32(code);
        switch (type) {
            case POINT:
                writeCoord(w, 0);
                break;
            case LINESTRING:
                writeLine(w, 0, numPoints());
                break;
            case POLYGON:
                w.u32(numParts());
                for (int p = 0; p < numParts(); p++) {
                    writeLine(w, partStart(p), partEnd(p));
                }
                break;
            case MULTIPOLYGON: {
                int groups = polygonGroupCount();
                w.u32(groups);
                for (int g = 0; g < groups; g++) {
                    int rings = 0;
                    for (int p = 0; p < numParts(); p++) {
                        if (ringGroupOf(p) == g) {
                            rings++;
                        }
                    }
                    w.u32(rings);
                    for (int p = 0; p < numParts(); p++) {
                        if (ringGroupOf(p) == g) {
                            writeLine(w, partStart(p), partEnd(p));
                        }
                    }
                }
                break;
            }
            case MULTIPOINT: {
                // FileGDB 里 MULTIPOINT 是"一个部件含 N 个点";WKB 要求拆成 N 个点
                w.u32(numPoints());
                for (int i = 0; i < numPoints(); i++) {
                    w.u8(1);
                    int pc = 1;
                    if (hasZ && hasM) {
                        pc += 3000;
                    } else if (hasZ) {
                        pc += 1000;
                    } else if (hasM) {
                        pc += 2000;
                    }
                    w.u32(pc);
                    writeCoord(w, i);
                }
                break;
            }
            case MULTILINESTRING:
            case MULTIPATCH: {
                w.u32(numParts());
                int pc = type == GeometryType.MULTIPATCH ? 32 : 2;
                if (hasZ && hasM) {
                    pc += 3000;
                } else if (hasZ) {
                    pc += 1000;
                } else if (hasM) {
                    pc += 2000;
                }
                for (int p = 0; p < numParts(); p++) {
                    w.u8(1);
                    w.u32(pc);
                    writeLine(w, partStart(p), partEnd(p));
                }
                break;
            }
            default:
                throw new GdbFormatException("无法导出为 WKB 的几何类型: " + type);
        }
    }

    private void writeLine(ByteWriter w, int start, int end) {
        int n = end - start;
        w.u32(n);
        for (int i = start; i < end; i++) {
            writeCoord(w, i);
        }
    }

    private void writeCoord(ByteWriter w, int i) {
        w.f64(x[i]);
        w.f64(y[i]);
        if (hasZ) {
            w.f64(z[i]);
        }
        if (hasM) {
            w.f64(m[i]);
        }
    }

    // ==================================================================
    // WKB 解析
    // ==================================================================

    /**
     * 从 WKB 解析几何(兼容小端/大端、ISO 变体与 EWKB 标志位)。
     *
     * @param wkb WKB 字节
     * @return 几何对象
     * @throws GdbFormatException 结构非法
     */
    public static GdbGeometry fromWkb(byte[] wkb) {
        return readGeometry(new ByteReader(wkb));
    }

    private static GdbGeometry readGeometry(ByteReader r) {
        int order = r.u8();
        if (order != 0 && order != 1) {
            throw new GdbFormatException("WKB 字节序标志非法: " + order);
        }
        boolean le = order == 1;
        long rawType = readU32(r, le);
        boolean hasZ = (rawType & 0x80000000L) != 0;
        boolean hasM = (rawType & 0x40000000L) != 0;
        int code = (int) (rawType & 0x0FFFFFFFL);
        if (code >= 3000) {
            hasZ = true;
            hasM = true;
            code -= 3000;
        } else if (code >= 2000) {
            hasM = true;
            code -= 2000;
        } else if (code >= 1000) {
            hasZ = true;
            code -= 1000;
        }
        GeometryType t = GeometryType.fromWkbCode(code);
        if (t == GeometryType.UNKNOWN) {
            throw new GdbFormatException("不支持的 WKB 几何类型码: " + code);
        }
        return readBody(r, t, hasZ, hasM, le);
    }

    private static GdbGeometry readBody(ByteReader r, GeometryType t,
                                        boolean hasZ, boolean hasM, boolean le) {
        switch (t) {
            case POINT: {
                double[] c = readCoord(r, hasZ, hasM, le);
                return new GdbGeometry(GeometryType.POINT, hasZ, hasM,
                        new double[]{c[0]}, new double[]{c[1]},
                        hasZ ? new double[]{c[2]} : null,
                        hasM ? new double[]{c[hasZ ? 3 : 2]} : null,
                        new int[]{0, 1}, null, null);
            }
            case LINESTRING: {
                int n = (int) readU32(r, le);
                double[] xs = new double[n];
                double[] ys = new double[n];
                double[] zs = hasZ ? new double[n] : null;
                double[] ms = hasM ? new double[n] : null;
                double[] buf = new double[4];
                for (int i = 0; i < n; i++) {
                    readCoordInto(r, hasZ, hasM, le, buf);
                    xs[i] = buf[0];
                    ys[i] = buf[1];
                    if (hasZ) {
                        zs[i] = buf[2];
                    }
                    if (hasM) {
                        ms[i] = buf[hasZ ? 3 : 2];
                    }
                }
                return new GdbGeometry(GeometryType.LINESTRING, hasZ, hasM, xs, ys, zs, ms,
                        new int[]{0, n}, null, null);
            }
            case POLYGON:
            case MULTIPOLYGON: {
                PointAccum acc = new PointAccum();
                int groups = t == GeometryType.POLYGON ? 1 : (int) readU32(r, le);
                for (int g = 0; g < groups; g++) {
                    int rings = (int) readU32(r, le);
                    for (int k = 0; k < rings; k++) {
                        int n = (int) readU32(r, le);
                        for (int i = 0; i < n; i++) {
                            double[] c = readCoord(r, hasZ, hasM, le);
                            acc.add(c);
                        }
                        acc.endPart(g);
                    }
                }
                return new GdbGeometry(t, hasZ, hasM, acc.xs(), acc.ys(), null, null,
                        acc.offsets(), acc.groups(), null);
            }
            case MULTIPOINT: {
                int n = (int) readU32(r, le);
                double[] xs = new double[n];
                double[] ys = new double[n];
                double[] zs = hasZ ? new double[n] : null;
                double[] ms = hasM ? new double[n] : null;
                for (int i = 0; i < n; i++) {
                    boolean le2 = r.u8() == 1;
                    long tp = readU32(r, le2);
                    boolean z2 = hasZ || (tp & 0x80000000L) != 0 || (tp & 0x0FFFFFFFL) >= 1000;
                    boolean m2 = hasM || (tp & 0x40000000L) != 0 || (tp & 0x0FFFFFFFL) >= 2000;
                    double[] c = readCoord(r, z2, m2, le2);
                    xs[i] = c[0];
                    ys[i] = c[1];
                    if (hasZ) {
                        zs[i] = c[2];
                    }
                    if (hasM) {
                        ms[i] = c[z2 ? 3 : 2];
                    }
                }
                return new GdbGeometry(GeometryType.MULTIPOINT, hasZ, hasM, xs, ys, zs, ms,
                        new int[]{0, n}, null, null);
            }
            case MULTILINESTRING:
            case MULTIPATCH: {
                PointAccum acc = new PointAccum();
                int parts = (int) readU32(r, le);
                for (int p = 0; p < parts; p++) {
                    boolean le2 = r.u8() == 1;
                    readU32(r, le2);
                    int n = (int) readU32(r, le2);
                    for (int i = 0; i < n; i++) {
                        acc.add(readCoord(r, hasZ, hasM, le2));
                    }
                    acc.endPart(0);
                }
                return new GdbGeometry(t, hasZ, hasM, acc.xs(), acc.ys(), null, null,
                        acc.offsets(), null, null);
            }
            default:
                throw new GdbFormatException("不支持的 WKB 几何类型: " + t);
        }
    }

    private static long readU32(ByteReader r, boolean le) {
        long v = r.u32();
        return le ? v : (Integer.reverseBytes((int) v) & 0xFFFFFFFFL);
    }

    private static double[] readCoord(ByteReader r, boolean hasZ, boolean hasM, boolean le) {
        double[] c = new double[2 + (hasZ ? 1 : 0) + (hasM ? 1 : 0)];
        readCoordInto(r, hasZ, hasM, le, c);
        return c;
    }

    private static void readCoordInto(ByteReader r, boolean hasZ, boolean hasM,
                                      boolean le, double[] out) {
        out[0] = readF64(r, le);
        out[1] = readF64(r, le);
        if (hasZ) {
            out[2] = readF64(r, le);
        }
        if (hasM) {
            out[hasZ ? 3 : 2] = readF64(r, le);
        }
    }

    private static double readF64(ByteReader r, boolean le) {
        if (le) {
            return r.f64();
        }
        return Double.longBitsToDouble(Long.reverseBytes(r.i64()));
    }

    /** 解析过程中累积顶点与部件边界。 */
    private static final class PointAccum {
        private double[] xs = new double[16];
        private double[] ys = new double[16];
        private int n;
        private int[] off = new int[8];
        private int[] grp = new int[8];
        private int parts;

        PointAccum() {
            off[0] = 0;
        }

        void add(double[] c) {
            if (n == xs.length) {
                xs = Arrays.copyOf(xs, n * 2);
                ys = Arrays.copyOf(ys, n * 2);
            }
            xs[n] = c[0];
            ys[n] = c[1];
            n++;
        }

        void endPart(int group) {
            parts++;
            if (parts + 1 > off.length) {
                off = Arrays.copyOf(off, off.length * 2);
                grp = Arrays.copyOf(grp, grp.length * 2);
            }
            off[parts] = n;
            grp[parts - 1] = group;
        }

        double[] xs() {
            return Arrays.copyOf(xs, n);
        }

        double[] ys() {
            return Arrays.copyOf(ys, n);
        }

        int[] offsets() {
            return Arrays.copyOf(off, parts + 1);
        }

        int[] groups() {
            return Arrays.copyOf(grp, parts);
        }
    }

    // ==================================================================
    // WKT 输出(仅供日志 / Demo 打印,不作为序列化格式)
    // ==================================================================

    /**
     * 输出 WKT 文本(仅供显示,不保证与 OGR 的 WKT 完全等价)。
     *
     * @return WKT 字符串
     */
    public String toWkt() {
        if (isEmpty()) {
            return type.ogrName().toUpperCase() + " EMPTY";
        }
        StringBuilder sb = new StringBuilder();
        String suffix = hasZ && hasM ? " ZM" : hasZ ? " Z" : hasM ? " M" : "";
        switch (type) {
            case POINT:
                sb.append("POINT").append(suffix).append(" (").append(coord(0)).append(')');
                break;
            case MULTIPOINT:
                sb.append("MULTIPOINT").append(suffix).append(" (");
                for (int i = 0; i < numPoints(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(coord(i)).append(')');
                }
                sb.append(')');
                break;
            case LINESTRING:
                sb.append("LINESTRING").append(suffix)
                        .append(" (").append(ring(0, numPoints())).append(')');
                break;
            case MULTILINESTRING:
                sb.append("MULTILINESTRING").append(suffix).append(" (");
                for (int p = 0; p < numParts(); p++) {
                    if (p > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(ring(partStart(p), partEnd(p))).append(')');
                }
                sb.append(')');
                break;
            case POLYGON:
            case MULTIPOLYGON: {
                // OGC WKT 要求多边形的环显式闭合(首尾点重复),而本库的内部表示
                // 不存闭合点(盘上形态才闭合,见 FileGdbGeometryCodec)。
                // 出处:pyopenfilegdb/_esri_geometry.py 的 ``seq_of(..., close=True)``,
                // 注释写着"OGC WKT 要求环闭合;内存里的环不存闭合点"。
                int groups = polygonGroupCount();
                // 只有一个外环时输出 POLYGON,多于一个才是 MULTIPOLYGON(与参考实现一致)。
                sb.append(groups > 1 ? "MULTIPOLYGON" : "POLYGON").append(suffix)
                        .append(" (");
                for (int g = 0; g < groups; g++) {
                    if (g > 0) {
                        sb.append(", ");
                    }
                    sb.append('(');
                    boolean first = true;
                    for (int p = 0; p < numParts(); p++) {
                        if (ringGroupOf(p) != g) {
                            continue;
                        }
                        if (!first) {
                            sb.append(", ");
                        }
                        sb.append('(')
                                .append(ringClosed(partStart(p), partEnd(p)))
                                .append(')');
                        first = false;
                    }
                    sb.append(')');
                }
                sb.append(')');
                break;
            }
            default:
                sb.append(type.ogrName().toUpperCase()).append(" (points=")
                        .append(numPoints()).append(", parts=").append(numParts()).append(')');
        }
        return sb.toString();
    }

    private String coord(int i) {
        StringBuilder sb = new StringBuilder();
        sb.append(fmt(x[i])).append(' ').append(fmt(y[i]));
        if (hasZ) {
            sb.append(' ').append(fmt(z[i]));
        }
        if (hasM) {
            sb.append(' ').append(fmt(m[i]));
        }
        return sb.toString();
    }

    private String ring(int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append(", ");
            }
            sb.append(coord(i));
        }
        return sb.toString();
    }

    /**
     * 与 {@link #ring(int, int)} 相同,但保证末端重复首点(仅用于 OGC WKT 的多边形环)。
     *
     * @param start 环的起始顶点下标
     * @param end   环的结束顶点下标(不含)
     * @return 闭合环的点序列
     */
    private String ringClosed(int start, int end) {
        String body = ring(start, end);
        if (end - start >= 2
                && x[start] == x[end - 1] && y[start] == y[end - 1]) {
            // 已经闭合,不再补第二个点(与 _close_parts 的判据一致)
            return body;
        }
        return body + ", " + coord(start);
    }

    /**
     * 坐标数值的文本化。
     *
     * <p>绝不能退化成科学计数法 —— WKT 里出现 {@code 4.5E7} / {@code 1.23E-5} 既难读,
     * 也没法跟 ArcGIS / GDAL 的文本输出对照。统一走 {@link GdbNumbers#plain(double)}:
     * 整值不带小数点,非整值给普通十进制。
     *
     * @param v 坐标分量
     * @return 普通十进制文本
     */
    private static String fmt(double v) {
        return GdbNumbers.plain(v);
    }

    @Override
    public String toString() {
        return "GdbGeometry[" + toWkt() + "]";
    }
}
