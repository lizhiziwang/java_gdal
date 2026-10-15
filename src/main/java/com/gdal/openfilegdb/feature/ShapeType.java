package com.gdal.openfilegdb.feature;

/**
 * 几何 blob 内部的 shape type 常量(Esri shapefile 风格编号)。
 *
 * <p>数值逐行核对 {@code ogr/ogrpgeogeometry.h}(见该文件 {@code #ifndef SHPT_POINT}
 * 段)与 {@code filegdbtable.cpp::GetAsGeometry} 中的 switch 分支。
 *
 * <p>⚠️ <b>这些编号与公开的 ESRI Shapefile 规范并不一致</b> ——
 * GDAL 用的是自己的一套,最典型的差异是 <b>ZM 档占用了 shapefile 里 Z 档的编号</b>:
 *
 * <pre>
 *   类型           GDAL 编号     shapefile 公开值
 *   POINTZ         9             11
 *   POINTZM        11            (无此档)
 *   POLYGONZ       19            15
 *   POLYGONZM      15            (常被误当成 POLYGONZ)
 *   MULTIPOINTZ    20            18
 *   MULTIPOINTZM   18            (常被误当成 MULTIPOINTZ)
 *   ARCZ           10            13
 *   ARCZM          13            (常被误当成 POLYLINEZ)
 * </pre>
 *
 * <p>照着 shapefile 规范写文件,GDAL 会把 3D+M 的图形读成纯 Z(或相反),
 * 因此必须严格按本类取值。
 */
public final class ShapeType {

    /** 空几何。 */
    public static final int NULL = 0;

    public static final int POINT = 1;
    public static final int POINTZ = 9;
    public static final int POINTZM = 11;
    public static final int POINTM = 21;

    /** 线(对应 shapefile 的 {@code SHPT_ARC})。 */
    public static final int POLYLINE = 3;
    public static final int POLYLINEZ = 10;
    public static final int POLYLINEZM = 13;
    public static final int POLYLINEM = 23;

    public static final int POLYGON = 5;
    public static final int POLYGONZ = 19;
    public static final int POLYGONZM = 15;
    public static final int POLYGONM = 25;

    public static final int MULTIPOINT = 8;
    public static final int MULTIPOINTZ = 20;
    public static final int MULTIPOINTZM = 18;
    public static final int MULTIPOINTM = 28;

    public static final int MULTIPATCH = 32;
    public static final int MULTIPATCHM = 31;

    /** 混合的"任意线"。 */
    public static final int GENERAL_POLYLINE = 50;
    /** 混合的"任意面"。 */
    public static final int GENERAL_POLYGON = 51;
    /** 混合的"任意点"。 */
    public static final int GENERAL_POINT = 52;
    /** 混合的"任意多点"。 */
    public static final int GENERAL_MULTIPOINT = 53;
    /** 混合的"任意 multipatch"。 */
    public static final int GENERAL_MULTIPATCH = 54;

    private ShapeType() {
    }

    /**
     * 该 shape type 是否带 Z。
     *
     * <p>{@code GENERAL_*} 系列(50~54)是"混合/任意"类型,其 Z/M 是否存在由
     * blob 里实际写出的坐标数组决定,不体现在 shape type 上,因此这里对它们
     * 一律返回 {@code false},由解码器另行判定。
     *
     * @param t shape type
     * @return 是否带 Z
     */
    public static boolean hasZ(int t) {
        return t == POINTZ || t == POINTZM
                || t == POLYLINEZ || t == POLYLINEZM
                || t == POLYGONZ || t == POLYGONZM
                || t == MULTIPOINTZ || t == MULTIPOINTZM
                || t == MULTIPATCH;
    }

    /**
     * 该 shape type 是否带 M。
     *
     * @param t shape type
     * @return 是否带 M
     */
    public static boolean hasM(int t) {
        return t == POINTM || t == POINTZM
                || t == POLYLINEM || t == POLYLINEZM
                || t == POLYGONM || t == POLYGONZM
                || t == MULTIPOINTM || t == MULTIPOINTZM
                || t == MULTIPATCHM;
    }

    /**
     * 归一化到基础族。
     *
     * @param t shape type
     * @return 基础 shape type:{@link #POINT} / {@link #POLYLINE} /
     *         {@link #POLYGON} / {@link #MULTIPOINT} / {@link #MULTIPATCH} / {@link #NULL}
     */
    public static int baseKind(int t) {
        switch (t) {
            case POINT:
            case POINTZ:
            case POINTZM:
            case POINTM:
            case GENERAL_POINT:
                return POINT;
            case POLYLINE:
            case POLYLINEZ:
            case POLYLINEZM:
            case POLYLINEM:
            case GENERAL_POLYLINE:
                return POLYLINE;
            case POLYGON:
            case POLYGONZ:
            case POLYGONZM:
            case POLYGONM:
            case GENERAL_POLYGON:
                return POLYGON;
            case MULTIPOINT:
            case MULTIPOINTZ:
            case MULTIPOINTZM:
            case MULTIPOINTM:
            case GENERAL_MULTIPOINT:
                return MULTIPOINT;
            case MULTIPATCH:
            case MULTIPATCHM:
            case GENERAL_MULTIPATCH:
                return MULTIPATCH;
            default:
                return NULL;
        }
    }

    /**
     * 由基础类型 + Z/M 标志推出最终 shape type。
     *
     * @param base 基础类型({@link #POINT}/{@link #POLYLINE}/{@link #POLYGON}/{@link #MULTIPOINT})
     * @param hasZ 是否带 Z
     * @param hasM 是否带 M
     * @return 最终 shape type
     * @throws IllegalArgumentException base 不是基础类型
     */
    public static int makeZM(int base, boolean hasZ, boolean hasM) {
        switch (base) {
            case POINT:
                return pick(hasZ, hasM, POINT, POINTZ, POINTM, POINTZM);
            case POLYLINE:
                return pick(hasZ, hasM, POLYLINE, POLYLINEZ, POLYLINEM, POLYLINEZM);
            case POLYGON:
                return pick(hasZ, hasM, POLYGON, POLYGONZ, POLYGONM, POLYGONZM);
            case MULTIPOINT:
                return pick(hasZ, hasM, MULTIPOINT, MULTIPOINTZ, MULTIPOINTM, MULTIPOINTZM);
            default:
                throw new IllegalArgumentException("不是基础 shape type: " + base);
        }
    }

    /**
     * 按 Z/M 标志在四个候选常量里挑一个。
     *
     * <p>抽出来只是为了避免四层嵌套三元表达式(可读性 + 行宽)。
     *
     * @param hasZ 是否带 Z
     * @param hasM 是否带 M
     * @param xy   只有 XY 时的常量
     * @param z    只有 Z 时的常量
     * @param m    只有 M 时的常量
     * @param zm   Z 和 M 都有的常量
     * @return 对应的 shape type
     */
    private static int pick(boolean hasZ, boolean hasM, int xy, int z, int m, int zm) {
        if (hasZ) {
            return hasM ? zm : z;
        }
        return hasM ? m : xy;
    }

    /**
     * 名称化(调试 / 日志用)。
     *
     * @param t shape type
     * @return 名字
     */
    public static String name(int t) {
        switch (t) {
            case NULL:
                return "NULL";
            case POINT:
                return "POINT";
            case POINTZ:
                return "POINTZ";
            case POINTZM:
                return "POINTZM";
            case POINTM:
                return "POINTM";
            case POLYLINE:
                return "POLYLINE";
            case POLYLINEZ:
                return "POLYLINEZ";
            case POLYLINEZM:
                return "POLYLINEZM";
            case POLYLINEM:
                return "POLYLINEM";
            case POLYGON:
                return "POLYGON";
            case POLYGONZ:
                return "POLYGONZ";
            case POLYGONZM:
                return "POLYGONZM";
            case POLYGONM:
                return "POLYGONM";
            case MULTIPOINT:
                return "MULTIPOINT";
            case MULTIPOINTZ:
                return "MULTIPOINTZ";
            case MULTIPOINTZM:
                return "MULTIPOINTZM";
            case MULTIPOINTM:
                return "MULTIPOINTM";
            case MULTIPATCH:
                return "MULTIPATCH";
            case GENERAL_POINT:
                return "GENERAL_POINT";
            case GENERAL_POLYLINE:
                return "GENERAL_POLYLINE";
            case GENERAL_POLYGON:
                return "GENERAL_POLYGON";
            case GENERAL_MULTIPOINT:
                return "GENERAL_MULTIPOINT";
            default:
                return "SHAPE(" + t + ")";
        }
    }
}
