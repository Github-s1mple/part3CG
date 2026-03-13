package Utils;

import impl.Fence;
import impl.Orders;
import impl.Order;
import impl.Route; // 新增：假设路径信息存储在Route类中
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 订单详情导出工具类 - 最终适配版：
 * 从Route.getFenceIndexList()获取围栏编号顺序 + 匹配fences经纬度
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
        sheet.setColumnWidth(1, 80 * 256);  // 访问围栏顺序（ID+经纬度）- 重点加宽
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

            // 4.2 访问围栏顺序（ID+经纬度）- 核心：从Route获取围栏编号顺序 + 匹配经纬度
            Cell cell1 = dataRow.createCell(1);
            List<Integer> fenceIndexList = getFenceIndexListFromOrder(order); // 统一获取围栏编号顺序
            String fenceOrderWithLatLon = getFenceOrderWithLatLonString(fenceIndexList, orders.getFences());
            cell1.setCellValue(fenceOrderWithLatLon);

            // 4.3 围栏总数
            Cell cell2 = dataRow.createCell(2);
            cell2.setCellValue(fenceIndexList == null ? 0 : fenceIndexList.size());

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
            String fenceLatLon = getFenceLatLonString(fenceIndexList, orders.getFences());
            cell7.setCellValue(fenceLatLon);
        }

        // 5. 写入文件并关闭资源
        try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
            workbook.write(outputStream);
            System.out.println("✅ 订单详情已成功导出到：" + filePath);
        } finally {
            workbook.close();  // 确保工作簿关闭，避免内存泄漏
        }
    }

    // ====================== 核心方法1：统一获取订单的围栏编号顺序 ======================
    /**
     * 从Order中提取围栏访问编号顺序（适配Route.getFenceIndexList()）
     */
    private static List<Integer> getFenceIndexListFromOrder(Order order) {
        if (order == null) {
            return null;
        }

        List<Integer> fenceIndexList = order.getFenceList(); // 核心：获取围栏编号顺序
        if (fenceIndexList == null || fenceIndexList.isEmpty()) {
            System.err.println("订单" + order.getOrderId() + "的路径无围栏编号！");
            return null;
        }
        return fenceIndexList;
    }

    // ====================== 核心方法2：围栏顺序+经纬度格式化 ======================
    /**
     * 格式化围栏访问顺序为「ID(经度,纬度)」格式（如 "1(121.194746,31.280915),3(121.196851,31.280915)"）
     */
    private static String getFenceOrderWithLatLonString(List<Integer> fenceIndexList, impl.Fences fences) {
        if (fenceIndexList == null || fenceIndexList.isEmpty() || fences == null) {
            return "无（围栏顺序为空）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fenceIndexList.size(); i++) {
            Integer fenceId = fenceIndexList.get(i);
            if (fenceId == null) {
                sb.append("null(无ID)");
            } else {
                // 从fences中匹配围栏对象，获取经纬度
                Fence fence = fences.getFence(fenceId);
                if (fence == null) {
                    sb.append(fenceId).append("(围栏不存在)");
                } else if (fence.getLon() == null || fence.getLat() == null) {
                    sb.append(fenceId).append("(无经纬度)");
                } else {
                    // 核心格式：ID(经度,纬度)，经纬度保留6位小数
                    sb.append(fenceId)
                            .append("(")
                            .append(String.format("%.6f", fence.getLon()))
                            .append(",")
                            .append(String.format("%.6f", fence.getLat()))
                            .append(")");
                }
            }
            // 非最后一个元素添加分隔符
            if (i < fenceIndexList.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    // ====================== 辅助方法：围栏需求格式化 ======================
    /**
     * 格式化围栏需求为字符串（如 "1:50.00,3:20.50"）
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

    // ====================== 辅助方法：围栏经纬度格式化 ======================
    /**
     * 格式化围栏经纬度为字符串（如 "1:121.194746,31.280915,3:121.196851,31.280915"）
     */
    private static String getFenceLatLonString(List<Integer> fenceIndexList, impl.Fences fences) {
        if (fenceIndexList == null || fenceIndexList.isEmpty() || fences == null) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Integer fenceId : fenceIndexList) {
            if (fenceId == null) {
                continue;
            }
            Fence fence = fences.getFence(fenceId);
            if (fence == null) {
                sb.append(fenceId).append(":围栏不存在");
            } else if (fence.getLon() == null || fence.getLat() == null) {
                sb.append(fenceId).append(":无经纬度");
            } else {
                sb.append(fenceId)
                        .append(":")
                        .append(String.format("%.6f", fence.getLon()))
                        .append(",")
                        .append(String.format("%.6f", fence.getLat()));
            }
            if (count < fenceIndexList.size() - 1) {
                sb.append(",");
            }
            count++;
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }
}