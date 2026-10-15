package com.gdal.openfilegdb.exception;

/**
 * 文件不存在或无法打开。
 *
 * <p>对标 GDAL 打开失败时的 {@code CE_Failure} + {@code CPLE_OpenFailed},
 * 见 {@code ogropenfilegdbdrivercore.cpp::OpenFileGDBIdentify} /
 * {@code ogropenfilegdbdatasource.cpp::Open()}:当目标路径不是合法目录、
 * {@code g} 魔数文件缺失或版本号非法时返回失败。
 */
public class GdbFileNotFoundException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbFileNotFoundException(String message) {
        super(message);
    }

    public GdbFileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
