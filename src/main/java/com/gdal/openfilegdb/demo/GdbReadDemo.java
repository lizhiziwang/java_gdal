package com.gdal.openfilegdb.demo;

import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.exception.OpenFileGdbException;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.srs.SpatialReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 读演示:打开一个 {@code .gdb},打印图层 / 字段 / 空间参考 / 范围 / 全部要素,
 * 并演示按范围查询与按属性过滤。
 *
 * <p>运行(在工程根目录,先 {@code mvn -q clean package}):
 * <pre>
 *   java -cp "target/classes;&lt;slf4j-api.jar&gt;" \
 *       com.gdal.openfilegdb.demo.GdbReadDemo &lt;某个.gdb&gt;
 * </pre>
 * 不传参数时默认读 {@code target/demo-out/write-demo.gdb}(可先跑 {@link GdbWriteDemo} 生成)。
 */
public final class GdbReadDemo {

    private GdbReadDemo() {
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
            System.out.println("打开 FileGDB: " + ds.getPath());
            System.out.println("可写: " + ds.isWritable());
            System.out.println("图层数: " + ds.getLayerCount() + " -> " + ds.getLayerNames());

            // 按序号遍历图层(0 基,序号即 getLayerNames() 的下标)。
            // 与 ds.getLayer(name) 等价、同一对象;系统表不参与序号空间。
            for (int i = 0; i < ds.getLayerCount(); i++) {
                OpenFileGdbLayer layer = ds.getLayer(i);
                System.out.println();
                System.out.println("######## 图层 #" + i + " ########");
                printLayer(layer);
            }

            demonstrateQueries(ds);
        } catch (OpenFileGdbException e) {
            System.err.println("FileGDB 操作失败: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printLayer(OpenFileGdbLayer layer) {
        System.out.println();
        System.out.println("==================================================================");
        System.out.println("图层: " + layer.getName());
        System.out.println("  几何类型: " + layer.getGeometryType());
        SpatialReference srs = layer.getSpatialReference();
        String srsName = srs.getName();
        String srsTag = srsName == null || srsName.isEmpty() ? "" : " (" + srsName + ")";
        System.out.println("  空间参考: wkid=" + srs.getWkid() + srsTag);
        System.out.println("  要素数: " + layer.getFeatureCount());
        double[] ext = layer.getExtent();
        System.out.println("  范围: " + (ext == null ? "(未记录)" : Arrays.toString(ext)));
        System.out.println("  字段(" + layer.getFields().size() + "):");
        for (GdbField f : layer.getFields()) {
            System.out.printf("    %-12s %-9s 长度=%-5d 可空=%-5s 必填=%-5s 别名=%s%n",
                    f.getName(), f.getType(), f.getLength(),
                    f.isNullable(), f.isRequired(),
                    f.getAlias() == null ? "" : f.getAlias());
        }
        System.out.println("  要素:");
        for (GdbFeature f : layer) {
            System.out.println("    " + describe(f));
        }
    }

    private static void demonstrateQueries(OpenFileGdbDataset ds) {
        // 挑第一个空间图层做示例(按序号遍历,与按名字取等价)
        OpenFileGdbLayer spatial = null;
        for (int i = 0; i < ds.getLayerCount(); i++) {
            OpenFileGdbLayer l = ds.getLayer(i);
            if (l.getGeometryType() == GeometryType.POINT) {
                spatial = l;
                break;
            }
        }
        if (spatial == null) {
            return;
        }
        System.out.println();
        System.out.println("==================================================================");
        System.out.println("查询演示 —— 图层 " + spatial.getName());

        // 1) 按范围查询(走 .gdbindex 空间索引,索引缺失时自动退化为全表扫描)
        List<GdbFeature> inBox = spatial.search(110.0, 20.0, 125.0, 40.0);
        System.out.println("  search(110,20,125,40) -> " + inBox.size() + " 条");
        for (GdbFeature f : inBox) {
            System.out.println("    " + describe(f));
        }

        // 2) 按属性过滤(OGR 风格谓词)
        List<GdbFeature> big = spatial.where(
                f -> f.getAttribute("POP") instanceof Number
                        && ((Number) f.getAttribute("POP")).intValue() > 20_000_000);
        System.out.println("  where(POP > 2000 万) -> " + big.size() + " 条");
        for (GdbFeature f : big) {
            System.out.println("    " + describe(f));
        }

        // 3) 按 OID 精确取一条
        if (spatial.getFeatureCount() > 0) {
            GdbFeature first = spatial.getFeature(1);
            System.out.println("  getFeature(1) -> " + (first == null ? "(空槽)" : describe(first)));
        }
    }

    private static String describe(GdbFeature f) {
        StringBuilder sb = new StringBuilder("OID=").append(f.getOid());
        for (Map.Entry<String, Object> e : f.getAttributes().entrySet()) {
            sb.append("  ").append(e.getKey()).append('=').append(e.getValue());
        }
        if (f.hasGeometry()) {
            GdbGeometry g = f.getGeometry();
            sb.append("  ").append(g.toWkt());
        }
        return sb.toString();
    }
}
