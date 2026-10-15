# java-openfilegdb —— 纯 Java 的 Esri FileGDB(.gdb)矢量数据读写库

本工程是 GDAL `ogr/ogrsf_frmts/openfilegdb/` C++ 驱动的一次**纯 Java 重写**。
它直接按字节解析 / 生成 Esri File Geodatabase(`.gdb` 目录)的二进制格式,
**不链接 GDAL、不使用 JNI/JNA、不依赖 Esri FileGDB SDK、也不依赖 GeoTools**。

- 语言级别:**Java 8**(class 文件 major version 52)
- 构建:**Maven**
- 日志:**SLF4J**(仅 API,实现由使用方决定)
- 测试:**JUnit 5**(83 个用例)
- 许可:**GPL v2+**(与 GDAL 上游派生产物保持一致)

---

## 1. 它能做什么

| 能力 | 状态 |
| --- | --- |
| 打开 `.gdb`,枚举要素类 / 表 | ✅ |
| 按名字或**序号**取图层 / 表 | ✅ |
| 解析 `.gdbtable` 主头 + 字段描述区 | ✅ |
| 解析记录流(无块、无 RLE,`[uint32 len][body]` 平铺) | ✅ |
| 解析 `.gdbtablx` 行索引(offsetSize 4/5/6、base block map) | ✅ |
| 读取全部字段类型 | ✅ INT16/32/64、FLOAT32/64、STRING、DATETIME、DATE、TIME、GUID、GLOBALID、BINARY、XML |
| 空值位图(LSB-first,1 bit / 可空字段) | ✅ |
| FileGDB 原生几何 → WKB / WKT | ✅ Point / MultiPoint / Line / Polygon / MultiPatch / Z / M |
| 几何包围盒前缀快速探测(`peekEnvelope`) | ✅ |
| 读取 SRS(WKT + WKID)、字段别名 / 可空 / 必填 / 最大宽度 | ✅ |
| 读取 `.gdbindexes` + `.spx` 空间索引并按范围过滤(精确几何相交) | ✅ |
| 按 OID 取要素、全表遍历、属性谓词过滤 | ✅ |
| **OGR 风格图层 API**(`GdbLayer`:`GetNextFeature` / `GetLayerDefn` / `SetSpatialFilterRect` / `TestCapability` …) | ✅ 见 [§4.1](#41-api-与-gdalogr-的命名对照) |
| **SQL 查询**(`executeSql`,对齐 GDAL `ExecuteSQL`,**返回图层**) | ✅ 见 [§4.5](#45-sql-查询executesql) |
| **新建** `.gdb`(gdb / timestamps / a00000001~a00000007 系统表) | ✅ |
| **新建**要素类 / 非空间表(写字段、SRS、几何类型) | ✅ |
| **写**要素(属性序列化 + FileGDB 几何编码 + 更新 `.gdbtablx`) | ✅ |
| 追加 / 更新 / 删除要素 | ✅ |
| 写空间索引(`.spx`) | ❌ 见 [已知限制](#7-已知限制) |

---

## 2. 目录结构

```
src/main/java/com/gdal/openfilegdb/
├── dataset/    OpenFileGdbDataset(数据集)、GdbSystemCatalog(系统目录)、GdbTemplates(建库样板)
├── layer/      GdbLayer(图层接口 = OGR 的 OGRLayer)、OpenFileGdbLayer(库里的表)、
│               GdbFeatureDefn(= OGRFeatureDefn)
├── table/      GdbTable(核心 .gdbtable 读写)、GdbTableIndex(.gdbtablx)
├── feature/    GdbFeature、GdbField、GdbGeomField、GdbGeometry、
│               FileGdbGeometryCodec(几何编解码)、FieldType、GeometryType、ShapeType
├── sql/        GdbSqlEngine(executeSql 入口)、GdbSqlTable(Calcite 表 + 下推)、
│               GdbSqlFilters(谓词下推)、GdbSqlLayer(结果图层 = OGRGenSQLResultsLayer)、
│               GdbSqlResult(原始行游标)、GdbSqlTypes
├── index/      GdbSpatialIndex(.gdbindexes + .spx 读)
├── srs/        SpatialReference、WktSupport
├── io/         LittleEndian、VarInt、Utf16、ByteReader、ByteWriter
├── util/       GdbDateTimeUtil、GuidUtil、BitArray、GdbCollections
├── exception/  OpenFileGdbException 及其 8 个子类
└── demo/       GdbReadDemo、GdbWriteDemo、GdbSqlDemo
```

规模(不含空行/注释统计口径为总行数):主代码 **49 个文件 / ~16.4k 行**,测试 **7 个文件 / ~2.3k 行**。

### 2.1 分层与依赖方向

```
        demo  ──►  dataset / layer / feature / srs  ──►  table  ──►  io / util
                     │                        │
                     ├──► index               ├──► feature ──► io / util
                     ├──► srs                 └──► exception
                     └──► sql ──► layer / table / feature(Calcite 只是执行引擎)
```

- `io` / `util` / `exception` 是叶子层,不反向依赖任何业务包。
- `table` 不认识 `layer` 与 `dataset`:`GdbTable` 只关心一个 `.gdbtable` 文件。
- `dataset` 负责把"目录里的一堆文件 + 系统表 + 用户图层"串起来,是唯一对外的主入口。
- `sql` 是 `dataset` 之上的**查询门面**:`OpenFileGdbDataset.executeSql()` 把当前图层
  交给 `GdbSqlEngine`,后者用 Apache Calcite 解析并流式执行(见
  [§4.5](#45-sql-查询executesql) 与 [§7.4](#74-sql-层的取舍)）。
- 数据模型完全对齐 **OGR**:`Dataset → Layer → Feature → Field / Geometry`。

### 2.2 与 GDAL C++ 源码的对应关系

工程根目录 `reference/cpp/` 保存了撰写时逐行对照的上游源码(只读参考)。
主要类头部注释里都写明了对应位置,概览:

| 本工程 | GDAL C++ |
| --- | --- |
| `GdbTable`(读) | `filegdbtable.cpp` —— `FileGDBTable::Open` / `ReadTableXHeader` / `ReadFields` |
| `GdbTable`(写) | `filegdbtable_write.cpp`、`filegdbtable_write_fields.cpp` |
| `GdbTableIndex` | `filegdbtable.cpp` 的 `OpenTableX` / `FileGDBTable::GetOffsetInTableX` |
| `FileGdbGeometryCodec` | `filegdbtable.cpp` 的 `ReadFeature` 几何分支、`ogrpgeogeometry.h` |
| `GdbSpatialIndex` | `filegdbindex.cpp` |
| `OpenFileGdbDataset` | `ogropenfilegdbdatasource.cpp` / `_write.cpp` |
| `GdbTemplates` | `_write.cpp` 的 `CreateGDBSystemCatalog` … `CreateGDBItemRelationshipTypes` |
| `ShapeType` 常量 | `ogrpgeogeometry.h`(GDAL 私有编号,**与 shapefile 公开规范不同**:Z/ZM 互换) |
| `GdbDateTimeUtil.ESRI_NAN` | `filegdbtable.cpp::getESRI_NAN` |
| `GdbDateTimeUtil.daysToDateTime` | `filegdbtable.cpp::FileGDBDoubleDateToOGRDate` |
| `OpenFileGdbLayer` | `ogropenfilegdblayer.cpp`(`OGROpenFileGDBLayer`) |
| `GdbLayer` / `GdbFeatureDefn` | `OGRLayer` / `OGRFeatureDefn`(命名一一对应,见 [§4.1](#41-api-与-gdalogr-的命名对照)) |
| `GdbSqlLayer` | `ogr_gensql.cpp` 的 `OGRGenSQLResultsLayer`(`ExecuteSQL` 返回的那个图层) |
| `GdbSqlEngine` / `GdbSqlResult` | `ExecuteSQL()` + `ogr_gensql.cpp`(逐条拉取语义;执行引擎换成 Calcite) |

另有 Python 参考实现(逐字节校验过的 `pyopenfilegdb`)用于交叉核对,尤其是
几何 delta-varint 编码与 `.spx` 页结构。

---

## 3. 构建与运行

### 3.1 环境

- JDK 8 或以上(编译目标为 8,高版本 JDK 也可以构建)
- Maven 3.6+

```bash
# 确认 JDK
java -version      # 期望 1.8.x 或更高
mvn -v
```

> **注意**:若环境里 `JAVA_HOME` 指向别处,请显式覆盖:
> ```bash
> export JAVA_HOME=/path/to/jdk1.8.0_xxx
> export PATH="$JAVA_HOME/bin:$PATH"
> ```

### 3.2 编译 / 测试 / 打包

```bash
mvn clean compile     # 只编译
mvn clean test        # 编译 + 跑 83 个单测
mvn clean package     # 产出 target/java-openfilegdb-1.0.0-SNAPSHOT.jar
```

### 3.3 IDEA 里导入

`File → Open` 选 `pom.xml` 即可。**没有任何 native 依赖**,不需要配置
`java.library.path`、不需要 OSGeo4W、不需要 `gdal.dll`。

### 3.4 跑 Demo

`slf4j-api` 需要放在 classpath 上(本工程只声明了 API,没绑实现);
`GdbSqlDemo` 还需要 Calcite 及其传递依赖,最省事的办法是让 Maven 打出 classpath:

```bash
mvn -q clean package -DskipTests
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

# Windows(cmd / PowerShell),注意 classpath 分隔符是分号
java -cp "target/classes;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.13\slf4j-api-2.0.13.jar" ^
     com.gdal.openfilegdb.demo.GdbWriteDemo

# Linux / macOS / Git Bash
java -cp "target/classes:$HOME/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar" \
     com.gdal.openfilegdb.demo.GdbWriteDemo

# SQL 演示(用 Maven 生成的完整 classpath)
java -cp "target/classes:$(cat target/cp.txt)" \
     com.gdal.openfilegdb.demo.GdbSqlDemo target/demo-out/write-demo.gdb
```

- `GdbWriteDemo` 新建一个 `.gdb`,建一个点要素类 + 一张非空间表,写入要素并回读校验。
  默认输出 `target/demo-out/write-demo.gdb`。
- `GdbReadDemo <某个.gdb>` 打开库,打印图层 / 字段 / SRS / 范围 / 全部要素,
  并演示范围查询与属性过滤。不传参数就读 `GdbWriteDemo` 的产物。
- `GdbSqlDemo <某个.gdb>` 演示 `executeSql`:投影 / 聚合 / 排序 / `bbox` 空间过滤、
  **把结果当普通图层读**(`GetLayerDefn` / `GetNextFeature` / `GetFID`),
  以及写语句被拒绝的语义。

Windows 控制台默认是 GBK,想看到正确的中文输出可以加:
`-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8`。

### 3.5 用 GDAL 交叉验证写出的库

```bash
ogrinfo -al -so target/demo-out/write-demo.gdb
ogrinfo -al    target/demo-out/write-demo.gdb 城市
```

实测输出(节选)——驱动识别为 `OpenFileGDB`,范围、字段类型、中文字符串、
`NULL`、日期时间全部一致:

```
Layer name: 城市
Geometry: Point
Feature Count: 4
Extent: (104.066500, 23.129100) - (121.473700, 39.904200)
Layer SRS WKT: ... ID["EPSG",4326]
NAME: String (64.0)
POP: Integer (0.0)
AREA_KM2: Real (0.0)
FOUNDED: DateTime
NOTE: String (255.0)

OGRFeature(城市):1
  NAME (String) = 北京
  POP (Integer) = 21893000
  FOUNDED (DateTime) = 1949/10/01 00:00:00+00
  NOTE (String) = 首都
  POINT (116.4074 39.9042)
```

反向也测过:用 `ogr2ogr -f OpenFileGDB` 生成含多边形(带洞)/线/多线/点的库,
本库能正确读回,`.spx` 空间索引也能被 `GdbSpatialIndex` 正确解析。

---

## 4. API 示例

### 4.1 API 与 GDAL/OGR 的命名对照

本库的公开 API 是**照着 OGR 的 `OGRLayer` / `OGRFeature` / `OGRFeatureDefn` /
`GDALDataset` 起的名**,照着 GDAL 写代码时可以直接对号入座。两种图层
(`OpenFileGdbLayer` 与 `executeSql` 的结果 `GdbSqlLayer`)实现同一个接口
`com.gdal.openfilegdb.layer.GdbLayer`,所以**读法完全一样**。

**图层(`GdbLayer` ↔ `OGRLayer`)**

| 本库 | OGR / GDAL |
| --- | --- |
| `getName()` | `GetName()`(SQL 结果层返回 `SELECT`,与 GDAL 一致) |
| `getLayerDefn()` | `GetLayerDefn()` |
| `getFields()` / `getField(name)` | `GetLayerDefn()->GetFieldDefn(i)` |
| `getGeomType()` | `GetGeomType()` |
| `getSpatialRef()` | `GetSpatialRef()` |
| `getFeatureCount()` / `getFeatureCount(force)` | `GetFeatureCount()` / `GetFeatureCount(bForce)` |
| `getExtent()` / `getExtent(force)` | `GetExtent(&sExtent, bForce)` |
| `resetReading()` | `ResetReading()` |
| `getNextFeature()` | `GetNextFeature()` |
| `getFeature(oid)` | `GetFeature(fid)` |
| `setSpatialFilterRect(x0,y0,x1,y1)` | `SetSpatialFilterRect(...)` |
| `setSpatialFilter(double[] rect)` / `clearSpatialFilter()` | `SetSpatialFilter(geom)` / `SetSpatialFilter(nullptr)` |
| `getSpatialFilter()` | `GetSpatialFilter()` |
| `getFIDColumn()` / `getGeometryColumn()` | `GetFIDColumn()` / `GetGeometryColumn()` |
| `testCapability("OLCxxx")` | `TestCapability(OLCxxx)` |
| `createFeature` / `updateFeature` / `deleteFeature` | `CreateFeature` / `SetFeature` / `DeleteFeature` |
| `iterator()`(返回 `this`) | OGR 没有,Java 便利;单游标语义与 OGR 一致 |

**要素定义(`GdbFeatureDefn` ↔ `OGRFeatureDefn`)**:`getFieldCount()` /
`getFieldDefn(i)` / `getFieldIndex(name)`(大小写不敏感,缺失 `-1`)/
`getGeomFieldCount()` / `getGeomFieldDefn(0)` / `getGeometryFieldIndex()` /
`getObjectIdFieldIndex()`。

**要素(`GdbFeature` ↔ `OGRFeature`)**:`getFID()` / `setFID()` /
`getField(name)` / `isFieldSet(name)` /
`getFieldAsInteger` / `getFieldAsInteger64` / `getFieldAsDouble` /
`getFieldAsString` / `getFieldAsBoolean` / `getGeometryRef()`。
(`getOid()` / `getAttribute()` / `getGeometry()` 是同义的旧叫法,保留兼容。)

**字段(`GdbField` ↔ `OGRFieldDefn`)**:`getName()` / `getTypeName()`
(返回 `ogrinfo` 里那个 `Integer / Integer64 / Real / String / Date / Time /
DateTime / Binary`)/ `getWidth()` / `getPrecision()`。

**数据集(`OpenFileGdbDataset` ↔ `GDALDataset`)**:`open(path)` /
`getLayerCount()` / `getLayer(int)` / `getLayer(String)` / `getLayerByName(String)` /
`getName()` / `testCapability("ODsCxxx")` / `executeSql(sql[, bbox])` /
`releaseResultSet(layer)` / `createLayer` / `createTable` / `flush()` / `close()`。

> ⚠️ 一处刻意的差异:`GetFeature(fid)` 在 OGR 里也要按 `fid` 定位,本库
> `OpenFileGdbLayer.getFeature(oid)` 同样**绕过空间过滤**(过滤器只作用于
> `GetNextFeature`);而 `GdbSqlLayer.getFeature()` 恒为 `null` ——
> SQL 结果层没有随机读,与 GDAL 的 GenSQL 层一样。

**空间过滤是"精确几何相交",不是"包围盒相交"**:`GdbGeometry.intersectsRect()`
逐段做线段-矩形裁剪、面还额外判"矩形中心是否落在多边形内"(奇偶规则处理洞)。
与 GDAL 的 `Intersects` 同口径 —— 实测在 21217 个村界多边形上,bbox 取 extent
左下四分之一时,只按包围盒会多出 1 条(凹多边形的包围盒搭上边、实体没进去),
精确判定后与 `ogrinfo -spat` / `ogr2ogr -spat` 的命中集合**逐条 ID 完全一致**。

### 4.2 读一个 `.gdb`

```java
import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;

try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(Paths.get("data/city.gdb"))) {
    System.out.println(ds.getLayerNames());          // [城市, 河流, ...]
    System.out.println(ds.getLayerCount());          // 3

    OpenFileGdbLayer layer = ds.getLayer("城市");
    System.out.println(layer.getGeometryType());     // POINT
    System.out.println(layer.getSpatialReference()); // SpatialReference[wkid=4326, ...]
    System.out.println(layer.getFeatureCount());
    System.out.println(Arrays.toString(layer.getExtent()));

    for (GdbField f : layer.getFields()) {
        System.out.printf("%-12s %-9s len=%d nullable=%s%n",
                f.getName(), f.getType(), f.getLength(), f.isNullable());
    }

    // 全表遍历(Layer 实现了 Iterable<GdbFeature> 与 Iterator<GdbFeature>)
    for (GdbFeature feat : layer) {
        System.out.println(feat.getOid() + " " + feat.getAttribute("NAME")
                + " " + (feat.hasGeometry() ? feat.getGeometry().toWkt() : "(无几何)"));
    }
}
```

取图层有两种方式,等价可互换:

```java
// 1) 按名字(大小写不敏感;逻辑名 / 物理名 a00000009 / 系统表名都接受)
OpenFileGdbLayer byName = ds.getLayer("城市");
assertEquals(byName, ds.getLayer("a00000009"));   // 物理名也行

// 2) 按序号(0 基)。序号即 getLayerNames() 的下标,范围 [0, getLayerCount())
for (int i = 0; i < ds.getLayerCount(); i++) {
    OpenFileGdbLayer l = ds.getLayer(i);
    System.out.println(i + " -> " + l.getName());
}
```

> ⚠️ 序号空间**只覆盖用户图层**。系统表(`GDB_SystemCatalog` / `GDB_Items` 等)不参与计数,
> 也不能用序号取 —— 这与 GDAL 把用户图层收在 `m_apoLayers`、系统表收在
> `m_apoHiddenLayers` 的分法是同一口径。需要系统表时用 `getLayer(String)`。
> 序号越界(含负数)抛 `GdbNotFoundException`,消息里会带上有效范围与现有图层名。

三种查询方式:

```java
// 1) 按 OID 精确取(1 基;空槽返回 null)
GdbFeature one = layer.getFeature(1);

// 2) 按范围查(优先走 .spx 空间索引;索引缺失时自动退化为全表扫描并告警)
List<GdbFeature> inBox = layer.search(110.0, 20.0, 125.0, 40.0);

// 3) 属性谓词
List<GdbFeature> big = layer.where(
        f -> f.getAttribute("POP") instanceof Number
                && ((Number) f.getAttribute("POP")).intValue() > 20_000_000);
```

`layer.reset()` 可以把迭代游标复位,便于重复遍历。

### 4.3 新建一个 `.gdb` 并写要素

```java
import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.feature.*;
import com.gdal.openfilegdb.srs.SpatialReference;
import static com.gdal.openfilegdb.util.GdbCollections.list;

try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(Paths.get("out/demo.gdb"))) {
    // fields 里只放"用户属性字段":OBJECTID 与 Shape 由库自动补在最前面
    List<GdbField> fields = list(
            new GdbField("NAME", FieldType.STRING).setLength(64),
            new GdbField("POP", FieldType.INT32),
            new GdbField("SEEN", FieldType.DATETIME));

    OpenFileGdbLayer layer = ds.createLayer("城市",
            SpatialReference.fromEpsg(4326), GeometryType.POINT, fields);

    GdbFeature f = new GdbFeature()
            .setAttribute("NAME", "北京")
            .setAttribute("POP", 21_893_000)
            .setAttribute("SEEN", LocalDateTime.of(2024, 3, 8, 10, 30, 15))
            .setGeometry(GdbGeometry.point(116.4074, 39.9042));

    long oid = layer.createFeature(f);

    // 非空间表
    OpenFileGdbLayer stats = ds.createTable("统计",
            list(new GdbField("YEAR", FieldType.INT16),
                 new GdbField("GDP", FieldType.FLOAT64)));
    stats.createFeature(new GdbFeature()
            .setAttribute("YEAR", (short) 2023)
            .setAttribute("GDP", 43760.7));

    ds.flush();   // 落盘(close() 也会 flush)
}
```

更新 / 删除:

```java
f.setAttribute("POP", 22_000_000);
layer.updateFeature(f);        // 需要以可写方式打开数据集

layer.deleteFeature(3);        // 返回 boolean
```

打开可写数据集用 `OpenFileGdbDataset.openForWrite(path)`。

### 4.4 直接操作单张表(更底层)

```java
// 建表:字段清单里 OBJECTID / 几何字段自己给
GdbGeomField shape = new GdbGeomField("Shape");
shape.setXOrigin(-180).setYOrigin(-90).setXyScale(1e9);
shape.setXyTolerance(2e-6);
shape.setWkt(WktSupport.builtinWkt(4326));
shape.setSpatialIndexGridResolutions(new double[]{0.012, 0.4, 12.0});

try (GdbTable t = GdbTable.create(tablePath,
        list(new GdbField("OBJECTID", FieldType.OBJECTID).setRequired(true).setNullable(false),
             shape,
             new GdbField("NAME", FieldType.STRING).setLength(32)),
        GeometryType.POLYGON.tableGeomType(), false, false, false /* utf8 */, null, 5)) {
    GdbFeature f = new GdbFeature()
            .setAttribute("NAME", "square")
            .setGeometry(GdbGeometry.polygon(
                    new double[]{0, 10, 10, 0, 0}, new double[]{0, 0, 10, 10, 0},
                    new int[]{0, 5}, new int[]{0}));
    t.appendFeature(f);
}
```

> `spatialIndexGridResolutions` 必须落在 **1~3** 个之间:GDAL 读侧会
> `returnErrorIf(nGridSizeCount == 0 || nGridSizeCount > 3)`
> (`filegdbtable.cpp:1412-1421`),写 0 个会产出一个连 GDAL 都打不开的文件。
> 本库在 `create()` 里做了兜底归一化:未指定时回落到 ArcGIS 默认值
> `{0.012, 0.4, 12.0}` 并打 WARN 日志,超过 3 个则截断。

### 4.5 SQL 查询(`executeSql`)

对齐 GDAL `GDALDataset::ExecuteSQL(pszSQL, poSpatialFilter, pszDialect)`:**返回一个
图层**(对应 GDAL 返回的 `OGRLayer*`,实际是 `OGRGenSQLResultsLayer`),可以
**跟普通图层一样读**;用完 `ds.releaseResultSet(layer)`(对应 `ReleaseResultSet`)。

```java
try (OpenFileGdbDataset ds = OpenFileGdbDataset.open("data.gdb")) {
    GdbLayer rs = ds.executeSql("SELECT OBJECTID, NAME, Shape FROM poi WHERE score > 90");
    try {
        System.out.println(rs.getName());            // "SELECT"(与 GDAL 相同)
        System.out.println(rs.getLayerDefn().getFieldCount());
        GdbFeature f;
        while ((f = rs.getNextFeature()) != null) {  // OGR 式单游标
            System.out.println(f.getFID() + " " + f.getField("NAME")
                    + " " + f.getGeometryRef().toWkt());
        }
        // 或者:for (GdbFeature f : rs) { ... }
    } finally {
        ds.releaseResultSet(rs);
    }
}
```

行 → 要素的映射:

| SQL 列 | 落到要素的哪里 |
| --- | --- |
| 几何列(值是 `GdbGeometry` 的那一列) | `getGeometry()`(与源图层的约定一致,**不**再进属性表) |
| `OBJECTID` 列(名字等于源图层 `getFIDColumn()`) | `getFID()`(同样不进属性表) |
| 其余列 | `getField("列名")`,列名保持 SQL 里写出的形式(别名优先) |
| 聚合/分组(没有 OBJECTID 列) | FID 退化成从 1 开始的行号,`getFIDColumn()` 返回 `""` |

聚合这类"没有 OBJECTID、没有几何"的结果,用**原始行**通道更顺手
(`Object[]` 游标,GDAL 里没有对应物,是本库的便利通道):

```java
try (GdbSqlResult rs = ds.executeSqlRows(
        "SELECT name, COUNT(*) AS n FROM poi GROUP BY name ORDER BY n DESC LIMIT 10")) {
    System.out.println(rs.getColumnNames());   // 列名(别名优先)
    for (Object[] row : rs) {
        System.out.println(row[0] + " -> " + row[1]);
    }
}
```

空间过滤走第二个参数(不写进 SQL,等价于 GDAL 的 `poSpatialFilter`);
结果图层上也能直接 `setSpatialFilterRect(...)` —— 那会用新 bbox **重跑一遍查询**:

```java
try (GdbSqlResult rs = ds.executeSql(
        "SELECT OBJECTID FROM \"村行政区划\"", new double[]{110, 20, 125, 40})) {
    // 走 .spx 索引粗筛 + 精确几何相交;索引缺失时退化为全表流式扫
}
```

要点:

| 项 | 说明 |
| --- | --- |
| **方言** | Calcite SQL(SQL:92 级别)。支持 `WHERE / GROUP BY / HAVING / ORDER BY / LIMIT / DISTINCT / JOIN / UNION / 子查询 / WITH / CAST / LIKE / IN` 与 `COUNT/SUM/AVG/MIN/MAX`。**比 OGR SQL 的子集大**(实测 GDAL 的 OGRSQL 连 `GROUP BY` 都不支持,需 `-dialect SQLITE`)。 |
| **流式** | `SELECT/WHERE/LIMIT` 与聚合**全程流式**,内存与结果行数无关;只有 `ORDER BY`(全表排序)、`DISTINCT`、`GROUP BY` 按 SQL 语义物化 —— 取舍与 GDAL 文档一致。 |
| **几何惰性** | 未出现在投影列与过滤条件里的几何列**完全不参与解码**(几何解码是读路径最贵的一步)。 |
| **标识符** | 图层名 / 字段名**大小写不敏感**;含空格或撞上 SQL 保留字时用双引号:`SELECT "VALUE" FROM "图层 A"`。 |
| **返回值** | 日期时间是 `LocalDateTime`(UTC 墙上时钟约定),几何是 `GdbGeometry`(`toWkt()` / `toWkb()` 可用),空值是 `null`。 |
| **只读** | 只接受 `SELECT / WITH / VALUES / TABLE / EXPLAIN`;`DROP TABLE` / `DELLAYER:` 等写命令直接抛 `GdbSqlException` 并提示改用 `createLayer` / `createFeature`。 |
| **结果层的能力** | `getFeatureCount()` 返回 `-1`(OGR 约定:无法快速确定),`force=true` 才数一遍;`getFeature(oid)` 恒 `null`(无随机读);写入方法直接抛 `GdbWriteException`。 |

命令行演示:

```bash
java -cp "target/classes;<calcite 及依赖>" com.gdal.openfilegdb.demo.GdbSqlDemo /path/to/data.gdb
```

---

## 5. 二进制格式要点

写代码/排错时会用到的关键事实:

- **`.gdbtable`** = 40 字节主头 + 字段描述区 + 平铺记录流 `[uint32 recLen][record body]`。
  **没有块、没有 RLE 压缩**。
  主头偏移:`+0` 版本、`+4` 有效记录数、`+8` 记录体最大字节数、`+12/16/20` 魔数 5/0/0、
  `+24` 文件总长、`+32` 字段描述区偏移。
- **`.gdbtablx`** = 16 字节头(`version` / `n1024BlocksPresent` / `nTotalRecordCount` /
  `nOffsetSize`=4|5|6)+ 偏移表(每 1024 条一页)+ 16 字节尾。**偏移 0 = 空槽/已删除**。
- **空值位图**:LSB-first,每个可空字段 1 bit,bit=1 表示 NULL;初值 `0xFF`。
- **变长整数**:unsigned LEB128。
- **字符串**:字段名 / 别名是 UTF-16LE 且带 `[uint8 码元数]` 前缀;
  表数据按表级 `stringsAreUtf8` 决定 UTF-8 还是 UTF-16LE(带 `[uint32 字节数]` 前缀);
  系统表的 `stringsAreUtf8 = true`,用户图层默认 `false`。
- **几何**:`[uint8]` 类型码 + 量化坐标。量化是 `round((coord - origin) * scale)`,
  坐标用 delta-varint 压缩(首字节 bit6 为符号位、低 6 位是数据;后续按 LEB128 续,
  位移从 6 开始每次 +7)。几何字段描述区尾部含 `[uint16]` 长度的 WKT。
- **日期**:`double` 表示自 **1899-12-30 UTC** 起的天数(Excel/OLE 基准),
  空值是 **ESRI NaN `0x7FF8000000000001`**(注意与 Java `Double.NaN`
  `0x7FF8000000000000` **最低有效位差 1**)。
- **GUID**:混合字节序(前 4/2/2 字节反转,后 8 字节顺序),格式化为大写
  `{XXXXXXXX-....}`。
- **物理名**:`String.format("a%08x", catalog 行号 + 1)`;空库 catalog 有 8 条,
  所以**第一个用户图层是 `a00000009`**。
- **定位系统表永远先读 catalog 的 `Name` 反查**,不要硬编码编号 ——
  GDAL 注释里明说 `GDB_Items` "has been seen in some datasets to not be a00000004"。
- **字段定义以物理 `.gdbtable` 为准**,不信 XML ——
  GDAL 原话:"We cannot trust the XML definition to build the field definitions"。

### 5.1 三个容易踩的坑(本库已处理)

1. **多边形环的闭合**:盘上环是**闭合**的(首尾点重复),但内存表示**不存**闭合点。
   编码时补、解码时削;WKT 出口再补回来(OGC 要求)。
   环的绕向也在此刻归一化:**第 1 个环顺时针、其余逆时针**
   (ArcGIS 约定;判据是带符号面积,且要先平移到首顶点,否则大坐标下近零面积的环会判反)。
2. **日期时间的亚秒误差**:天数存 `double`,`days * 86400` 在秒级值上有相对误差,
   `15` 秒会读成 `14.999999523`。GDAL 的对策是"小数部分离 1 秒不到 `1e-4`
   就吸附到整秒"(`filegdbtable.cpp:1873`),本库照抄,所以整秒值能精确往返。
3. **文本化不能出现科学计数法**:`Double.toString` 在 `|v| < 1e-3` 或 `|v| >= 1e7`
   时会退化成 `1.23E-5` / `3.939437078975E7` 这种形态。投影坐标轻轻松松就落在
   1e7 以上(高斯投影假东距 `X ≈ 3.9e7`、WebMercator `≈ 2e7`),WKT 一打印全是
   `E7`,既没法跟 ArcGIS / GDAL 的文本对照,也让日志和 diff 没法看。
   本库统一走 `util/GdbNumbers.plain(double)`:`Double.toString` 拿到最短往返串,
   含 `E`/`e` 时才用 `BigDecimal(...).stripTrailingZeros().toPlainString()` 展平
   (整值仍走 `long` 分支不带小数点)。**精度不丢**:`parse(plain(v)) == v` 恒成立。
   同样的处理也用在 Definition XML 的 `<XMin>` / `<Scale>` 上。

---

## 6. 设计约束

- **线程安全**:读操作可并发。`GdbTable` 的所有 IO 都走
  `FileChannel` 的**定位读写**(`read(ByteBuffer, position)` / `write(..., position)`),
  不共享文件游标。**写操作必须在单线程内串行**。
- **字节序**:全部小端,与 C++ 侧一致;`ByteBuffer` 统一 `LITTLE_ENDIAN`。
- **异常体系**:`OpenFileGdbException`(基类,`RuntimeException`)下分
  `GdbFileNotFoundException` / `GdbFormatException` / `GdbVersionException` /
  `GdbNotFoundException` / `GdbUnsupportedFieldTypeException` / `GdbIoException` /
  `GdbWriteException`,把"文件不存在 / 格式非法 / 不支持的字段类型 / IO 失败"
  区分开,便于调用方分别处理。
- **资源管理**:`OpenFileGdbDataset` / `OpenFileGdbLayer` 之下的
  `GdbTable`、`GdbTableIndex`、`GdbSystemCatalog`、`GdbSpatialIndex` 都实现了
  `AutoCloseable`,推荐一律走 try-with-resources。
- **Java 8 兼容**:`List.of` / `Set.of` / `Map.ofEntries` / `Path.of` /
  `String.isBlank` / `String.strip` / `Stream.toList` / `record` /
  `instanceof` 模式匹配 / 匿名类钻石操作符这些 9+ 的写法一律不用;
  集合工厂由 `util/GdbCollections` 提供 Java 8 版本。
- **"纯 Java"的边界**:唯一的第三方运行时依赖是 **Apache Calcite**
  (`executeSql` 用)与 **slf4j-api**。Calcite 是纯 Java 实现,不碰 JNI;
  它内部用 Janino 在运行时生成字节码,Janino 本身也是纯 Java 编译器,
  所以 §1 的"不链接 GDAL / 不用 JNI"硬约束没有被破坏。

---

## 7. 已知限制

阅读实现或规划使用时请注意以下取舍。

### 7.1 写能力

| 限制 | 说明 |
| --- | --- |
| **不写空间索引** | 不生成 `.gdbindexes` / `.spx`。写出的库用 GDAL `ogrinfo` 可正常打开、可读全部要素,但 GDAL 的范围查询会走全表扫描。用 ArcGIS 打开一次会自己补建。这是刻意留的 TODO。 |
| **不写 Z / M** | `createLayer` 目前不暴露 `hasZ` / `hasM`(`GdbTable` 底层已支持,只是 Dataset 层未开口子)。 |
| **只写版本 3** | 读支持版本 3 与 4;写只支持 3(ArcGIS 10.x)。以可写方式打开版本 4 会抛 `GdbVersionException`。 |
| **不写域(domain)、子类型、关系类** | 不生成 `Range Domain` / `Coded Value Domain` 的 Definition XML。 |
| **不写 `GDB_ReplicaLog`** | 与 GDAL 一致(`a00000001` 的行数据里仍保留那一条,所以物理编号不受影响)。 |
| **不建要素数据集(Feature Dataset)** | 图层都建在根 Folder 下。 |

### 7.2 读能力

| 限制 | 说明 |
| --- | --- |
| **MultiPatch 不导出 WKT/GeoJSON** | FileGDB 的 MultiPatch 存的是 triangle strip / triangle fan,与 OGC 的 Solid / TIN 不是一套模型。读得到几何对象,但 `toWkt()` / `toGeoJson()` 会拒绝而不是给出错误拓扑。 |
| **WKT 输出不是完整 WKT 引擎** | 只做够用的格式化输出,不做坐标轴序、`AXIS`、`AUTHORITY` 等节点。原样保存的 WKT 才是权威。 |
| **WKID 反解依赖内建表** | GDAL 写进 `.gdb` 的 `SRTEXT` 是**没有 `AUTHORITY` 节点**的 ESRI 风格 WKT1,所以先找 `AUTHORITY`/`ID`,找不到再对 `WktSupport` 内建的几个 EPSG(4326 / 3857 / 4490 / 4269 / 3395 / 32650)做归一化比对。**其它坐标系读回的 `wkid` 会是 0**,但 WKT 文本完整保留、不会丢坐标系,`ogrinfo` 也能正常识别。要精确 WKID 请用 `SpatialReference.of(wkid, wkt)` 显式构造。 |
| **不解析 `.gdbtablx` 的 base block map 之外的旧式变体** | 只覆盖 GDAL 支持的布局。 |
| **不读栅格 / 关系类实体** | `FGFT_RASTER` 字段会被识别类型并跳过内容,不解析栅格数据。 |
| **不做拓扑校核** | 读出多边形环后按 GDAL 的口径做环归组,但不做 `organizePolygons` 那种完整拓扑重建。 |

### 7.3 精度与其它

- **日期时间**:整秒值精确往返(见 §5.1);带小数的值受"天数 double"本身限制,
  实测误差在 **1 微秒**以内,达不到纳秒。
- **坐标**:受几何字段 `xyScale`(默认 `1e9`)与 origin 限制,量化后误差在
  `1/scale` 量级(默认 `1e-9` 度)。导入数据时请确认 origin / scale 足够覆盖数据范围,
  否则坐标会被截断。
- **不支持的字段类型**:遇到无法处理的 `FGFT_*` 会抛
  `GdbUnsupportedFieldTypeException` 而不是静默丢弃。
- **空间过滤是几何级判定**:`SetSpatialFilterRect` / `search()` / `executeSql(sql, bbox)`
  都用 `GdbGeometry.intersectsRect()` 做**精确相交**,与 GDAL 的 `Intersects` 同口径
  (不是"包围盒相交")。代价是:包围盒搭上边的要素还要再扫一遍顶点
  (面还会做一次"矩形中心是否在多边形内"的奇偶判定)。绝大多数要素在包围盒那一步
  就被挡掉,实测 21217 个多边形层的流式空间过滤仍在同一量级(约 0.5 s)。
- **`.gdbtable` 记录流不做随机写压缩**:追加/更新以"追加新记录 + 改索引偏移"实现,
  更新后的旧记录体变成不可达的幽灵区域(与 GDAL 的追加式写入一致)。频繁小改会让
  文件变大,必要时可用 GDAL / ArcGIS 压缩一次。

### 7.4 SQL 层的取舍

**为什么是 Calcite**:本库要么自己写 SQL 引擎(像 GDAL 的 `swq.cpp`,几百行起步且
只能覆盖子集),要么借现成的。选 Calcite 的理由:
1. **纯 Java、不碰 JNI**,且 **1.38.0 是仍支持 JDK 8 的最后一档**(与本工程
   `maven.compiler.source=8` 对齐);
2. 它把"表"抽象成 `ScannableTable` / `ProjectableFilterableTable` 接口,
   **下推与流式由我们自己控制** —— 这正好能把 `OGRGenSQLResultsLayer::GetNextFeature`
   的逐条拉取语义照搬过来;
3. 标准 SQL 覆盖面远超 OGR SQL(`JOIN` / 子查询 / 窗口函数 / `WITH` 都能用),
   用户不必迁就 GDAL 自研方言的语法限制。

代价与限制(使用时请注意):

| 限制 | 说明 |
| --- | --- |
| **依赖体积** | Calcite 会带进 guava / jackson / avatica / janino 等,jar 依赖约 **20 MB+**。不需要 SQL 的场景可以不引 `calcite-core`(`sql` 包以外的功能零依赖)。 |
| **每次查询要规划** | 每调一次 `executeSql` 建一个 Calcite 连接并做一次解析 + 规划 + 代码生成(首次约 **1~2 s**,之后同一 SQL 走连接内语句缓存会快很多)。**不要**把它放进逐条循环里,那是 `iterator()` / `where()` 的活。 |
| **保留字要加引号** | 标准 SQL 的保留字(如 `VALUE`、`ORDER`、`GROUP`)作字段名时必须双引号:`SELECT "VALUE" FROM t`。OGR SQL 在这点上更宽松。 |
| **没有空间 SQL 函数** | 不支持 `ST_Intersects` 这类函数(Calcite 没有空间扩展)。空间过滤请用 `executeSql(sql, bbox)`、`layer.setSpatialFilterRect(...)` + `getNextFeature()`,或 `layer.search()`。 |
| **结果层的要素定义要读一行才定型** | 列类型来自 JDBC 元数据,但"哪一列是几何"要看实际值类型,所以 `getLayerDefn()` 会预取第一行(**不丢行**)。空结果集只能靠元数据猜,此时 `getGeometryColumn()` 是 `""`。 |
| **给结果层设 bbox 会重跑查询** | `resultLayer.setSpatialFilterRect(...)` 不是"查完再过滤",而是用新 bbox 重新跑一遍 SQL(能吃到 `.spx` 索引)。 |
| **没有 `fid` 伪列 / 写命令** | OGR SQL 的 `fid` 直接用 `OBJECTID` 代替;GDAL 的 `DELLAYER:` / `DROP TABLE` / `ALTER TABLE` 等写命令本库不做(写走 `createLayer` / `createFeature` 等 API)。 |
| **字符集** | Calcite 的解析器字符集取自系统属性 `calcite.default.charset`,**默认是 ISO-8859-1**;本库在第一次 `executeSql` 时若发现该属性未设置会自动设为 `UTF-8`(见 `GdbSqlBootstrap`)。若你的进程里已有别的 Calcite 用户抢先加载了 Calcite 类,请自行在启动参数里加 `-Dcalcite.default.charset=UTF-8`。 |
| **字符串表达式的 CHAR 语义** | 标准 SQL 里字符串字面量是 `CHAR(n)`,所以 `CASE ... THEN 'city'` 这类**计算出来的**字符串会按定长补空格(`'city    '`);`LIKE` 与 `=` 比较不受影响(按 SQL 标准忽略尾随空格),但直接拿去做等值判断/落库前建议 `TRIM`。列本身(`VARCHAR`)不受影响。 |
| **JOIN 的代价** | `JOIN` 走 Calcite 的执行计划(小右表常为哈希连接,大表可能是嵌套循环)。内表每次都需要重新枚举时,本库每次都给一个**全新游标**,所以结果正确;但大表 × 大表的 JOIN 仍然会很慢 —— 要"快"就得自己写循环用 `.spx` 索引。 |
| **查询结果内存** | `ORDER BY` / `DISTINCT` / `GROUP BY` 会按 SQL 语义物化(GDAL 文档对这几个子句同样警告内存);`SELECT`/`WHERE`/`LIMIT`/聚合则不会。 |

---

## 8. 测试

```bash
mvn test
```

83 个用例,分七组:

| 测试类 | 用例数 | 覆盖点 |
| --- | --- | --- |
| `IoUtilTest` | 10 | LEB128 往返与边界、GUID 混合字节序、UTF-16、日期往返与**整秒吸附**、**数值文本化不出现科学计数法且精度不丢**、ESRI NaN 位型、位图、`GdbCollections` 不可变/保序/可含 null |
| `GdbGeometryTest` | 12 | 点 / 两部件线 / 带洞多边形 / 三维点的原生 blob 往返、**环闭合与绕向归一**、**WKT 坐标禁用科学计数法**、包围盒前缀探测、WKB 往返与 ISO Z 偏移、空几何、字段类型码 |
| `GdbTableRoundTripTest` | 7 | 全字段类型往返、空值、更新 / 删除、多边形往返(环闭合 + 表级包围盒)、错误语义、建表校验、迭代跳过空槽 |
| `OpenFileGdbDatasetTest` | 9 | 建库结构(系统表文件齐全)、写读往返、追加、多图层、**按序号取图层**、序号越界语义、打开错误、写入错误、只读拒绝写 |
| `OgrApiTest` | 19 | **OGR 风格 API**:`GetLayerDefn` / `GetFIDColumn` / `GetGeometryColumn` / 单游标 `GetNextFeature` / 随机读 / **`SetSpatialFilterRect` 流式过滤** / `GetFeatureCount(force)` / `GetExtent(force)` / `TestCapability` 的 `OLC*` / 只读图层写守卫 / 数据集 `GetName`·`GetLayerByName`·`ReleaseResultSet`;**`executeSql` 结果图层**(层名 `SELECT`、FID 与几何的映射、复位重读、无 OBJECTID 时 FID 退化、结果层套 bbox、只读与释放后语义);`GetFieldAsXxx` / `GetWidth`·`GetPrecision`·`GetTypeName`;`intersectsRect` 的**凹多边形 / 洞 / 点 / 线**精确性 |
| `GdbSqlTest` | 22 | 投影 / WHERE 下推 / 聚合 / `GROUP BY` / `ORDER BY` + `LIMIT` / `DISTINCT`、大小写不敏感、**NULL 三值逻辑**(`IS NULL` / `= NULL`)、**TIMESTAMP 字面量比较**(UTC 约定)、中文字面量(ISO-8859-1 的坑)、保留字引号、几何列投影与 `IS NULL`、`bbox` 空间过滤、游标 `reset()`、非查询语句拒绝、语法/表/字段错误语义 |
| `SpatialReferenceTest` | 4 | 内建 WKT 反查 EPSG(含空白/大小写容忍)、ESRI 风格 WKT 还原 WKID、`AUTHORITY` 优先、构造口径 |

其中 `GdbGeometryTest` 与 `GdbTableRoundTripTest` 里的期望值不是"代码跑出来的",
而是对照 GDAL C++ 源码(`filegdbtable.cpp` 的几何解析、`FileGDBDoubleDateToOGRDate`)
与 Python 参考实现逐字节核对出来的。

外部验收(每次改动后建议各跑一遍):

```bash
# 1. 本库写 → GDAL 读
java -cp ... com.gdal.openfilegdb.demo.GdbWriteDemo
ogrinfo -al -so target/demo-out/write-demo.gdb

# 2. GDAL 写 → 本库读
ogr2ogr -f OpenFileGDB /tmp/made.gdb /tmp/features.json -nlt POLYGON
java -cp ... com.gdal.openfilegdb.demo.GdbReadDemo /tmp/made.gdb

# 3. SQL 与 GDAL ExecuteSQL 交叉验证(同一句 SQL 两边跑,结果必须一致)
ogrinfo -q -sql 'SELECT COUNT(*) FROM "村行政区划" WHERE dcmj > 1000000' data.gdb
java -cp ... com.gdal.openfilegdb.demo.GdbSqlDemo data.gdb
```

`executeSql` 的实测(21217 要素 / 249.7 MiB 的"村行政区划",`-Xmx256m`):

| 查询 | 内存 | 说明 |
| --- | --- | --- |
| `SELECT objectid, zldwdm WHERE objectid <= 5` | 平坦 | 下推生效,只扫前 5 行 |
| `SELECT COUNT(*), SUM(dcmj)` | 平坦 | 全表流式,只留累加器 |
| `SELECT objectid, zldwdm`(21217 行全取) | GC 后**无增长** | 过一条丢一条 |
| `SELECT objectid, Shape`(解码 686 MB 几何) | **不 OOM** | 单条几何随用随丢 |
| 对照 `layer.where(f -> true)` | **OOM** | 急切 API 全量驻留(≈711 MB) |
| `executeSql(sql, bbox)` | 平坦 | 与 `layer.search()` 命中数逐条一致(318 = 318) |

`executeSql` / 空间过滤与 GDAL 的**外部交叉验证**(同一库、同一条件两边跑):

| 对比项 | 本库 | GDAL | 结论 |
| --- | --- | --- | --- |
| `SELECT ... WHERE dcmj > 1000000` | 19017 | 19017 | ✅ |
| `... AND objectid <= 10000` | 8831 | 8831 | ✅ |
| `ORDER BY dcmj DESC LIMIT 3` 的 OBJECTID | 4585 / 20017 / 835 | 同 | ✅ |
| `setSpatialFilterRect`(extent 左下四分之一) | 6094 | 6094(`ogrinfo -so -spat`) | ✅ 命中 ID **逐条一致** |
| `executeSql(sql, bbox)`(叠 `dcmj > 1000000`) | 5712 | 5712(`ogr2ogr -spat`) | ✅ |

> 空间过滤这一项最初是 6095 vs 6094:差的那条(OBJECTID 17852)是凹多边形,
> 包围盒搭上了查询矩形、实体没进去。改成 `GdbGeometry.intersectsRect()` 做
> **精确几何相交**后,两边完全一致 —— 这也是 §4.1 那条"不是包围盒相交"的由来。

---

## 9. 参考

- GDAL OpenFileGDB 驱动文档:<https://gdal.org/drivers/vector/openfilegdb.html>
- GDAL 源码:<https://github.com/OSGeo/gdal/tree/master/ogr/ogrsf_frmts/openfilegdb>
- GDAL OGR SQL 方言(本库 `executeSql` 的行为参照):<https://gdal.org/user/ogr_sql_dialect.html>
- Apache Calcite(`executeSql` 的 SQL 引擎):<https://calcite.apache.org/docs/>
- 本工程 `reference/cpp/` 保存了撰写时对照的上游文件(只读,不参与构建)。
