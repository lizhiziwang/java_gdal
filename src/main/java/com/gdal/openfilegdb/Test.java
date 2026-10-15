package com.gdal.openfilegdb;

import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.feature.*;
import com.gdal.openfilegdb.layer.GdbLayer;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.sql.GdbSqlResult;
import com.gdal.openfilegdb.srs.SpatialReference;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Test {

    private static void createGdb(){
        String path = "test.gdb";
        try(OpenFileGdbDataset ds = OpenFileGdbDataset.create(path))
        {
            List<GdbField> fields = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                GdbField field = new GdbField("test"+i, FieldType.STRING);
                fields.add(field);
            }
            SpatialReference sr = SpatialReference.fromEpsg(4490);

            OpenFileGdbLayer layer = ds.createLayer("test_layer", sr, GeometryType.MULTIPOLYGON, fields);
            layer.createFeature(new GdbFeature(1l, GdbGeometry.point(110,20)));
            ds.flush();
        }
    }
    public static void read(){
        OpenFileGdbDataset ds = OpenFileGdbDataset.open("D:\\work\\2024年国土行政区划.gdb");
//        List<String> layerNames = ds.getLayerNames();
//        for (String name : layerNames){
//            System.out.println(name);
//        }
        OpenFileGdbLayer layer = ds.getLayer(2);
        System.out.println(layer.getName());
        long featureCount = layer.getFeatureCount();
        System.out.println("要素的数量："+featureCount);

        long start = System.currentTimeMillis();
        Iterator<GdbFeature> iterator = layer.iterator();
        GdbFeature feature;
        while (iterator.hasNext()){
            feature = iterator.next();
            Object zldwdm = feature.getAttribute("ZLDWDM");
            String wkt = feature.getGeometry().toWkt();
//            System.out.println(wkt);
            System.out.println(zldwdm);
        }
        long end = System.currentTimeMillis();
        System.out.println("花费时间："+(end-start));
        SpatialReference spatialReference = layer.getSpatialReference();
        System.out.println(spatialReference.getWkt());
        // 关闭数据
        ds.close();
    }

    public static void readSQL(){
        OpenFileGdbDataset ds = OpenFileGdbDataset.open("D:\\work\\2024年国土行政区划.gdb");
        GdbLayer layer = ds.executeSql("select * from \"村行政区划\"");
        GdbFeature feature;
        while ((feature = layer.getNextFeature())!=null)
        {
            String ZLDWDM = feature.getFieldAsString("ZLDWDM");
            System.out.println(ZLDWDM);
        }

    }
    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        readSQL();
        long end = System.currentTimeMillis();
        System.out.println("花费时间："+(end-start));
    }
}
