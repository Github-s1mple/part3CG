import baseinfo.MapDistance;
import org.apache.poi.ss.usermodel.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DistanceCalculator {

    public static void main(String[] args) {
        // XLSX文件路径（替换为你的文件路径）
        String xlsxFilePath = "all_points.xlsx";

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
            List<List<Double>> distanceMatrix = MapDistance.calculateDistanceMatrix(coordinates);

            System.out.println("距离矩阵大小: " + distanceMatrix.size() + "x" + distanceMatrix.getFirst().size());

        } catch (IOException e) {
            System.err.println("读取XLSX文件时出错: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("处理数据时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
