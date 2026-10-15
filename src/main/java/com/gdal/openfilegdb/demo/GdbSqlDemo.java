package com.gdal.openfilegdb.demo;

import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.exception.OpenFileGdbException;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.layer.GdbFeatureDefn;
import com.gdal.openfilegdb.layer.GdbLayer;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.sql.GdbSqlResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * SQL 演示:用 {@code executeSql} 做属性查询 / 聚合 / 排序 / 空间过滤。
 *
 * <p>对标 GDAL 的 {@code ogrinfo -sql "..."}:查询结果是一个<b>游标式</b>的结果集,
 * {@code SELECT/WHERE/LIMIT} 与聚合全程流式(内存与行数无关),
 * {@code ORDER BY / DISTINCT / GROUP BY} 这些子句本身要求物化。
 *
 * <p>运行(在工程根目录,先 {@code mvn -q clean package}):
 * <pre>
 *   java -cp "target/classes;&lt;calcite 及依赖.jar&gt;" \
 *       com.gdal.openfilegdb.demo.GdbSqlDemo &lt;某个.gdb&gt;
 * </pre>
 * 不传参数时默认读 {@code target/demo-out/write-demo.gdb}(可先跑 {@link GdbWriteDemo} 生成)。
 */
public final class GdbSqlDemo {

    /** 结果集里最多打印多少行(查询本身不截断,只是打印省事)。 */
    private static final int PREVIEW_ROWS = 10;

    private GdbSqlDemo() {
    }

    /**
     * 入口。
     *
     * @param args 可选:{@code [0]} 待打开的 {@code .gdb} 目录
     */
    public static void main(String[] args) {
        Path gdbPath = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("target", "demo-out", "write-demo.gdb");

        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdbPath)) {
            List<String> names = ds.getLayerNames();
            System.out.println("打开 FileGDB: " + ds.getPath());
            System.out.println("可查询图层: " + names);
            if (names.isEmpty()) {
                System.out.println("(没有用户图层,先跑 GdbWriteDemo 生成一个)");
                return;
            }

            // 挑第一个有要素的空间图层做示例
            OpenFileGdbLayer layer = pickLayer(ds);
            if (layer == null) {
                System.out.println("(没有带要素的空间图层可演示)");
                return;
            }
            String table = "\"" + layer.getName() + "\"";

            // 1) 投影 + WHERE + LIMIT:流式
            run(ds, "投影 + 过滤 + 限量(流式)",
                    "SELECT * FROM " + table + " LIMIT 3");

            // 2) 计数与聚合:只留累加器,不物化
            run(ds, "计数与聚合",
                    "SELECT COUNT(*) AS n FROM " + table);

            // 3) 排序:SQL 语义要求物化(GDAL 的 ORDER BY 也是)
            run(ds, "按 OBJECTID 倒序取前 3(排序会物化)",
                    "SELECT * FROM " + table + " ORDER BY OBJECTID DESC LIMIT 3");

            // 4) 空间过滤:bbox 参数等价于 GDAL ExecuteSQL 的 poSpatialFilter
            double[] extent = layer.getExtent();
            if (extent != null) {
                double[] bbox = {extent[0], extent[1],
                        (extent[0] + extent[2]) / 2, (extent[1] + extent[3]) / 2};
                run(ds, "空间过滤(extent 的左下半区) " + java.util.Arrays.toString(bbox),
                        "SELECT COUNT(*) AS n FROM " + table, bbox);
            }

            // 5) 结果当图层读:与 GDAL ExecuteSQL 返回的 OGRLayer 同款
            runAsLayer(ds, table);

            // 6) 错误语义:非查询语句被拒绝
            try {
                ds.executeSql("DROP TABLE " + table);
            } catch (OpenFileGdbException e) {
                System.out.println();
                System.out.println("--- 写语句被拒绝(GDAL 的 DELLAYER:/DROP TABLE 本库不做)");
                System.out.println("    " + e.getMessage());
            }
        } catch (OpenFileGdbException e) {
            System.err.println("FileGDB 操作失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static OpenFileGdbLayer pickLayer(OpenFileGdbDataset ds) {
        for (int i = 0; i < ds.getLayerCount(); i++) {
            OpenFileGdbLayer l = ds.getLayer(i);
            if (l.getFeatureCount() > 0 && l.getGeometryType() != GeometryType.UNKNOWN) {
                return l;
            }
        }
        for (int i = 0; i < ds.getLayerCount(); i++) {
            OpenFileGdbLayer l = ds.getLayer(i);
            if (l.getFeatureCount() > 0) {
                return l;
            }
        }
        return null;
    }

    private static void run(OpenFileGdbDataset ds, String label, String sql) {
        run(ds, label, sql, null);
    }

    private static void run(OpenFileGdbDataset ds, String label, String sql, double[] bbox) {
        System.out.println();
        System.out.println("--- " + label);
        System.out.println("    SQL: " + sql);
        long t0 = System.currentTimeMillis();
        try (GdbSqlResult rs = ds.executeSqlRows(sql, bbox)) {
            System.out.println("    列: " + rs.getColumnNames());
            int n = 0;
            for (Object[] row : rs) {
                n++;
                if (n <= PREVIEW_ROWS) {
                    System.out.println("    " + render(row));
                }
            }
            System.out.println("    命中 " + n + " 行,耗时 "
                    + (System.currentTimeMillis() - t0) + " ms");
        } catch (OpenFileGdbException e) {
            System.out.println("    失败: " + e.getMessage());
        }
    }

    /**
     * 把 {@code executeSql} 的结果<b>当普通图层读</b>。
     *
     * <p>这就是 GDAL 里 {@code ExecuteSQL()} 返回 {@code OGRLayer*} 的用法:
     * {@code GetLayerDefn()} 看字段、{@code GetNextFeature()} 逐条拉、
     * {@code GetFID()/GetField()/GetGeometryRef()} 取值,用完
     * {@code ReleaseResultSet()}。
     */
    private static void runAsLayer(OpenFileGdbDataset ds, String table) {
        System.out.println();
        System.out.println("--- 结果当图层读(对标 GDAL ExecuteSQL 返回的 OGRLayer)");
        GdbLayer rs = ds.executeSql("SELECT * FROM " + table + " LIMIT 3");
        try {
            System.out.println("    层名: " + rs.getName() + "  几何列: '"
                    + rs.getGeometryColumn() + "'  FID 列: '" + rs.getFIDColumn() + "'");
            GdbFeatureDefn defn = rs.getLayerDefn();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < defn.getFieldCount(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                GdbField f = defn.getFieldDefn(i);
                sb.append(f.getName()).append(" (").append(f.getTypeName()).append(')');
            }
            System.out.println("    字段: " + sb);
            System.out.println("    几何类型: " + rs.getGeomType().ogrName());

            GdbFeature f;
            while ((f = rs.getNextFeature()) != null) {
                String wkt = f.getGeometry() == null
                        ? "NULL"
                        : f.getGeometry().toWkt();
                System.out.println("    FID=" + f.getFID() + "  geom="
                        + (wkt.length() > 50 ? wkt.substring(0, 50) + "..." : wkt));
            }
        } finally {
            ds.releaseResultSet(rs);
        }
    }

    /** 打印一行:几何出 WKT,字符串加引号,空值写 NULL。 */
    private static String render(Object[] row) {
        StringBuilder sb = new StringBuilder();
        for (Object v : row) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            if (v == null) {
                sb.append("NULL");
            } else if (v instanceof GdbGeometry) {
                String wkt = ((GdbGeometry) v).toWkt();
                sb.append(wkt.length() > 60 ? wkt.substring(0, 60) + "..." : wkt);
            } else if (v instanceof String) {
                sb.append('\'').append(v).append('\'');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
