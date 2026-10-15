package com.gdal.openfilegdb.feature;

import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.io.ByteWriter;
import com.gdal.openfilegdb.io.VarInt;
import java.util.Arrays;

/**
 * Esri FileGDB 私有几何二进制编解码。
 *
 * <p>本类是 {@code E:/code/pyopenfilegdb/pyopenfilegdb/_esri_geometry.py} 的
 * 逐行对照 Java 移植(该 Python 版已用真实 ArcGIS 产出的 .gdb 逐字节实测验证)。
 *
 * <p>这不是 WKB,也不是 shapefile 的 shape blob,而是 FileGDB 自己的一套
 * <b>变长整数 + 增量编码</b> 格式。三个关键点:
 *
 * <ol>
 *   <li>坐标是 <b>量化整数</b>,不是裸 double。还原公式:
 *       <pre>
 *   数组形式(线/面/多点):  X = dx / xyScale + xOrigin
 *   单点形式(POINT):       X = (x - 1) / xyScale + xOrigin
 *       </pre>
 *       {@code xyScale}/{@code xOrigin} 来自几何字段描述区(ArcGIS 默认 1e9 / -400)。
 *       单点那个 {@code -1} 是 GDAL 的行为(见 filegdbtable.cpp 里 POINT 分支),
 *       数组形式则没有 —— 这个不对称必须照抄,否则单点会整体偏移一个量化单位。</li>
 *   <li>点序列用 <b>增量(delta)</b> 编码,且增量跨 part 连续累积;Z、M 各自
 *       独立的累加器(从 0 开始),放在所有 XY 之后。</li>
 *   <li>几何类型本身也是一个 varuint,高位带标志:
 *       <pre>
 *   0x80000000  Z
 *   0x40000000  M
 *   0x20000000  曲线段(圆弧/贝塞尔/椭圆),本项目仅识别不解码
 *   低 8 位       shape type
 *       </pre></li>
 * </ol>
 *
 * <p>GDAL 参考位置:
 * <ul>
 *   <li>{@code filegdbtable.cpp::FileGDBOGRGeometryConverterImpl::GetAsGeometry}</li>
 *   <li>{@code filegdbtable.cpp::ReadXYArray / ReadZArray / ReadMArray}</li>
 *   <li>{@code filegdbtable.cpp::ReadPartDefs}</li>
 *   <li>{@code filegdbtable.cpp::ReadVarIntAndAddNoCheck}</li>
 *   <li>{@code filegdbtable.cpp::EncodeGeometry / EncodeEnvelope}(写侧)</li>
 * </ul>
 *
 * <p>⚠️ <b>delta-varint 的具体变体</b>(首字节 bit6 是符号位、低 6 位是数据;
 * 首点全量参与累加;非 zigzag;小端无关,逐字节读出)完全以 Python 参考实现为准,
 * 不是按公开 ESRI 文档猜的。
 */
public final class FileGdbGeometryCodec {

    // ==================================================================
    // 常量(对应 GDAL filegdbtable.cpp 顶部的常量)
    // ==================================================================

    /** 几何类型 varuint 的 Z 标志位。 */
    private static final long EXT_SHAPE_Z_FLAG = 0x80000000L;
    /** 几何类型 varuint 的 M 标志位。 */
    private static final long EXT_SHAPE_M_FLAG = 0x40000000L;
    /** 几何类型 varuint 的曲线段标志位(仅识别,不解码)。 */
    private static final long EXT_SHAPE_CURVE_FLAG = 0x20000000L;

    /**
     * M 数组"缺失"的标记字节。GDAL 的注释:
     * "It seems that absence of M is marked with a single byte with value 66"。
     */
    private static final int NO_M_MARKER = 66;

    /**
     * delta-varint 续字节的 shift 上限,与 {@code _esri_geometry._MAX_SHIFT} 一致。
     *
     * <p>shift 取值是 6, 13, 20, ...(第 k 个续字节用 6 + 7*(k-1))。取 57 意味着
     * 最多接受 8 个续字节:第 8 个用 shift 55,贡献的最高位是 61,稳稳落在 64 位
     * 累加器里。第 9 个续字节的 shift 是 62,贡献能摸到 68 位,故判非法。
     */
    private static final int MAX_SHIFT = 57;

    /**
     * {@link #peekEnvelope} 只取包围盒时需要读的 blob 前缀字节数。
     *
     * <p>这是<b>性能参数不是正确性参数</b>。Java 版一次性拿到完整 blob,该常量仅
     * 作为文档保留(对应 {@code _esri_geometry.GEOM_ENVELOPE_PREFIX})。
     */
    public static final int GEOM_ENVELOPE_PREFIX = 256;

    private FileGdbGeometryCodec() {
    }

    // ==================================================================
    // 量化参数(对应 _esri_geometry._Quantizer)
    // ==================================================================

    /**
     * 把几何字段描述区里的 origin/scale 打包,供坐标还原/量化使用。
     *
     * <p>对应 GDAL {@code FileGDBGeomField} 的 GetXYScale/GetXOrigin/...
     * {@link #sanitizeScale} 处理 scale 为 0 的情形(GDAL 里会退化成 1)。
     */
    private static final class Quantizer {
        final double xOrigin;
        final double yOrigin;
        final double xyScale;
        final double zOrigin;
        final double zScale;
        final double mOrigin;
        final double mScale;

        Quantizer(GdbGeomField geomField) {
            if (geomField == null) {
                this.xOrigin = 0.0;
                this.yOrigin = 0.0;
                this.xyScale = 1.0;
                this.zOrigin = 0.0;
                this.mOrigin = 0.0;
                this.zScale = 1.0;
                this.mScale = 1.0;
            } else {
                this.xOrigin = geomField.getXOrigin();
                this.yOrigin = geomField.getYOrigin();
                this.xyScale = sanitizeScale(geomField.getXyScale());
                this.zOrigin = geomField.getZOrigin();
                this.zScale = sanitizeScale(geomField.getZScale());
                this.mOrigin = geomField.getMOrigin();
                this.mScale = sanitizeScale(geomField.getMScale());
            }
        }
    }

    /** 对应 GDAL {@code SanitizeScale}:scale 为 0/非数时退化为 1。 */
    private static double sanitizeScale(double s) {
        if (s == 0 || Double.isNaN(s)) {
            return 1.0;
        }
        return s;
    }

    // ==================================================================
    // shape type 分族判定(对应 _constants.ShapeType._XXX_ALL)
    // ==================================================================

    private static boolean isPointAll(int shp) {
        return shp == ShapeType.POINT || shp == ShapeType.POINTZ
                || shp == ShapeType.POINTZM || shp == ShapeType.POINTM
                || shp == ShapeType.GENERAL_POINT;
    }

    private static boolean isMultiPointAll(int shp) {
        return shp == ShapeType.MULTIPOINT || shp == ShapeType.MULTIPOINTZ
                || shp == ShapeType.MULTIPOINTZM || shp == ShapeType.MULTIPOINTM
                || shp == ShapeType.GENERAL_MULTIPOINT;
    }

    private static boolean isPolylineAll(int shp) {
        return shp == ShapeType.POLYLINE || shp == ShapeType.POLYLINEZ
                || shp == ShapeType.POLYLINEZM || shp == ShapeType.POLYLINEM
                || shp == ShapeType.GENERAL_POLYLINE;
    }

    private static boolean isPolygonAll(int shp) {
        return shp == ShapeType.POLYGON || shp == ShapeType.POLYGONZ
                || shp == ShapeType.POLYGONZM || shp == ShapeType.POLYGONM
                || shp == ShapeType.GENERAL_POLYGON;
    }

    private static boolean isMultiPatchAll(int shp) {
        return shp == ShapeType.MULTIPATCH || shp == ShapeType.MULTIPATCHM
                || shp == ShapeType.GENERAL_MULTIPATCH;
    }

    // ==================================================================
    // 解码
    // ==================================================================

    /**
     * FileGDB 原生几何 blob -> {@link GdbGeometry};blob 为空/长度不足返回 {@code null}。
     *
     * <p>分派逻辑完全照抄 GDAL {@code GetAsGeometry}({@code filegdbtable.cpp} 的
     * {@code switch ((nGeomType & 0xff))}):每个"纯 Z"和"ZM"档都落到同一段代码,
     * 只在 case 里把 bHasZ/bHasM 置真。
     *
     * <p>⚠️ {@code hasZ}/{@code hasM} 形参与 Python 参考实现一样<b>不被使用</b> ——
     * 维度完全由 blob 里的 geom_type 标志位与 shape type 决定;保留这两个形参只为
     * 与 Python 版 API 对齐。
     *
     * @param blob      几何 blob
     * @param geomField 提供 origin/scale 量化参数(可为 null)
     * @param hasZ      是否带 Z(未使用,见上)
     * @param hasM      是否带 M(未使用,见上)
     * @return 几何对象;空/非法头部返回 null
     * @throws GdbFormatException 结构非法(截断、未知 shape type)
     */
    public static GdbGeometry decode(byte[] blob, GdbGeomField geomField,
                                     boolean hasZ, boolean hasM) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        Quantizer q = new Quantizer(geomField);
        Cursor cur = new Cursor(blob, 0);

        // 对应GDAL filegdbtable.cpp::GetAsGeometry() 读 nGeomType
        long geomType;
        try {
            geomType = cur.varUInt();
        } catch (RuntimeException ex) {
            // 与 Python 一致:头部就读不出来 —— 交给上层当"没有几何"
            return null;
        }

        int shp = (int) (geomType & 0xFF);
        boolean bHasZ = (geomType & EXT_SHAPE_Z_FLAG) != 0 || ShapeType.hasZ(shp);
        boolean bHasM = (geomType & EXT_SHAPE_M_FLAG) != 0 || ShapeType.hasM(shp);
        boolean isCurve = (geomType & EXT_SHAPE_CURVE_FLAG) != 0;

        if (shp == ShapeType.NULL) {
            return null;
        }

        if (isPointAll(shp)) {
            return decodePoint(cur, q, bHasZ, bHasM);
        }
        if (isMultiPointAll(shp)) {
            return decodeMultiPoint(cur, q, bHasZ, bHasM);
        }
        if (isPolylineAll(shp)) {
            return decodeParts(cur, q, bHasZ, bHasM, isCurve, PartsKind.POLYLINE);
        }
        if (isPolygonAll(shp)) {
            return decodeParts(cur, q, bHasZ, bHasM, isCurve, PartsKind.POLYGON);
        }
        if (isMultiPatchAll(shp)) {
            // ⚠️ Python 参考实现未覆盖 MULTIPATCH;这里按 _decode_parts 文档给出的
            // 布局(在 nPoints 之后多一个被跳过的 varuint)尽力还原,见方法注释。
            return decodeParts(cur, q, bHasZ, bHasM, isCurve, PartsKind.MULTIPATCH);
        }

        throw new GdbFormatException(
                "未知的 FileGDB shape type: " + shp + " (geom_type=0x"
                        + Long.toHexString(geomType) + ")");
    }

    /**
     * POINT:varuint x, varuint y[, z][, m]。
     *
     * <p>量化值 0 表示"该维为空",还原成 NaN。注意这里带 {@code -1}。
     * 对应GDAL filegdbtable.cpp::GetAsGeometry() 的 SHPT_POINT 分支。
     */
    private static GdbGeometry decodePoint(Cursor cur, Quantizer q,
                                           boolean hasZ, boolean hasM) {
        long x = cur.varUInt();
        long y = cur.varUInt();

        double dfx = x == 0 ? Double.NaN : (x - 1) / q.xyScale + q.xOrigin;
        double dfy = y == 0 ? Double.NaN : (y - 1) / q.xyScale + q.yOrigin;

        double[] zs = hasZ ? new double[1] : null;
        double[] ms = hasM ? new double[1] : null;
        if (hasZ) {
            long z = cur.varUInt();
            zs[0] = z == 0 ? Double.NaN : (z - 1) / q.zScale + q.zOrigin;
        }
        if (hasM) {
            long m = cur.varUInt();
            ms[0] = m == 0 ? Double.NaN : (m - 1) / q.mScale + q.mOrigin;
        }

        return new GdbGeometry(GeometryType.POINT, hasZ, hasM,
                new double[]{dfx}, new double[]{dfy}, zs, ms,
                new int[]{0, 1}, null, null);
    }

    /**
     * MULTIPOINT:nPoints | 4 个 varuint(包围盒) | XY 数组 | [Z] | [M]。
     *
     * <p>对应GDAL filegdbtable.cpp::GetAsGeometry() 的 MULTIPOINT 分支、
     * ReadXYArray / ReadZArray / ReadMArray。
     */
    private static GdbGeometry decodeMultiPoint(Cursor cur, Quantizer q,
                                                boolean hasZ, boolean hasM) {
        long nPointsL = cur.varUInt();
        int nPoints = (int) nPointsL;
        if (nPoints == 0) {
            return new GdbGeometry(GeometryType.MULTIPOINT, hasZ, hasM,
                    new double[0], new double[0], null, null,
                    new int[]{0}, null, null);
        }

        cur.skipVarUInt(4); // 包围盒(本函数不解码它,只跳过)

        double[] xs = new double[nPoints];
        double[] ys = new double[nPoints];
        long dx = 0;
        long dy = 0;
        for (int i = 0; i < nPoints; i++) {
            dx = cur.delta(dx);
            dy = cur.delta(dy);
            xs[i] = dx / q.xyScale + q.xOrigin;
            ys[i] = dy / q.xyScale + q.yOrigin;
        }

        double[] zs = null;
        if (hasZ) {
            zs = new double[nPoints];
            long dz = 0;
            for (int i = 0; i < nPoints; i++) {
                dz = cur.delta(dz);
                zs[i] = dz / q.zScale + q.zOrigin;
            }
        }

        // M 可能整体缺失:GDAL 的做法是只有当剩余字节数 >= 点数时才尝试解析。
        double[] ms = null;
        if (hasM) {
            if (cur.pos + nPoints <= cur.blob.length) {
                ms = new double[nPoints];
                long dm = 0;
                for (int i = 0; i < nPoints; i++) {
                    dm = cur.delta(dm);
                    ms[i] = dm / q.mScale + q.mOrigin;
                }
            } else {
                // 声明带 M 但数据缺失:填 NaN,保证 toWkb() 不会空指针
                ms = new double[nPoints];
                Arrays.fill(ms, Double.NaN);
            }
        }

        return new GdbGeometry(GeometryType.MULTIPOINT, hasZ, hasM,
                xs, ys, zs, ms, new int[]{0, nPoints}, null, null);
    }

    /** 多部件几何的种类(POLYLINE / POLYGON / MULTIPATCH 共用同一段解码)。 */
    private enum PartsKind {
        POLYLINE,
        POLYGON,
        MULTIPATCH
    }

    /**
     * POLYLINE / POLYGON / MULTIPATCH。
     *
     * <p>布局(对应 {@code _decode_parts} docstring 与 GDAL
     * filegdbtable.cpp::ReadPartDefs):
     * <pre>
     *   varuint nPoints
     *   [如果 multipatch: varuint 跳过]     ← ⚠️ Python 参考实现未实现该档
     *   varuint nParts
     *   [如果有曲线描述: varuint nCurves]
     *   [4 个 varuint 包围盒]
     *   varuint * (nParts-1)     每个 part 的点数(最后一个不算)
     *   XY 增量数组(所有 part 的点首尾相接)
     *   [Z 增量数组]
     *   [M 增量数组]
     * </pre>
     */
    private static GdbGeometry decodeParts(Cursor cur, Quantizer q, boolean hasZ,
                                           boolean hasM, boolean isCurve,
                                           PartsKind kind) {
        long nPointsL = cur.varUInt();
        int nPoints = (int) nPointsL;

        GeometryType emptyType = kind == PartsKind.POLYGON ? GeometryType.POLYGON
                : kind == PartsKind.MULTIPATCH ? GeometryType.MULTIPATCH
                : GeometryType.LINESTRING;

        if (nPoints == 0) {
            return new GdbGeometry(emptyType, hasZ, hasM,
                    new double[0], new double[0], null, null,
                    new int[]{0}, null, null);
        }

        if (kind == PartsKind.MULTIPATCH) {
            // 见 _decode_parts docstring 的 "[如果 multipatch: varuint 跳过]"
            cur.skipVarUInt(1);
        }

        long nPartsL = cur.varUInt();
        int nParts = (int) nPartsL;

        if (isCurve) {
            // 圆弧/贝塞尔/椭圆段:本实现只解析直线段部分
            // (见模块 docstring:曲线标志仅识别不解码)
            cur.varUInt();
        }

        if (nParts == 0) {
            return new GdbGeometry(emptyType, hasZ, hasM,
                    new double[0], new double[0], null, null,
                    new int[]{0}, null, null);
        }

        cur.skipVarUInt(4); // 包围盒

        // 每个 part 的点数;最后一个由总数减去前面之和得到
        int[] counts = new int[nParts];
        long total = 0;
        for (int p = 0; p + 1 < nParts; p++) {
            long cnt = cur.varUInt();
            counts[p] = (int) cnt;
            total += cnt;
        }
        counts[nParts - 1] = (int) (nPoints - total);

        // ---- XY 增量数组:增量累加器跨 part 连续 ----
        double[] xs = new double[nPoints];
        double[] ys = new double[nPoints];
        long dx = 0;
        long dy = 0;
        int w = 0;
        for (int p = 0; p < nParts; p++) {
            for (int k = 0; k < counts[p]; k++) {
                dx = cur.delta(dx);
                dy = cur.delta(dy);
                xs[w] = dx / q.xyScale + q.xOrigin;
                ys[w] = dy / q.xyScale + q.yOrigin;
                w++;
            }
        }

        double[] zs = null;
        if (hasZ) {
            zs = new double[nPoints];
            long dz = 0;
            int wz = 0;
            for (int p = 0; p < nParts; p++) {
                for (int k = 0; k < counts[p]; k++) {
                    dz = cur.delta(dz);
                    zs[wz] = dz / q.zScale + q.zOrigin;
                    wz++;
                }
            }
        }

        // M 可能整体缺失,此时数据里只有一个标记字节(值 66)。
        // GDAL 的做法:只有当剩余字节数 >= 点数时才尝试解析。
        double[] ms = null;
        if (hasM) {
            boolean readM;
            if (cur.pos < cur.blob.length && (cur.blob[cur.pos] & 0xFF) == NO_M_MARKER) {
                readM = false;
            } else {
                readM = cur.pos + nPoints <= cur.blob.length;
            }
            if (readM) {
                ms = new double[nPoints];
                long dm = 0;
                int wm = 0;
                for (int p = 0; p < nParts; p++) {
                    for (int k = 0; k < counts[p]; k++) {
                        dm = cur.delta(dm);
                        ms[wm] = dm / q.mScale + q.mOrigin;
                        wm++;
                    }
                }
            } else {
                // 声明带 M 但数据缺失:填 NaN,保证 toWkb() 不会空指针
                ms = new double[nPoints];
                Arrays.fill(ms, Double.NaN);
            }
        }

        int[] rawOffsets = new int[nParts + 1];
        for (int p = 0; p < nParts; p++) {
            rawOffsets[p + 1] = rawOffsets[p] + counts[p];
        }

        if (kind == PartsKind.POLYGON) {
            // 盘上环是闭合的(首尾点相同),而本工程内部约定环不闭合,统一削掉
            // (对应 _strip_part_closures_flat)。
            int kept = 0;
            int[] newOffsets = new int[nParts + 1];
            for (int p = 0; p < nParts; p++) {
                int s = rawOffsets[p];
                int e = rawOffsets[p + 1];
                boolean closed = e - s > 1 && xs[s] == xs[e - 1] && ys[s] == ys[e - 1];
                int keep = closed ? (e - s - 1) : (e - s);
                newOffsets[p] = kept;
                kept += keep;
            }
            newOffsets[nParts] = kept;

            double[] nxs = new double[kept];
            double[] nys = new double[kept];
            double[] nzs = zs != null ? new double[kept] : null;
            double[] nms = ms != null ? new double[kept] : null;
            for (int p = 0; p < nParts; p++) {
                int s = rawOffsets[p];
                int keep = newOffsets[p + 1] - newOffsets[p];
                int dst = newOffsets[p];
                System.arraycopy(xs, s, nxs, dst, keep);
                System.arraycopy(ys, s, nys, dst, keep);
                if (nzs != null) {
                    System.arraycopy(zs, s, nzs, dst, keep);
                }
                if (nms != null) {
                    System.arraycopy(ms, s, nms, dst, keep);
                }
            }

            int[] ringGroup = organizePolygons(nxs, nys, newOffsets);
            int groups = 1;
            for (int g : ringGroup) {
                groups = Math.max(groups, g + 1);
            }
            GeometryType type = groups > 1 ? GeometryType.MULTIPOLYGON : GeometryType.POLYGON;
            return new GdbGeometry(type, hasZ, hasM, nxs, nys, nzs, nms,
                    newOffsets, ringGroup, null);
        }

        double[] cfx = Arrays.copyOf(xs, w);
        double[] cfy = Arrays.copyOf(ys, w);
        GeometryType type;
        if (kind == PartsKind.MULTIPATCH) {
            type = GeometryType.MULTIPATCH;
        } else {
            type = nParts > 1 ? GeometryType.MULTILINESTRING : GeometryType.LINESTRING;
        }
        return new GdbGeometry(type, hasZ, hasM, cfx, cfy, zs, ms,
                rawOffsets, null, null);
    }

    // ==================================================================
    // 只读包围盒(空间索引粗筛)
    // ==================================================================

    /**
     * 只读 blob 前缀解出包围盒(不解码任何顶点),用于空间索引粗筛。
     *
     * <p>这是空间过滤的第一道闸。对应 GDAL 做空间过滤的路子:先用记录自带的
     * 包围盒快速排除,只对可能相交的记录才走完整的解码。
     *
     * <p>对应GDAL filegdbtable.cpp::GetAsGeometry() 头部读
     * minX/minY/(maxX-minX)/(maxY-minY) 四个 varuint。
     *
     * <p>⚠️ <b>返回值按一个量化步长的量级放宽过</b>({@code pad = 4/scale})。存储的
     * 包围盒是量化之后写的({@code round((v-origin)*scale)}),四舍五入可能让它比
     * 真实范围小不到一个量化单位。放宽之后才能保证"真实包围盒 ⊆ 返回值" —— 用它
     * 排除才不会错杀本该命中的要素。
     *
     * @param blob      几何 blob
     * @param geomField 提供 origin/scale 量化参数(可为 null)
     * @param hasZ      是否带 Z(未使用,维度由 blob 决定)
     * @param hasM      是否带 M(未使用,维度由 blob 决定)
     * @return {@code {xmin, ymin, xmax, ymax}};拿不到时返回 {@code null}
     *         (空几何、POINT、MULTIPATCH 之外的未知布局、解析失败)
     */
    public static double[] peekEnvelope(byte[] blob, GdbGeomField geomField,
                                        boolean hasZ, boolean hasM) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        Quantizer q = new Quantizer(geomField);
        Cursor cur = new Cursor(blob, 0);

        long geomType;
        try {
            geomType = cur.varUInt();
        } catch (RuntimeException ex) {
            return null;
        }

        int shp = (int) (geomType & 0xFF);
        if (shp == ShapeType.NULL || isPointAll(shp)) {
            // 点不存包围盒,坐标本身就是全部
            return null;
        }

        long vMinX;
        long vMinY;
        long vSpanX;
        long vSpanY;
        try {
            long nPoints = cur.varUInt();
            if (nPoints == 0) {
                return null;
            }
            if (isMultiPointAll(shp)) {
                // 多点:点数之后直接就是包围盒
            } else if (isPolylineAll(shp) || isPolygonAll(shp) || isMultiPatchAll(shp)) {
                if (isMultiPatchAll(shp)) {
                    // multipatch 在 nPoints 之后多一个被跳过的 varuint
                    cur.skipVarUInt(1);
                }
                long nParts = cur.varUInt();
                if (nParts == 0) {
                    return null;
                }
                if ((geomType & EXT_SHAPE_CURVE_FLAG) != 0) {
                    cur.skipVarUInt(1);
                }
            } else {
                return null;
            }

            vMinX = cur.varUInt();
            vMinY = cur.varUInt();
            vSpanX = cur.varUInt();
            vSpanY = cur.varUInt();
        } catch (RuntimeException ex) {
            // 布局与预期不符:交给完整的解码路径去报错/兜底
            return null;
        }

        double scale = q.xyScale;
        double xmin = vMinX / scale + q.xOrigin;
        double ymin = vMinY / scale + q.yOrigin;
        double pad = 4.0 / Math.max(Math.abs(scale), 1.0);
        return new double[]{
                xmin - pad,
                ymin - pad,
                xmin + vSpanX / scale + pad,
                ymin + vSpanY / scale + pad
        };
    }

    // ==================================================================
    // 编码(写路径)
    // ==================================================================

    /**
     * GdbGeometry -> FileGDB 原生几何 blob(用于写入记录体)。
     *
     * <p>逐行对照 GDAL {@code filegdbtable_write.cpp}:
     * <ul>
     *   <li>{@code EncodeEnvelope} —— 包围盒 = minX, minY, (maxX-minX), (maxY-minY)</li>
     *   <li>{@code FileGDBTable::EncodeGeometry} —— 主分派</li>
     *   <li>{@code WriteEndOfCurveOrSurface} —— nPoints / nParts / 各 part 点数 / XY / Z / M</li>
     * </ul>
     *
     * <p>三个最容易写错的地方(与 Python 参考实现一致):
     * <ol>
     *   <li>包围盒的后两个分量是 <b>跨度(delta)</b> 而不是 max 值,并且 <b>没有 POINT
     *       那种 +1 偏移</b>({@code +0.5} 只是四舍五入);</li>
     *   <li>坐标网格化的偏移不对称:POINT 是 {@code (v-origin)*scale + 1},
     *       数组形式(线/面/多点)是 {@code (v-origin)*scale},差 1 个量化单位;</li>
     *   <li>环的方向:第 1 个环必须顺时针、其余环必须逆时针,不符合就整环反转
     *       (GDAL 的 {@code bReverseOrder = bFirstRing != bIsClockwise})。</li>
     * </ol>
     *
     * <p>⚠️ {@code compressed} 形参:Python 参考实现只有 delta-varint 一种压缩形式
     * (已逐字节实测)。因此 {@code compressed} 无论真假都写出同一套 delta-varint
     * 字节流 —— 没有未压缩(absolute)写路径可对照,不按公开文档猜。
     *
     * @param geometry   待编码的几何;{@code null}/空几何写成 NULL 类型码
     * @param geomField  提供 origin/scale 量化参数;缺省为 origin=0、scale=1
     * @param hasZ       是否写 Z
     * @param hasM       是否写 M
     * @param compressed 是否使用 delta-varint 压缩点串(见上,当前恒为压缩形式)
     * @return 几何 blob(<b>不含</b> 记录里的 varuint 长度前缀)
     * @throws GdbWriteException 几何类型不支持、或标记带 Z 却缺 Z 坐标
     */
    public static byte[] encode(GdbGeometry geometry, GdbGeomField geomField,
                                boolean hasZ, boolean hasM, boolean compressed) {
        Quantizer q = new Quantizer(geomField);
        ByteWriter out = new ByteWriter(64);

        if (geometry == null || geometry.isEmpty()) {
            VarInt.write(out, ShapeType.NULL);
            return out.toByteArray();
        }

        GeometryType kind = geometry.getType();
        switch (kind) {
            case POINT:
                encodePoint(out, q, geometry, hasZ, hasM);
                break;
            case MULTIPOINT:
                encodeMultiPoint(out, q, geometry, hasZ, hasM);
                break;
            case LINESTRING:
            case MULTILINESTRING:
                encodeParts(out, q, geometry, hasZ, hasM, PartsKind.POLYLINE);
                break;
            case POLYGON:
            case MULTIPOLYGON:
                encodeParts(out, q, geometry, hasZ, hasM, PartsKind.POLYGON);
                break;
            case MULTIPATCH:
                // ⚠️ Python 参考实现未覆盖 multipatch 编码;这里按解码侧同一布局
                // 尽力写出(见 decodeParts 注释),未用真实 ArcGIS 数据校验。
                encodeParts(out, q, geometry, hasZ, hasM, PartsKind.MULTIPATCH);
                break;
            default:
                throw new GdbWriteException("不支持的几何类型: " + kind);
        }
        return out.toByteArray();
    }

    /** 单点编码。网格值 = {@code (v-origin)*scale + 1},空值用 0 表示。 */
    private static void encodePoint(ByteWriter out, Quantizer q,
                                    GdbGeometry geom, boolean hasZ, boolean hasM) {
        VarInt.write(out, ShapeType.makeZM(ShapeType.POINT, hasZ, hasM));

        double x = geom.getX(0);
        double y = geom.getY(0);
        VarInt.write(out, Double.isNaN(x) ? 0 : grid(x, q.xOrigin, q.xyScale) + 1);
        VarInt.write(out, Double.isNaN(y) ? 0 : grid(y, q.yOrigin, q.xyScale) + 1);

        if (hasZ) {
            double z = geom.getZ(0);
            VarInt.write(out, Double.isNaN(z) ? 0 : grid(z, q.zOrigin, q.zScale) + 1);
        }
        if (hasM) {
            double m = geom.getM(0);
            VarInt.write(out, Double.isNaN(m) ? 0 : grid(m, q.mOrigin, q.mScale) + 1);
        }
    }

    /** 多点编码:nPoints | 包围盒 | XY 数组 | [Z] | [M]。 */
    private static void encodeMultiPoint(ByteWriter out, Quantizer q,
                                         GdbGeometry geom, boolean hasZ, boolean hasM) {
        VarInt.write(out, ShapeType.makeZM(ShapeType.MULTIPOINT, hasZ, hasM));
        int n = geom.numPoints();
        VarInt.write(out, n);
        if (n == 0) {
            return;
        }
        double[] xs = geom.xArray();
        double[] ys = geom.yArray();
        double[] zs = hasZ ? geom.zArray() : null;
        double[] ms = hasM ? geom.mArray() : null;
        encodeEnvelope(out, q, xs, ys, n);
        encodePoints(out, q, hasZ, hasM, xs, ys, zs, ms, 0, n);
    }

    /**
     * 线 / 面 / multipatch 编码。
     *
     * <p>对应GDAL filegdbtable_write.cpp::WriteEndOfCurveOrSurface()。
     */
    private static void encodeParts(ByteWriter out, Quantizer q, GdbGeometry geom,
                                    boolean hasZ, boolean hasM, PartsKind kind) {
        int base = kind == PartsKind.POLYGON ? ShapeType.POLYGON : ShapeType.POLYLINE;
        if (kind == PartsKind.MULTIPATCH) {
            VarInt.write(out, ShapeType.MULTIPATCH);
        } else {
            VarInt.write(out, ShapeType.makeZM(base, hasZ, hasM));
        }

        int nPoints = geom.numPoints();
        if (nPoints == 0) {
            VarInt.write(out, 0); // nPoints == 0 即结束(读侧同款早退)
            return;
        }

        int[] offsets = geom.partOffsets();
        int nParts = Math.max(0, offsets.length - 1);

        double[] x = geom.xArray();
        double[] y = geom.yArray();
        double[] z = hasZ ? geom.zArray() : null;
        double[] m = hasM ? geom.mArray() : null;

        // 面的盘上环是闭合的(ArcGIS 这么写),而内部表示不闭合,在这里补回来。
        // 必须在算 counts 之前做,补点会改变每个 part 的长度。
        boolean closeRings = kind == PartsKind.POLYGON;
        boolean[] needsClose = new boolean[nParts];
        int extra = 0;
        if (closeRings) {
            for (int p = 0; p < nParts; p++) {
                int s = offsets[p];
                int e = offsets[p + 1];
                if (e - s > 1 && !(x[s] == x[e - 1] && y[s] == y[e - 1])) {
                    needsClose[p] = true;
                    extra++;
                }
            }
        }

        int total = nPoints + extra;
        double[] ax = new double[total];
        double[] ay = new double[total];
        double[] az = hasZ ? new double[total] : null;
        double[] am = hasM ? new double[total] : null;

        int[] counts = new int[nParts];
        int w = 0;
        for (int p = 0; p < nParts; p++) {
            int s = offsets[p];
            int e = offsets[p + 1];
            for (int k = s; k < e; k++) {
                ax[w] = x[k];
                ay[w] = y[k];
                if (az != null) {
                    az[w] = z != null ? z[k] : Double.NaN;
                }
                if (am != null) {
                    am[w] = m != null ? m[k] : Double.NaN;
                }
                w++;
            }
            if (closeRings && needsClose[p]) {
                ax[w] = x[s];
                ay[w] = y[s];
                if (az != null) {
                    az[w] = z != null ? z[s] : Double.NaN;
                }
                if (am != null) {
                    am[w] = m != null ? m[s] : Double.NaN;
                }
                w++;
            }
            counts[p] = (closeRings && needsClose[p]) ? (e - s + 1) : (e - s);
        }

        if (closeRings) {
            // 闭合在前、定向在后:整环反转后仍然闭合
            orientRings(ax, ay, az, am, counts);
        }

        VarInt.write(out, w); // nPoints
        if (kind == PartsKind.MULTIPATCH) {
            // 与解码侧对应:nPoints 之后、nParts 之前那个被跳过的 varuint,写出 0
            VarInt.write(out, 0);
        }
        VarInt.write(out, counts.length); // nParts

        encodeEnvelope(out, q, ax, ay, w);
        for (int p = 0; p + 1 < counts.length; p++) {
            // 最后一个 part 的点数不写,由总数推出
            VarInt.write(out, counts[p]);
        }
        encodePoints(out, q, hasZ, hasM, ax, ay, az, am, 0, w);
    }

    /**
     * 把点数组按增量写进 {@code out}(对应 _esri_geometry._Encoder)。
     *
     * <p>与解码侧的 {@link Cursor#delta} 对称:XY 的增量累加器 <b>跨 part 连续</b>,
     * Z、M 各自独立累加器(都从 0 开始)。
     */
    private static void encodePoints(ByteWriter out, Quantizer q, boolean hasZ,
                                     boolean hasM, double[] xs, double[] ys,
                                     double[] zs, double[] ms, int start, int end) {
        long lastX = 0;
        long lastY = 0;
        for (int i = start; i < end; i++) {
            long nx = grid(xs[i], q.xOrigin, q.xyScale);
            long ny = grid(ys[i], q.yOrigin, q.xyScale);
            writeVarintDelta(out, nx - lastX);
            writeVarintDelta(out, ny - lastY);
            lastX = nx;
            lastY = ny;
        }

        if (hasZ) {
            if (zs == null) {
                throw new GdbWriteException("几何标记为带 Z,但没提供 Z 坐标");
            }
            // NaN -> 量化值 0,即"这一维为空"。数组形式的量化没有那个 +1,
            // 所以 0 天然就是空值哨兵(读侧也这么认)。
            long last = 0;
            for (int i = start; i < end; i++) {
                long nz = Double.isNaN(zs[i]) ? 0 : grid(zs[i], q.zOrigin, q.zScale);
                writeVarintDelta(out, nz - last);
                last = nz;
            }
        }

        if (hasM && ms != null) {
            // M 整体缺失时 GDAL 不写任何东西(读侧只是"容忍"那个 0x42 标记字节,
            // 写侧从不生成),这里保持一致。
            long last = 0;
            for (int i = start; i < end; i++) {
                long nm = Double.isNaN(ms[i]) ? 0 : grid(ms[i], q.mOrigin, q.mScale);
                writeVarintDelta(out, nm - last);
                last = nm;
            }
        }
    }

    /**
     * 写包围盒:minX, minY, (maxX-minX), (maxY-minY) 共 4 个 varuint。
     *
     * <p>对应 GDAL {@code EncodeEnvelope}。两点必须注意:
     * <ul>
     *   <li>前两个分量相对 origin 量化,<b>没有 +1</b>;</li>
     *   <li>后两个分量是 <b>跨度</b>,只乘 scale,不减 origin。</li>
     * </ul>
     */
    private static void encodeEnvelope(ByteWriter out, Quantizer q,
                                       double[] xs, double[] ys, int n) {
        double minx = Double.POSITIVE_INFINITY;
        double maxx = Double.NEGATIVE_INFINITY;
        double miny = Double.POSITIVE_INFINITY;
        double maxy = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (xs[i] < minx) {
                minx = xs[i];
            }
            if (xs[i] > maxx) {
                maxx = xs[i];
            }
            if (ys[i] < miny) {
                miny = ys[i];
            }
            if (ys[i] > maxy) {
                maxy = ys[i];
            }
        }
        VarInt.write(out, grid(minx, q.xOrigin, q.xyScale));
        VarInt.write(out, grid(miny, q.yOrigin, q.xyScale));
        VarInt.write(out, roundHalfUp((maxx - minx) * q.xyScale));
        VarInt.write(out, roundHalfUp((maxy - miny) * q.xyScale));
    }

    /**
     * 写一个"有符号增量整数",与 GDAL {@code WriteVarInt} 完全等价。
     *
     * <p>编码规则(注意<b>不是</b> zigzag):首字节 bit6 是符号位、低 6 位是数据;
     * 若数据放不下则置 bit7,后续按<b>无符号</b> LEB128 分组续写。
     */
    private static void writeVarintDelta(ByteWriter out, long val) {
        if (val < 0) {
            long uval = -val;
            if (uval >= 0x40L) {
                out.u8(0x80 | 0x40 | (int) (uval & 0x3FL));
                uval >>>= 6;
            } else {
                out.u8(0x40 | (int) (uval & 0x3FL));
                return;
            }
            VarInt.write(out, uval);
        } else {
            long uval = val;
            if (uval >= 0x40L) {
                out.u8(0x80 | (int) (uval & 0x3FL));
                uval >>>= 6;
            } else {
                out.u8((int) (uval & 0x3FL));
                return;
            }
            VarInt.write(out, uval);
        }
    }

    /**
     * 四舍五入到整数(对应 GDAL 的 {@code std::round} + {@code static_cast<int64_t>})。
     *
     * <p>不能用 {@code Math.round}:它是"向下取整 + 0.5"对负数的口径与 std::round
     * 不同,在 .5 处会与 GDAL 差 1,导致写出的坐标与 ArcGIS 逐位不一致。
     */
    private static long roundHalfUp(double v) {
        if (v >= 0) {
            return (long) (v + 0.5);
        }
        return -(long) (-v + 0.5);
    }

    /** {@code round((v - origin) * scale)}。NaN 返回 0(表示空值)。 */
    private static long grid(double v, double origin, double scale) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return roundHalfUp((v - origin) * scale);
    }

    // ==================================================================
    // 环的方向与分组
    // ==================================================================

    /**
     * 让第 1 个环顺时针、其余环逆时针(对应 _orient_rings)。
     *
     * <p>⚠️ 会<b>就地改写</b>点数组以反映反转后的顺序。对应 GDAL 里
     * {@code bReverseOrder = bFirstRing != bIsClockwise} 那段逻辑。
     */
    private static void orientRings(double[] ax, double[] ay, double[] az, double[] am,
                                    int[] counts) {
        int idx = 0;
        for (int p = 0; p < counts.length; p++) {
            int c = counts[p];
            boolean first = p == 0;
            if (isClockwise(ax, ay, idx, idx + c) != first) {
                reverseRange(ax, ay, az, am, idx, idx + c);
            }
            idx += c;
        }
    }

    /** 就地反转 {@code [s, e)} 区间的点。 */
    private static void reverseRange(double[] ax, double[] ay, double[] az, double[] am,
                                     int s, int e) {
        for (int i = s, j = e - 1; i < j; i++, j--) {
            double t = ax[i];
            ax[i] = ax[j];
            ax[j] = t;
            t = ay[i];
            ay[i] = ay[j];
            ay[j] = t;
            if (az != null) {
                t = az[i];
                az[i] = az[j];
                az[j] = t;
            }
            if (am != null) {
                t = am[i];
                am[i] = am[j];
                am[j] = t;
            }
        }
    }

    /**
     * 用带符号面积(鞋带公式)判断环的绕向。
     *
     * <p>Esri 的约定是"外环顺时针、内环逆时针"。按数学坐标系(y 向上):
     * <b>面积为负 = 顺时针</b>。对应 _esri_geometry._is_clockwise +
     * _geometry_ops.ring_signed_area2。
     */
    private static boolean isClockwise(double[] ax, double[] ay, int s, int e) {
        if (e - s < 3) {
            return false;
        }
        return ringSignedArea2(ax, ay, s, e) < 0;
    }

    /**
     * 环的<b>带符号面积的两倍</b> {@code Σ(x_i·y_{i+1} − x_{i+1}·y_i)}。
     *
     * <p>⚠️ <b>先平移到首顶点再累加</b> —— 这不是优化,是正确性:不平移时近零面积
     * 的环会因灾难性抵消把符号判反,而这个符号决定环的绕向/归属。
     */
    private static double ringSignedArea2(double[] ax, double[] ay, int s, int e) {
        int n = e - s;
        if (n < 3) {
            return 0.0;
        }
        double ox = ax[s];
        double oy = ay[s];
        double total = 0.0;
        double x1 = 0.0;
        double y1 = 0.0;
        for (int i = 1; i < n; i++) {
            double x2 = ax[s + i] - ox;
            double y2 = ay[s + i] - oy;
            total += x1 * y2 - x2 * y1;
            x1 = x2;
            y1 = y2;
        }
        return total;
    }

    /** 点相对单个环的位置常量(与 DE-9IM 的 I/B/E 下标一致)。 */
    private static final int INTERIOR = 0;
    private static final int BOUNDARY = 1;
    private static final int EXTERIOR = 2;

    /** 点是否落在环上/内/外(对应 _geometry_ops.point_in_ring)。 */
    private static int pointInRing(double px, double py, double[] ax, double[] ay,
                                   int s, int e) {
        int n = e - s;
        if (n == 0) {
            return EXTERIOR;
        }
        if (n == 1) {
            return (px == ax[s] && py == ay[s]) ? BOUNDARY : EXTERIOR;
        }
        boolean inside = false;
        double x1 = ax[s];
        double y1 = ay[s];
        for (int i = 1; i <= n; i++) {
            int j = i % n;
            double x2 = ax[s + j];
            double y2 = ay[s + j];
            if (orient(x1, y1, x2, y2, px, py) == 0.0) {
                if (Math.min(x1, x2) <= px && px <= Math.max(x1, x2)
                        && Math.min(y1, y2) <= py && py <= Math.max(y1, y2)) {
                    return BOUNDARY;
                }
            } else if ((y1 > py) != (y2 > py)) {
                // 半开区间规则 (y1 > py) != (y2 > py):顶点只被数一次
                if (px < x1 + (py - y1) * (x2 - x1) / (y2 - y1)) {
                    inside = !inside;
                }
            }
            x1 = x2;
            y1 = y2;
        }
        return inside ? INTERIOR : EXTERIOR;
    }

    /** 叉积 {@code (b - a) × (c - a)}。> 0 表示 c 在 a→b 左侧。 */
    private static double orient(double ax, double ay, double bx, double by,
                                 double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /**
     * 把平铺的环组装成"每个环属于哪个多边形"(对应
     * _geometry_ops.organize_polygons 的 ONLY_CCW 捷径 + 归属回退)。
     *
     * <p><b>这是 GDAL {@code OGRGeometryFactory::organizePolygons()} 的对应物</b>
     * (受 {@code OGR_ORGANIZE_POLYGONS} 控制,默认 {@code ONLY_CCW})。Esri 盘上把一个
     * 面的所有环平铺在一起、<b>不记录"哪个洞属于哪个外环"</b>,这里按绕向区分壳与洞:
     * <ol>
     *   <li>顺时针(带符号面积为负)= 外环;一个顺时针环都没有时按位置兜底(part 0 是外环);</li>
     *   <li>每个洞找<b>包含它的最小外环</b>当归属;</li>
     *   <li>一个洞若不被任何外环包含,说明绕向多半是反的 —— 按 GDAL 做法改当成外环。</li>
     * </ol>
     *
     * @param ax        X 数组(环不闭合)
     * @param ay        Y 数组
     * @param offsets   环边界(长度 = 环数 + 1)
     * @return 逐环的多边形序号
     */
    private static int[] organizePolygons(double[] ax, double[] ay, int[] offsets) {
        int n = offsets.length - 1;
        if (n <= 0) {
            return new int[0];
        }
        if (n == 1) {
            // 单环面:答案与绕向无关,恒为"这一个环就是外环、没有洞"
            return new int[]{0};
        }

        boolean[] isShell = new boolean[n];
        boolean anyShell = false;
        for (int i = 0; i < n; i++) {
            isShell[i] = ringSignedArea2(ax, ay, offsets[i], offsets[i + 1]) < 0.0;
            anyShell |= isShell[i];
        }
        if (!anyShell) {
            // 一个顺时针环都没有 —— 全是逆时针。按位置约定兜底:part 0 是外环。
            for (int i = 0; i < n; i++) {
                isShell[i] = i == 0;
            }
        }

        int[] groupOf = new int[n];
        Arrays.fill(groupOf, -1);
        int nGroups = 0;
        for (int i = 0; i < n; i++) {
            if (isShell[i]) {
                groupOf[i] = nGroups++;
            }
        }

        double[] areas = new double[n];
        for (int i = 0; i < n; i++) {
            areas[i] = Math.abs(ringSignedArea2(ax, ay, offsets[i], offsets[i + 1]));
        }

        for (int i = 0; i < n; i++) {
            if (isShell[i]) {
                continue;
            }
            // 代表点取环的首顶点(对应 _ring_representative)
            double px = ax[offsets[i]];
            double py = ay[offsets[i]];
            int best = -1;
            double bestArea = 0.0;
            for (int sj = 0; sj < n; sj++) {
                if (!isShell[sj]) {
                    continue;
                }
                if (pointInRing(px, py, ax, ay, offsets[sj], offsets[sj + 1]) == EXTERIOR) {
                    continue;
                }
                if (best < 0 || areas[sj] < bestArea) {
                    best = sj;
                    bestArea = areas[sj];
                }
            }
            if (best < 0) {
                // 绕向反了 —— 改当外环(GDAL 的回退)
                isShell[i] = true;
                groupOf[i] = nGroups++;
            } else {
                groupOf[i] = groupOf[best];
            }
        }
        return groupOf;
    }

    // ==================================================================
    // 带位置的 blob 游标(对应 _DeltaReader)
    // ==================================================================

    /**
     * 带位置状态的有符号增量读取器。
     *
     * <p>对应 GDAL 的 {@code ReadVarIntAndAddNoCheck}:每读一次就把累加器加上
     * (或减去)增量。线/面的点序列就是靠这个累加器还原绝对坐标的。
     */
    private static final class Cursor {
        private final byte[] blob;
        private int pos;
        private boolean lastNegative;

        Cursor(byte[] blob, int pos) {
            this.blob = blob;
            this.pos = pos;
        }

        /** 断言还有 {@code n} 字节可读。 */
        private void need(int n) {
            if (pos + n > blob.length) {
                throw new GdbFormatException(
                        "几何 blob 被截断(pos=" + pos + ", len=" + blob.length + ")");
            }
        }

        /** 读一个 unsigned varuint(对应 GDAL {@code ReadVarUInt})。 */
        long varUInt() {
            long[] r = VarInt.read(blob, pos, blob.length);
            pos = (int) r[1];
            return r[0];
        }

        /**
         * 跳过 {@code n} 个 varuint(对应 GDAL {@code SkipVarUInt})。
         */
        void skipVarUInt(int n) {
            for (int k = 0; k < n; k++) {
                if (pos >= blob.length) {
                    throw new GdbFormatException("_skip_varuint: 越界");
                }
                while (true) {
                    int b = blob[pos++] & 0xFF;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    if (pos >= blob.length) {
                        throw new GdbFormatException("_skip_varuint: 截断");
                    }
                }
            }
        }

        /**
         * 读一个增量并累加到 {@code acc},返回新的 acc。
         *
         * <p>对应 GDAL {@code ReadVarIntAndAddNoCheck}。
         */
        long delta(long acc) {
            long val = readDeltaMagnitude();
            return lastNegative ? acc - val : acc + val;
        }

        /**
         * 读一个"增量整数"的绝对值并置 {@link #lastNegative}。
         *
         * <p>与 varuint 的区别:第一个字节的 0x40 位是符号位,首字节只有低 6 位是
         * 数据;后续字节仍是 7 位一组。对应 {@code read_varint_delta} 与
         * {@code _read_xy_array} 内联版(用 _MAX_SHIFT 上限,二者接受域一致)。
         */
        private long readDeltaMagnitude() {
            if (pos >= blob.length) {
                throw new GdbFormatException("read_varint_delta: 截断");
            }
            int b = blob[pos] & 0xFF;
            long val = b & 0x3FL;
            lastNegative = (b & 0x40) != 0;

            if ((b & 0x80) == 0) {
                pos += 1;
                return val;
            }

            pos += 1;
            int shift = 6;
            while (true) {
                if (pos >= blob.length) {
                    throw new GdbFormatException("read_varint_delta: 截断");
                }
                b = blob[pos] & 0xFF;
                pos += 1;
                val |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) {
                    return val;
                }
                shift += 7;
                if (shift >= MAX_SHIFT) {
                    throw new GdbFormatException("delta-varint 过长(损坏的 blob)");
                }
            }
        }
    }
}
