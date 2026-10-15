package com.gdal.openfilegdb.feature;

import java.util.Objects;

/**
 * 一个字段的定义。
 *
 * <p>对标 GDAL {@code FileGDBField}(定义在
 * {@code ogr/ogrsf_frmts/openfilegdb/filegdbtable.h})与 OGR 的
 * {@code OGRFieldDefn}。字段描述区里每条字段记录由
 * {@code [uint8 名字码元数][UTF-16LE 名字][uint8 别名额数][UTF-16LE 别名]
 * [uint8 类型][类型相关载荷]} 构成,见 {@code filegdbtable.cpp::Open} 中
 * 的字段循环与 {@code filegdbtable_write_fields.cpp::CreateField}。
 *
 * <p>本类是可变的定义对象,setter 采用链式风格,便于构造:
 * <pre>{@code
 * GdbField f = new GdbField("NAME", FieldType.STRING).setLength(255).setNullable(true);
 * }</pre>
 */
public class GdbField {

    /** 字段名(在表中唯一)。 */
    private String name;

    /** 字段类型。 */
    private FieldType type;

    /**
     * 字符串字段的最大字符数(对应 {@code m_nMaxWidth})。
     *
     * <p>仅对 {@link FieldType#STRING} 有意义,其余类型为 0。
     */
    private int length;

    /** flags 的 {@code MASK_NULLABLE} 位:为 true 时该字段在空值位图里占 1 bit。 */
    private boolean nullable = true;

    /** flags 的 {@code MASK_REQUIRED} 位。 */
    private boolean required;

    /** flags 的 {@code MASK_EDITABLE} 位:置位时字段描述区里才跟默认值。 */
    private boolean editable;

    /** 别名(显示名),可为空串。 */
    private String alias = "";

    /** 默认值;{@code null} 表示无。 */
    private Object defaultValue;

    /**
     * 构造一个字段定义。
     *
     * @param name 字段名
     * @param type 字段类型
     */
    public GdbField(String name, FieldType type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    // ------------------------------------------------------------------
    // 只读衍生属性
    // ------------------------------------------------------------------

    /** 字段名。 */
    public String getName() {
        return name;
    }

    /**
     * 设置字段名。
     *
     * @param name 新名字
     * @return this
     */
    public GdbField setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    /** 字段类型。 */
    public FieldType getType() {
        return type;
    }

    /**
     * 设置字段类型。
     *
     * @param type 新类型
     * @return this
     */
    public GdbField setType(FieldType type) {
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    /** 字符串字段的最大字符数(非字符串类型恒为 0)。 */
    public int getLength() {
        return length;
    }

    /** {@link #getLength()} 的 OGR 风格别名(OGR: {@code OGRFieldDefn::GetWidth()})。 */
    public int getWidth() {
        return length;
    }

    /**
     * 字段精度(OGR: {@code OGRFieldDefn::GetPrecision()})。
     *
     * <p>{@code .gdbtable} 的字段描述区里没有精度这个概念(FileGDB 只有类型 +
     * 字符串宽度),所以恒为 0 —— 留着是为了让照着 OGR 写的调用方能编译通过。
     */
    public int getPrecision() {
        return 0;
    }

    /** OGR 的类型名(OGR: {@code OGRFieldDefn::GetFieldTypeName()}),见
     *  {@link FieldType#ogrTypeName()}。 */
    public String getTypeName() {
        return type.ogrTypeName();
    }

    /**
     * 设置字符串字段的最大字符数。
     *
     * @param length 字符数
     * @return this
     */
    public GdbField setLength(int length) {
        this.length = length;
        return this;
    }

    /** 是否可为空。 */
    public boolean isNullable() {
        return nullable;
    }

    /**
     * 设置是否可为空。
     *
     * @param nullable 可否为空
     * @return this
     */
    public GdbField setNullable(boolean nullable) {
        this.nullable = nullable;
        return this;
    }

    /** 是否必填。 */
    public boolean isRequired() {
        return required;
    }

    /**
     * 设置是否必填。
     *
     * @param required 是否必填
     * @return this
     */
    public GdbField setRequired(boolean required) {
        this.required = required;
        return this;
    }

    /** 是否可编辑。 */
    public boolean isEditable() {
        return editable;
    }

    /**
     * 设置是否可编辑。
     *
     * @param editable 是否可编辑
     * @return this
     */
    public GdbField setEditable(boolean editable) {
        this.editable = editable;
        return this;
    }

    /** 别名。 */
    public String getAlias() {
        return alias;
    }

    /**
     * 设置别名。
     *
     * @param alias 别名,null 视为空串
     * @return this
     */
    public GdbField setAlias(String alias) {
        this.alias = alias == null ? "" : alias;
        return this;
    }

    /** 默认值。 */
    public Object getDefaultValue() {
        return defaultValue;
    }

    /**
     * 设置默认值。
     *
     * @param defaultValue 默认值
     * @return this
     */
    public GdbField setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    // ------------------------------------------------------------------
    // 便捷判定
    // ------------------------------------------------------------------

    /** 记录体内占用的固定字节数;变长字段为 0。 */
    public int fixedSize() {
        return type.fixedSize();
    }

    /** 是否变长字段。 */
    public boolean isVariable() {
        return type.isVariable();
    }

    /** 是否几何字段。 */
    public boolean isGeometry() {
        return type == FieldType.GEOMETRY;
    }

    /** 是否 OBJECTID 字段。 */
    public boolean isObjectId() {
        return type == FieldType.OBJECTID;
    }

    /** 对外显示名:别名优先。 */
    public String displayName() {
        return alias.isEmpty() ? name : alias;
    }

    /** 深拷贝。 */
    public GdbField copy() {
        GdbField f = new GdbField(name, type);
        f.length = length;
        f.nullable = nullable;
        f.required = required;
        f.editable = editable;
        f.alias = alias;
        f.defaultValue = defaultValue;
        return f;
    }

    @Override
    public String toString() {
        return "GdbField[" + name + " " + type
                + (type == FieldType.STRING ? "(" + length + ")" : "")
                + (nullable ? ",nullable" : "")
                + (required ? ",required" : "") + "]";
    }
}
