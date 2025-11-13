package baseinfo;

import lombok.Getter;
import lombok.Setter;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.poi.ss.usermodel.*;

@Setter
@Getter
public class MapDistance {
    private ArrayList<ArrayList<Double>> mapList;

    public static List<List<Double>> calculateDistanceMatrix(List<double[]> coordinates) {
        List<List<Double>> matrix = new ArrayList<>();
        int n = coordinates.size();

        for (double[] coordinate : coordinates) {
            List<Double> row = new ArrayList<>();
            double[] point1 = coordinate;

            for (int j = 0; j < n; j++) {
                double[] point2 = coordinates.get(j);
                double distance = calculateSphericalDistance(
                        point1[1], point1[0],  // lat1, lon1
                        point2[1], point2[0]   // lat2, lon2
                );
                row.add(distance);
            }

            matrix.add(row);
        }

        return matrix;
    }

    public static double calculateSphericalDistance(double lat1, double lon1, double lat2, double lon2) {
        // 将角度转换为弧度
        double radLat1 = Math.toRadians(lat1);
        double radLon1 = Math.toRadians(lon1);
        double radLat2 = Math.toRadians(lat2);
        double radLon2 = Math.toRadians(lon2);

        // 计算纬度差和经度差
        double deltaLat = radLat2 - radLat1;
        double deltaLon = radLon2 - radLon1;

        // Haversine公式
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // 计算距离（千米）
        return Constants.EARTH_RADIUS * c;
    }

    public static List<List<Double>> initialDistanceMatrix() {
        System.out.println("开始生成距离矩阵...");
        // XLSX文件路径
        String xlsxFilePath = (Objects.equals(Constants.ALGO_MODE, "CG") ? Constants.allPointsFilePath : Constants.allPointsTestFilePath);

        // 存储所有点的经纬度（lon, lat）
        List<double[]> coordinates = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(xlsxFilePath);
             Workbook workbook = WorkbookFactory.create(fis)) {

            // 读取第一个工作表（索引从0开始）
            Sheet sheet = workbook.getSheetAt(0);

            // 跳过表头行（第0行），从第1行开始读取数据
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue; // 跳过空行

                Cell lonCell = row.getCell(1);
                Cell latCell = row.getCell(2);
                if (lonCell == null || latCell == null) continue; // 跳过经纬度为空的行

                // 解析经纬度（确保单元格格式为数字）
                double lon = lonCell.getNumericCellValue();
                double lat = latCell.getNumericCellValue();

                coordinates.add(new double[]{lon, lat});
            }

            System.out.println("成功读取 " + coordinates.size() + " 个点的数据");

            // 计算距离矩阵
            List<List<Double>> distanceMatrix = calculateDistanceMatrix(coordinates);

            System.out.println("距离矩阵大小: " + distanceMatrix.size() + "x" + distanceMatrix.getFirst().size());
            return distanceMatrix;
        } catch (IOException e) {
            System.err.println("读取XLSX文件时出错: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("处理数据时出错: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public static List<double[]> initialDepotMap() {
        List<double[]> fenceCoordinates = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(Objects.equals(Constants.ALGO_MODE, "CG") ? Constants.allPointsFilePath:Constants.allPointsTestFilePath);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            // 跳过表头行（第0行），从第1行开始读取（与MapDistance保持一致）
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                // 解析经纬度：B列（索引1）=经度，C列（索引2）=纬度（兼容数字/字符串）
                double longitude = getCellValueAsDouble(row.getCell(1));
                double latitude = getCellValueAsDouble(row.getCell(2));

                // 过滤无效经纬度（如NaN）
                if (!Double.isNaN(longitude) && !Double.isNaN(latitude)) {
                    fenceCoordinates.add(new double[]{longitude, latitude});
                }
            }
        } catch (IOException e) {
            System.err.println("读取围栏坐标失败：" + e.getMessage());
            return new ArrayList<>();
        }
        return fenceCoordinates;
    }

    private static double getCellValueAsDouble(Cell cell) {
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
}
