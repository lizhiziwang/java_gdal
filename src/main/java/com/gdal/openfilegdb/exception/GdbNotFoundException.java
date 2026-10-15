package com.gdal.openfilegdb.exception;

/**
 * 请求的图层 / 表 / 字段不存在。
 *
 * <p>对标 GDAL {@code GDALDataset::GetLayerByName()} 找不到时返回
 * {@code nullptr},以及 {@code OGRLayer::GetLayerDefn()->GetFieldIndex()}
 * 返回 {@code -1} 的情形;本工程改为显式抛异常。
 */
public class GdbNotFoundException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbNotFoundException(String message) {
        super(message);
    }
}
