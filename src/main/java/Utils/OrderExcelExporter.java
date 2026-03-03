package Utils;

import impl.Fence;
import impl.Orders;
import impl.Order;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 订单详情导出工具类 - 增强版：围栏顺序包含ID+经纬度
 */
public class OrderExcelExporter {

    /**
     * 将Orders中的所有订单详情导出到XLSX文件
     * @param orders 待导出的订单集合
     * @param filePath 导出文件路径（如 "D:/order_detail.xlsx"）
     * @throws IOException IO异常（文件路径无效、权限不足等）
     */
    public static void exportOrdersToXlsx(Orders orders, String filePath) throws IOException {
        // 1. 入参校验
        if (orders == null || orders.getOrderList() == null || orders.getOrderList().isEmpty()) {
            System.err.println("订单数据为空，无法导出！");
            return;
        }
        if (filePath == null || filePath.isEmpty() || !filePath.endsWith(".xlsx")) {
            throw new IllegalArgumentException("文件路径无效，必须是.xlsx格式！");
        }

        // 2. 创建Excel工作簿和工作表
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("订单详情");
        // 调整列宽：适配经纬度内容（加宽访问围栏顺序列）
        sheet.setColumnWidth(0, 15 * 256);  // 订单ID
        sheet.setColumnWidth(1, 60 * 256);  // 访问围栏顺序（ID+经纬度）- 重点加宽
        sheet.setColumnWidth(2, 15 * 256);  // 围栏总数
        sheet.setColumnWidth(3, 15 * 256);  // 订单距离
        sheet.setColumnWidth(4, 15 * 256);  // 配送成本
        sheet.setColumnWidth(5, 15 * 256);  // 调度量
        sheet.setColumnWidth(6, 30 * 256);  // 围栏需求（ID:需求）
        sheet.setColumnWidth(7, 40 * 256);  // 围栏经纬度（ID:经度,纬度）

        // 3. 创建表头行
        Row headerRow = sheet.createRow(0);
        String[] headers = {
                "订单ID", "访问围栏顺序（ID+经纬度）", "围栏总数", "订单距离",
                "配送成本", "调度量", "各围栏需求", "各围栏经纬度"
        };
        // 设置表头样式（加粗）
        CellStyle headerStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        headerStyle.setFont(font);
        // 写入表头
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // 4. 遍历订单，写入数据行
        int rowNum = 1;  // 从第二行开始写入数据（第一行是表头）
        List<Order> orderList = orders.getOrderList();
        for (Order order : orderList) {
            if (order == null) {
                continue;
            }
            Row dataRow = sheet.createRow(rowNum++);

            // 4.1 订单ID
            Cell cell0 = dataRow.createCell(0);
            cell0.setCellValue(order.getOrderId() == null ? "无" : order.getOrderId().toString());

            // 4.2 访问围栏顺序（ID+经纬度）- 核心修改
            Cell cell1 = dataRow.createCell(1);
            // 传入orders.getFences()以获取围栏经纬度
            String fenceOrderWithLatLon = getFenceOrderWithLatLonString(order.getFenceList(), orders.getFences());
            cell1.setCellValue(fenceOrderWithLatLon);

            // 4.3 围栏总数
            Cell cell2 = dataRow.createCell(2);
            cell2.setCellValue(order.getFenceNumber());

            // 4.4 订单距离
            Cell cell3 = dataRow.createCell(3);
            cell3.setCellValue(String.format("%.2f", order.getDistance()));

            // 4.5 配送成本（carrierCost）
            Cell cell4 = dataRow.createCell(4);
            cell4.setCellValue(String.format("%.2f", order.getCarrierCost()));

            // 4.6 调度量（dispatchNum）
            Cell cell5 = dataRow.createCell(5);
            cell5.setCellValue(String.format("%.2f", order.getDispatchNum()));

            // 4.7 各围栏需求（loads：key=fenceId, value=需求）
            Cell cell6 = dataRow.createCell(6);
            String fenceLoads = getFenceLoadsString(order.getLoads(), orders.getFences());
            cell6.setCellValue(fenceLoads);

            // 4.8 各围栏经纬度（拼接fenceId:经度,纬度）
            Cell cell7 = dataRow.createCell(7);
            String fenceLatLon = getFenceLatLonString(order.getFenceList(), orders.getFences());
            cell7.setCellValue(fenceLatLon);
        }

        // 5. 写入文件并关闭资源
        try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
            workbook.write(outputStream);
            System.out.println("订单详情已成功导出到：" + filePath);
        } finally {
            workbook.close();  // 确保工作簿关闭，避免内存泄漏
        }
    }

    // ====================== 核心修改：围栏顺序包含ID+经纬度 ======================
    /**
     * 格式化围栏访问顺序为「ID(经度,纬度)」格式（如 "1(116.40,39.90),3(116.45,39.95)"）
     */
    private static String getFenceOrderWithLatLonString(List<Integer> fenceList, impl.Fences fences) {
        if (fenceList == null || fenceList.isEmpty() || fences == null) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fenceList.size(); i++) {
            Integer fenceId = fenceList.get(i);
            if (fenceId == null) {
                continue;
            }
            // 获取围栏对象，提取经纬度
            Fence fence = fences.getFence(fenceId);
            if (fence == null || fence.getLon() == null || fence.getLat() == null) {
                sb.append(fenceId).append("(无有效经纬度)");
            } else {
                // 核心格式：ID(经度,纬度)，经纬度保留6位小数
                sb.append(fenceId)
                        .append("(")
                        .append(String.format("%.6f", fence.getLon()))
                        .append(",")
                        .append(String.format("%.6f", fence.getLat()))
                        .append(")");
            }
            // 非最后一个元素，添加分隔符
            if (i < fenceList.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    // ====================== 原有工具方法（保持不变） ======================
    /**
     * 格式化围栏需求为字符串（如 "1:50.0,3:20.5"）
     */
    private static String getFenceLoadsString(Map<Integer, Double> loads, impl.Fences fences) {
        if (loads == null || loads.isEmpty() || fences == null) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<Integer, Double> entry : loads.entrySet()) {
            Integer fenceId = entry.getKey();
            Double load = entry.getValue();
            if (fenceId == null || load == null) {
                continue;
            }
            sb.append(fenceId).append(":").append(String.format("%.2f", load));
            if (count < loads.size() - 1) {
                sb.append(",");
            }
            count++;
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /**
     * 格式化围栏经纬度为字符串（如 "1:116.40,39.90,3:116.45,39.95"）
     */
    private static String getFenceLatLonString(List<Integer> fenceList, impl.Fences fences) {
        if (fenceList == null || fenceList.isEmpty() || fences == null) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Integer fenceId : fenceList) {
            if (fenceId == null) {
                continue;
            }
            Fence fence = fences.getFence(fenceId);
            if (fence == null || fence.getLon() == null || fence.getLat() == null) {
                sb.append(fenceId).append(":无有效经纬度");
            } else {
                sb.append(fenceId)
                        .append(":")
                        .append(String.format("%.6f", fence.getLon()))
                        .append(",")
                        .append(String.format("%.6f", fence.getLat()));
            }
            if (count < fenceList.size() - 1) {
                sb.append(",");
            }
            count++;
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }
}