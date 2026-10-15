package com.gdal.openfilegdb.demo;

import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.exception.OpenFileGdbException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.srs.SpatialReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 写演示:新建一个 {@code .gdb},建一个点要素类 + 一张非空间表,写入要素,最后回读校验。
 *
 * <p>运行(在工程根目录,先 {@code mvn -q clean package}):
 * <pre>
 *   java -cp "target/classes;&lt;slf4j-api.jar&gt;" com.gdal.openfilegdb.demo.GdbWriteDemo [输出目录]
 * </pre>
 * 不传参数时默认写到 {@code target/demo-out/write-demo.gdb}。
 *
 * <p>写出的库可直接用 GDAL 验证:
 * <pre>
 *   ogrinfo -al -so target/demo-out/write-demo.gdb
 *   ogrinfo -al target/demo-out/write-demo.gdb 城市
 * </pre>
 */
public final class GdbWriteDemo {

    private GdbWriteDemo() {
    }

    /**
     * 入口。
     *
     * @param args 可选:{@code [0]} 输出目录
     */
    public static void main(String[] args) {
        Path outDir = args.length > 0 ? Paths.get(args[0]) : Paths.get("target", "demo-out");
        Path gdbPath = outDir.resolve("write-demo.gdb");

        try {
            // ---- 1. 建库 ----
            // create() 会写出 gdb / timestamps / a00000001..a00000007 七张系统表
            try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdbPath)) {
                System.out.println("已新建 FileGDB: " + ds.getPath());
                System.out.println("可写: " + ds.isWritable());

                // ---- 2. 建点要素类 ----
                // 注意:fields 里只放"用户属性字段";OBJECTID 与 Shape 由库自动补在最前面。
                List<GdbField> cityFields = new ArrayList<>();
                cityFields.add(new GdbField("NAME", FieldType.STRING).setLength(64));
                cityFields.add(new GdbField("POP", FieldType.INT32));
                cityFields.add(new GdbField("AREA_KM2", FieldType.FLOAT64));
                cityFields.add(new GdbField("FOUNDED", FieldType.DATETIME));
                cityFields.add(new GdbField("NOTE", FieldType.STRING)
                        .setLength(255).setNullable(true));

                OpenFileGdbLayer cities = ds.createLayer("城市", SpatialReference.fromEpsg(4326),
                        GeometryType.POINT, cityFields);
                System.out.println("已建要素类: " + cities.getName()
                        + " 几何=" + cities.getGeometryType()
                        + " SRS=" + cities.getSpatialReference());

                // ---- 3. 写要素 ----
                addCity(cities, "北京", 116.4074, 39.9042, 21_893_000,
                        16_410.0, LocalDateTime.of(1949, 10, 1, 0, 0), "首都");
                addCity(cities, "上海", 121.4737, 31.2304, 24_870_000,
                        6_340.5, LocalDateTime.of(1927, 7, 7, 0, 0), "直辖市");
                addCity(cities, "广州", 113.2644, 23.1291, 18_680_000,
                        7_434.0, LocalDateTime.of(1921, 2, 15, 0, 0), null);
                addCity(cities, "成都", 104.0665, 30.5723, 21_192_000,
                        14_335.0, LocalDateTime.of(1921, 3, 1, 0, 0), "天府");

                // ---- 4. 建一张非空间表 ----
                List<GdbField> statFields = new ArrayList<>();
                statFields.add(new GdbField("YEAR", FieldType.INT16));
                statFields.add(new GdbField("CITY", FieldType.STRING).setLength(32));
                statFields.add(new GdbField("GDP", FieldType.FLOAT64));
                OpenFileGdbLayer stats = ds.createTable("城市统计", statFields);
                stats.createFeature(newFeature()
                        .setAttribute("YEAR", (short) 2023)
                        .setAttribute("CITY", "北京")
                        .setAttribute("GDP", 43760.7));
                stats.createFeature(newFeature()
                        .setAttribute("YEAR", (short) 2023)
                        .setAttribute("CITY", "上海")
                        .setAttribute("GDP", 47218.7));

                // ---- 5. 落盘 ----
                ds.flush();
                System.out.println("图层清单: " + ds.getLayerNames());
            }

            // ---- 6. 重新只读打开并校验 ----
            System.out.println();
            System.out.println("---- 回读校验 ----");
            try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdbPath)) {
                System.out.println("图层清单: " + ds.getLayerNames());
                for (String name : ds.getLayerNames()) {
                    OpenFileGdbLayer layer = ds.getLayer(name);
                    System.out.println();
                    System.out.println("图层 " + layer.getName()
                            + "  要素数=" + layer.getFeatureCount()
                            + "  几何=" + layer.getGeometryType());
                    System.out.println("  范围: " + java.util.Arrays.toString(layer.getExtent()));
                    System.out.print("  字段:");
                    for (GdbField f : layer.getFields()) {
                        System.out.print(" " + f.getName() + ":" + f.getType()
                                + "(" + f.getLength() + ")");
                    }
                    System.out.println();
                    for (GdbFeature f : layer) {
                        printFeature(f);
                    }
                }
            }
            System.out.println();
            System.out.println("完成。用 GDAL 验证:");
            System.out.println("  ogrinfo -al -so \"" + gdbPath + "\"");
        } catch (OpenFileGdbException e) {
            System.err.println("FileGDB 操作失败: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void addCity(OpenFileGdbLayer layer, String name, double x, double y,
                                int population, double areaKm2,
                                LocalDateTime founded, String note) {
        GdbFeature f = newFeature()
                .setAttribute("NAME", name)
                .setAttribute("POP", population)
                .setAttribute("AREA_KM2", areaKm2)
                .setAttribute("FOUNDED", founded);
        if (note != null) {
            f.setAttribute("NOTE", note);
        }
        f.setGeometry(GdbGeometry.point(x, y));
        long oid = layer.createFeature(f);
        System.out.println("  写入 " + name + " -> OID " + oid);
    }

    private static GdbFeature newFeature() {
        return new GdbFeature();
    }

    private static void printFeature(GdbFeature f) {
        StringBuilder sb = new StringBuilder("  OID=").append(f.getOid());
        for (Map.Entry<String, Object> e : f.getAttributes().entrySet()) {
            sb.append("  ").append(e.getKey()).append('=').append(e.getValue());
        }
        if (f.hasGeometry()) {
            sb.append("  GEOM=").append(f.getGeometry().toWkt());
        }
        System.out.println(sb);
    }
}
