package Utils;

import baseinfo.Constants;
import impl.Carrier;
import impl.Depot;
import impl.Fence;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static baseinfo.MapDistance.calculateSphericalDistance;

public class Initializer {
    private int fenceNum;
    private int depotNum;
    private int carrierNum;
    private ArrayList<Fence> fenceList;
    private ArrayList<Depot> depotList;
    private ArrayList<Carrier> carrierList;

    public Initializer() {
        fenceNum = 0;
        depotNum = 0;
        carrierNum = 0;
        fenceList = new ArrayList<>();
        depotList = new ArrayList<>();
        carrierList = new ArrayList<>();
    }

    public ArrayList<Fence> fenceInitializer(List<List<Double>> distanceMatrix) {
        System.out.println("开始初始化围栏...");
        fenceList = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(Constants.ALGOMODE == "CG" ? Constants.allPointsFilePath: Constants.allPointsTestFilePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0); // 获取第一个工作表
            int headerRowNum = 0; // 表头行索引（假设第0行为表头）
            int index = 0; // 顺序编号，从0开始

            // 遍历数据行（从表头下一行开始）
            for (int rowNum = headerRowNum + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                try {
                    int currentIndex = index; // 当前行的序号
                    index++;
                    double lon = getCellNumericValue(row.getCell(1));
                    double lat = getCellNumericValue(row.getCell(2));
                    double totalDemand = getCellNumericValue(row.getCell(3)); // 第5列（索引4）
                    double selfDemand = getCellNumericValue(row.getCell(4));  // 第6列（索引5）
                    double depotDemand = getCellNumericValue(row.getCell(5)); // 第7列（索引6）
                    double deliverDemand = getCellNumericValue(row.getCell(6)); // 第8列（索引7）

                    // 创建Fence实例
                    Fence fence = new Fence(
                            currentIndex,
                            lon,
                            lat,
                            totalDemand,
                            selfDemand,
                            depotDemand,
                            deliverDemand,
                            0.0
                    );

                    fence.generateDistanceMap(distanceMatrix);
                    double nearestDepotDistance = calNearestDepotDistance(fence, depotList);
                    fence.setOriginalFenceValue(nearestDepotDistance * Constants.DEPOTDISTANCETOFENCEVALUE);
                    fenceList.add(fence);

                } catch (Exception e) {
                    System.err.println("解析行 " + rowNum + " 失败：" + e.getMessage());
                }
            }

        } catch (IOException e) {
            // 捕获IO异常（如文件不存在、读取失败等），打印信息后停止程序
            System.err.println("处理Excel文件时发生错误：" + e.getMessage());
            e.printStackTrace(); // 打印详细异常栈
            System.exit(1); // 非0状态码表示异常退出
        }
        fenceNum = fenceList.size();
        System.out.println("成功生成围栏数：" + fenceList.size());
        return fenceList;
    }

    public ArrayList<Depot> depotInitializer(List<double[]> fenceCoordinates) {
        System.out.println("开始初始化仓库地图...");
        if (fenceCoordinates == null || fenceCoordinates.isEmpty()) {
            System.err.println("围栏坐标为空，无法创建Depot");
            return new ArrayList<>();
        }

        depotList = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(Constants.ALGOMODE == "CG" ? Constants.candidatePointsFilePath : Constants.candidatePointsTestFilePath);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            // 跳过表头行（第0行：Longitude,Latitude）
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                // 读取候选点经纬度：A列（索引0）=经度，B列（索引1）=纬度（兼容数字/字符串）
                double depotLon = getCellValueAsDouble(row.getCell(0));
                double depotLat = getCellValueAsDouble(row.getCell(1));

                // 过滤无效经纬度
                if (Double.isNaN(depotLon) || Double.isNaN(depotLat)) {
                    continue;
                }

                // 创建Depot并计算到所有围栏的距离
                Depot depot = new Depot(rowNum, depotLon, depotLat);
                depot.generateDistanceMap(fenceCoordinates);
                depotList.add(depot);
            }
        } catch (IOException e) {
            System.err.println("读取候选点失败：" + e.getMessage());
            return new ArrayList<>();
        }
        depotNum = depotList.size();
        System.out.println("成功生成仓库数：" + depotList.size());
        return depotList;
    }

    public ArrayList<Carrier> carrierInitializer(boolean isDifferentCarrier) {
        System.out.println("开始初始化载具...");
        ArrayList<Carrier> carrierList = new ArrayList<>();
        if (isDifferentCarrier) {
            System.out.println("当前未提供载具信息");
            return carrierList;
        } else {
            int index = 0; // 顺序编号，从0开始
            for (index = 0; index < depotNum; index++) {

                try {
                    int currentIndex = index; // 当前行的序号
                    Double capacity = Constants.MAXCAPACITY;
                    Double price = Constants.DELIVERCOSTPERMETER;
                    Double maxDistance = Constants.MAXDISTANCE;
                    Double minRatioCapacity = Constants.MINCARRIERLOAD;

                    Carrier carrier = new Carrier(
                            currentIndex,
                            capacity,
                            maxDistance,
                            currentIndex,
                            minRatioCapacity
                    );

                    carrierList.add(carrier);

                } catch (Exception e) {
                    System.err.println("创建载具 " + index + " 失败：" + e.getMessage());
                }
            }
            System.out.println("成功生成载具数：" + carrierList.size());
            return carrierList;
        }
    }

    /**
     * 安全获取单元格的字符串值
     */
    private double getCellValueAsDouble(Cell cell) {
        if (cell == null) {
            return Double.NaN; // 单元格为空返回无效值
        }

        switch (cell.getCellType()) {
            case NUMERIC:
                // 数字类型直接读取
                return cell.getNumericCellValue();
            case STRING:
                // 字符串类型：去除空格后尝试转为double
                String cellValue = cell.getStringCellValue().trim();
                try {
                    return Double.parseDouble(cellValue);
                } catch (NumberFormatException e) {
                    System.err.println("字符串单元格转换为数字失败：" + cellValue);
                    return Double.NaN;
                }
            default:
                // 其他类型（如公式、布尔值）视为无效
                System.err.println("不支持的单元格类型：" + cell.getCellType());
                return Double.NaN;
        }
    }

    /**
     * 安全获取单元格的数值（默认0.0）
     */
    private double getCellNumericValue(Cell cell) {
        if (cell == null) return 0.0;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return cell.getNumericCellValue();
            } else if (cell.getCellType() == CellType.STRING) {
                return Double.parseDouble(cell.getStringCellValue().trim());
            }
        } catch (Exception e) {
            throw new RuntimeException("单元格数值解析失败：" + e.getMessage());
        }
        return 0.0;
    }

    private double calNearestDepotDistance(Fence fence, List<Depot> depotList) {
        double minDistance = Double.MAX_VALUE;
        double fenceLon = fence.getLon(); // 假设Fence有getLon()方法
        double fenceLat = fence.getLat(); // 假设Fence有getLat()方法

        for (Depot depot : depotList) {
            double distance = calculateSphericalDistance(depot.getLatitude(), depot.getLongitude(), fenceLat, fenceLon);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }
        return minDistance;
    }
}
