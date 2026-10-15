package com.gdal.openfilegdb.feature;

import com.gdal.openfilegdb.exception.GdbUnsupportedFieldTypeException;

/**
 * FileGDB 字段类型(对应 GDAL {@code enum FileGDBFieldType},前缀 {@code FGFT_*})。
 *
 * <p>数值来源:{@code ogr/ogrsf_frmts/openfilegdb/filegdbtable.h} 中的
 * {@code enum FileGDBFieldType}。这些数值 <b>必须</b>与 GDAL 完全一致 ——
 * 它们直接写在 {@code .gdbtable} 字段描述区的类型字节上。
 *
 * <p>与 OGR 的映射关系见 {@code filegdb_gdbtoogrfieldtype.h}
 * (GDAL 在那里把 {@code FGFT_*} 映射成 {@code OGRFieldType}/{@code OGRFieldSubType})。
 * 例如 {@code FGFT_INT16} → {@code OFTInteger}, {@code FGFT_INT64} →
 * {@code OFTInteger64}, {@code FGFT_FLOAT64} → {@code OFTReal},
 * {@code FGFT_STRING}/{@code FGFT_XML} → {@code OFTString},
 * {@code FGFT_DATETIME} → {@code OFTDateTime}。
 *
 * <p><b>版本差异</b>:{@code INT64 / DATE / TIME / DATETIME_WITH_OFFSET} 是
 * ArcGIS Pro 3.2+ 才引入的类型;ArcGIS 10.x 产出的表里不会出现。
 */
public enum FieldType {

    /** 未定义 —— GDAL 里 {@code FGFT_UNDEFINED = -1}。 */
    UNDEFINED(-1, 0, false),

    /** 16 位整数(esriFieldTypeSmallInteger / short)。 */
    INT16(0, 2, false),

    /** 32 位整数(esriFieldTypeInteger / long)。 */
    INT32(1, 4, false),

    /** 32 位浮点(esriFieldTypeSingle / float)。 */
    FLOAT32(2, 4, false),

    /** 64 位浮点(esriFieldTypeDouble)。 */
    FLOAT64(3, 8, false),

    /** 变长字符串(esriFieldTypeString / text),记录里带 varuint 长度前缀。 */
    STRING(4, 0, true),

    /** 日期时间,8 字节 double(自 1899-12-30 起的天数)。 */
    DATETIME(5, 8, false),

    /**
     * OBJECTID(行号推导,<b>不占记录空间</b>。
     *
     * <p>记录体内不存 OID 值,读取时用行号 + 1 得到 —— 对标 GDAL
     * {@code FileGDBTable::SelectRow} 之后的 {@code SetFID(iRow + 1)}。
     */
    OBJECTID(6, 0, false),

    /** 几何字段,记录里带 varuint 长度前缀的原生几何 blob。 */
    GEOMETRY(7, 0, true),

    /** 二进制大对象(BLOB),带 varuint 长度前缀。 */
    BINARY(8, 0, true),

    /** 栅格(仅托管栅格列,本工程只做识别,不解析内容)。 */
    RASTER(9, 0, true),

    /** 16 字节 GUID,ArcGIS 以 {@code {..}} 文本形式呈现。 */
    GUID(10, 16, false),

    /** 全局 ID(16 字节),语义同 GUID。 */
    GLOBALID(11, 16, false),

    /** XML 文本,编码方式与 STRING 相同。 */
    XML(12, 0, true),

    /** 64 位整数(ArcGIS Pro 3.2+)。 */
    INT64(13, 8, false),

    /** 仅日期(ArcGIS Pro 3.2+),存储格式同 DATETIME。 */
    DATE(14, 8, false),

    /** 仅时间(ArcGIS Pro 3.2+),存储格式同 DATETIME。 */
    TIME(15, 8, false),

    /** 带时区的日期时间(ArcGIS Pro 3.2+):8 字节 double + 2 字节时区偏移。 */
    DATETIME_WITH_OFFSET(16, 10, false);

    private final int code;
    private final int fixedSize;
    private final boolean variable;

    FieldType(int code, int fixedSize, boolean variable) {
        this.code = code;
        this.fixedSize = fixedSize;
        this.variable = variable;
    }

    /** 字段描述区里写的类型码({@code FGFT_*} 的值)。 */
    public int code() {
        return code;
    }

    /**
     * 记录体中占用的固定字节数;<b>变长字段返回 0</b>。
     *
     * <p>对标 {@code FileGDBField::m_nFixedSize}。注意 {@code OBJECTID} 返回 0:
     * FileGDB 不把 OID 存进记录体,而是由行号推导。
     */
    public int fixedSize() {
        return fixedSize;
    }

    /** 是否变长(记录里为 {@code varuint 长度 + 数据})。 */
    public boolean isVariable() {
        return variable;
    }

    /** 是否为数值型(定长整数/浮点)。 */
    public boolean isNumeric() {
        return this == INT16 || this == INT32 || this == INT64
                || this == FLOAT32 || this == FLOAT64;
    }

    /** 是否为日期时间族。 */
    public boolean isDateTime() {
        return this == DATETIME || this == DATE || this == TIME
                || this == DATETIME_WITH_OFFSET;
    }

    /**
     * OGR 的类型名(OGR: {@code OGRFieldDefn::GetFieldTypeName})。
     *
     * <p>取值就是 {@code ogrinfo} 里 {@code name (Type)} 那一列写的名字:
     * {@code Integer} / {@code Integer64} / {@code Real} / {@code String} /
     * {@code Date} / {@code Time} / {@code DateTime} / {@code Binary}。
     * 注意 OGR 的 {@code OFTInteger} 是 32 位,所以 INT16 与 INT32 都叫
     * {@code Integer};几何字段在 OGR 里不是普通字段,这里给 {@code "Geometry"}。
     *
     * @return OGR 类型名;未定义类型返回空串
     */
    public String ogrTypeName() {
        switch (this) {
            case INT16:
            case INT32:
                return "Integer";
            case INT64:
            case OBJECTID:
                return "Integer64";
            case FLOAT32:
            case FLOAT64:
                return "Real";
            case STRING:
            case XML:
            case GUID:
            case GLOBALID:
                return "String";
            case DATE:
                return "Date";
            case TIME:
                return "Time";
            case DATETIME:
            case DATETIME_WITH_OFFSET:
                return "DateTime";
            case BINARY:
            case RASTER:
                return "Binary";
            case GEOMETRY:
                return "Geometry";
            default:
                return "";
        }
    }

    /**
     * 由类型码反查枚举。
     *
     * @param code {@code FGFT_*} 数值
     * @return 对应枚举
     * @throws GdbUnsupportedFieldTypeException 未知类型码
     */
    public static FieldType fromCode(int code) {
        for (FieldType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new GdbUnsupportedFieldTypeException("未知的 FileGDB 字段类型码: " + code);
    }

    /**
     * 由类型码反查枚举,未知时返回 {@link #UNDEFINED} 而不是抛异常。
     *
     * @param code {@code FGFT_*} 数值
     * @return 对应枚举或 {@code UNDEFINED}
     */
    public static FieldType fromCodeOrUndefined(int code) {
        for (FieldType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return UNDEFINED;
    }
}
