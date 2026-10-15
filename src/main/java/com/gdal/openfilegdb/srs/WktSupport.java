package com.gdal.openfilegdb.srs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WKT 的轻量解析与内置坐标系表。
 *
 * <p>本类<b>不是</b>一个完整的 WKT 解析器 —— GDAL 在这里委托
 * {@code OSRSetFromUserInput} / {@code importFromWkt},依赖 PROJ 数据库。
 * 本工程不引入 PROJ,因此只做两件够用的事:
 * <ol>
 *   <li>从 WKT 文本里抽取末端 {@code AUTHORITY}/ {@code ID} 节点得到 WKID
 *       (对标 GDAL {@code OGRSpatialReference::GetAuthorityCode} 的简化版);</li>
 *   <li>内建最常用的几个 EPSG 的 WKT1 文本,供"新建要素类"时使用。</li>
 * </ol>
 *
 * <p>完整 WKT 仍然会被原样保存并写回文件,因此即使 {@code WKID} 解析失败,
 * 空间参考也不会丢失 —— 这与 GDAL 的行为一致(它同样把原 WKT 写进
 * 几何字段描述区)。
 */
public final class WktSupport {

    /** 匹配 {@code AUTHORITY["EPSG","4326"]} 或 {@code ID["EPSG",4326]}。 */
    private static final Pattern AUTHORITY = Pattern.compile(
            "(?:AUTHORITY|ID)\\s*\\[\\s*\"([^\"]+)\"\\s*,\\s*\"?(\\d+)\"?\\s*]",
            Pattern.CASE_INSENSITIVE);

    /** 匹配 WKT 的第一个引号字符串(通常是根节点的名字)。 */
    private static final Pattern FIRST_NAME =
            Pattern.compile("^\\s*[A-Za-z_]+\\s*\\[\\s*\"([^\"]*)\"");

    /** 内建坐标系清单,供 {@link #builtinEpsg(String)} 反查。 */
    private static final int[] BUILTIN_EPSG = {4326, 3857, 4490, 4269, 3395, 32650};

    private WktSupport() {
    }

    /**
     * 抽取 WKT 里最后一个 {@code AUTHORITY}/{@code ID} 的编码。
     *
     * <p>取"最后一个"是因为 WKT1 里 {@code AUTHORITY} 总是挂在最外层节点上、
     * 且出现在文本末尾;WKT2 的 {@code ID} 同样如此。
     *
     * @param wkt WKT 文本
     * @return WKID;解析不到返回 0
     */
    public static int extractLastAuthorityCode(String wkt) {
        if (wkt == null || wkt.isEmpty()) {
            return 0;
        }
        int code = 0;
        Matcher m = AUTHORITY.matcher(wkt);
        while (m.find()) {
            try {
                code = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                // 继续找下一个
            }
        }
        return code;
    }

    /**
     * 抽取 WKT 里第一个 AUTHORITY 的系统名(如 {@code EPSG})。
     *
     * @param wkt WKT 文本
     * @return 系统名;解析不到返回空串
     */
    public static String extractLastAuthorityName(String wkt) {
        if (wkt == null || wkt.isEmpty()) {
            return "";
        }
        String name = "";
        Matcher m = AUTHORITY.matcher(wkt);
        while (m.find()) {
            name = m.group(1);
        }
        return name;
    }

    /**
     * 抽取 WKT 根节点的名字(第一个引号字符串)。
     *
     * @param wkt WKT 文本
     * @return 名字;解析不到返回空串
     */
    public static String extractName(String wkt) {
        if (wkt == null || wkt.isEmpty()) {
            return "";
        }
        Matcher m = FIRST_NAME.matcher(wkt);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 内建 WKT 反查 EPSG 编码。
     *
     * <p><b>为什么需要:</b>ArcGIS 与 GDAL 写进 {@code .gdb} 的 {@code SRTEXT}
     * 都是 <b>ESRI 风格 WKT1</b>,里面<b>没有</b> {@code AUTHORITY}/{@code ID} 节点
     * (实测 GDAL 为 EPSG:4326 写的 SRTEXT 与本类 {@link #builtinWkt(int)} 的
     * 返回逐字符相同)。所以 {@link #extractLastAuthorityCode(String)} 会返回 0,
     * 导致读回的 {@code wkid} 丢失。
     *
     * <p>GDAL 是靠 PROJ 把 WKT 与 EPSG 库做匹配来还原 WKID 的;本工程不引入 PROJ,
     * 只对<a href="#builtinWkt(int)">内建的几个</a>做归一化字符串比对 ——
     * 归一只去空白 + 转小写,所以对空白差异是稳健的,对语义等价但写法不同的
     * WKT(如 2D/3D、轴序不同)不生效。
     *
     * @param wkt WKT 文本
     * @return 命中的 EPSG 编码;未命中返回 0
     */
    public static int builtinEpsg(String wkt) {
        if (wkt == null || wkt.isEmpty()) {
            return 0;
        }
        String needle = normalize(wkt);
        for (int epsg : BUILTIN_EPSG) {
            if (normalize(builtinWkt(epsg)).equals(needle)) {
                return epsg;
            }
        }
        return 0;
    }

    /** 去掉所有空白并转小写,用于 WKT 的宽松比对。 */
    private static String normalize(String wkt) {
        StringBuilder sb = new StringBuilder(wkt.length());
        for (int i = 0; i < wkt.length(); i++) {
            char c = wkt.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * 内建 EPSG 的 WKT1 文本。
     *
     * <p>只收录最常用的几个。要支持其它坐标系,请用
     * {@link SpatialReference#of(int, String)} 显式传入 WKT。
     *
     * @param epsg EPSG 编码
     * @return WKT;未收录返回空串
     */
    public static String builtinWkt(int epsg) {
        switch (epsg) {
            case 4326:
                return "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\","
                        + "SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                        + "PRIMEM[\"Greenwich\",0.0],"
                        + "UNIT[\"Degree\",0.0174532925199433]]";
            case 3857:
                return "PROJCS[\"WGS_1984_Web_Mercator_Auxiliary_Sphere\","
                        + "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\","
                        + "SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                        + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                        + "PROJECTION[\"Mercator_Auxiliary_Sphere\"],"
                        + "PARAMETER[\"False_Easting\",0.0],PARAMETER[\"False_Northing\",0.0],"
                        + "PARAMETER[\"Central_Meridian\",0.0],"
                        + "PARAMETER[\"Standard_Parallel_1\",0.0],"
                        + "PARAMETER[\"Auxiliary_Sphere_Type\",0.0],UNIT[\"Meter\",1.0]]";
            case 4490:
                return "GEOGCS[\"GCS_China_Geodetic_Coordinate_System_2000\","
                        + "DATUM[\"D_China_2000\",SPHEROID[\"CGCS2000\",6378137.0,298.257222101]],"
                        + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
            case 4269:
                return "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\","
                        + "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],"
                        + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
            case 3395:
                return "PROJCS[\"WGS_1984_World_Mercator\",GEOGCS[\"GCS_WGS_1984\","
                        + "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                        + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                        + "PROJECTION[\"Mercator\"],PARAMETER[\"False_Easting\",0.0],"
                        + "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",0.0],"
                        + "PARAMETER[\"Standard_Parallel_1\",0.0],UNIT[\"Meter\",1.0]]";
            case 32650:
                return "PROJCS[\"WGS_1984_UTM_Zone_50N\",GEOGCS[\"GCS_WGS_1984\","
                        + "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                        + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                        + "PROJECTION[\"Transverse_Mercator\"],"
                        + "PARAMETER[\"False_Easting\",500000.0],"
                        + "PARAMETER[\"False_Northing\",0.0],"
                        + "PARAMETER[\"Central_Meridian\",117.0],"
                        + "PARAMETER[\"Scale_Factor\",0.9996],"
                        + "PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1.0]]";
            default:
                return "";
        }
    }

    /**
     * 内建 EPSG 的名称。
     *
     * @param epsg EPSG 编码
     * @return 名称;未收录返回形如 {@code EPSG:xxxx} 的兜底名
     */
    public static String builtinName(int epsg) {
        switch (epsg) {
            case 4326:
                return "GCS_WGS_1984";
            case 3857:
                return "WGS_1984_Web_Mercator_Auxiliary_Sphere";
            case 4490:
                return "GCS_China_Geodetic_Coordinate_System_2000";
            case 4269:
                return "GCS_North_American_1983";
            case 3395:
                return "WGS_1984_World_Mercator";
            case 32650:
                return "WGS_1984_UTM_Zone_50N";
            default:
                return "EPSG:" + epsg;
        }
    }
}
