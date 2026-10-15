package com.gdal.openfilegdb.exception;

/**
 * SQL 查询({@code executeSql})相关的错误。
 *
 * <p>对应 GDAL 里 {@code ExecuteSQL()} 失败的情形:GDAL 返回 {@code nullptr} 并通过
 * {@code CPLError(CE_Failure, CPLE_AppDefined, "Failed to parse query: ...")} 报错,
 * 本库把这些统一抛成本异常 —— 包括语法错误、表/字段不存在、非查询语句、方言不支持等。
 *
 * <p>底层 Calcite 抛出的 {@link org.apache.calcite.sql.validate.SqlValidatorException} /
 * {@link java.sql.SQLException} 会作为 cause 附着,便于取回具体的错误位置。
 */
public class GdbSqlException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbSqlException(String message) {
        super(message);
    }

    public GdbSqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
