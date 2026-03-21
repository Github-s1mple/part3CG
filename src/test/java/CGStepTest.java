import Utils.ResultPersistenceUtil;
import algoCG.CGSolve;
import baseinfo.Constants;
import impl.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class CGStepTest {
    // 定义需要测试的多组 EXPAND_STEP 参数（可根据需求扩展）
    private static final List<List<Double>> TEST_PARAMS = new ArrayList<>();

    static {
        // 初始化测试参数组（示例：5组不同的步长组合）
        // 格式：每组6个值，对应6个阶段的步长
        TEST_PARAMS.add(Arrays.asList(0.025, 0.025, 0.025,0.025,0.025,0.025)); // 原始参数
        TEST_PARAMS.add(Arrays.asList(0.05, 0.05, 0.05, 0.05, 0.05, 0.05)); // 步长偏小
        TEST_PARAMS.add(Arrays.asList(0.08, 0.08, 0.08, 0.08, 0.08, 0.08)); // 步长偏大
        TEST_PARAMS.add(Arrays.asList(0.1, 0.1, 0.1, 0.1, 0.1, 0.1)); // 统一步长
    }

    public static void main(String[] args) throws IOException {
        // 1. 加载基础数据（只加载一次，避免重复IO）
        LocationResult firstStageResult = ResultPersistenceUtil.loadFirstStageResult("firstStageResult.json");


        // 2. 创建结果汇总文件（记录所有测试组的结果）
        Workbook resultWorkbook = new XSSFWorkbook();
        Sheet resultSheet = resultWorkbook.createSheet("参数灵敏度测试结果");
        // 写入表头
        Row headerRow = resultSheet.createRow(0);
        headerRow.createCell(0).setCellValue("测试组编号");
        headerRow.createCell(1).setCellValue("EXPAND_STEP参数");
        headerRow.createCell(2).setCellValue("第一阶段步长(实际距离)");
        headerRow.createCell(3).setCellValue("总耗时(秒)");
        headerRow.createCell(4).setCellValue("总收益");

        // 3. 循环测试每组参数
        for (int groupIdx = 0; groupIdx < TEST_PARAMS.size(); groupIdx++) {
            List<Double> currentStep = TEST_PARAMS.get(groupIdx);
            System.out.println("\n==================== 开始测试第 " + (groupIdx + 1) + " 组参数 ====================");
            System.out.println("当前EXPAND_STEP：" + currentStep);

            // 设置当前测试组的步长参数
            Constants.EXPAND_STEP = currentStep;
            Instance instance = new Instance(firstStageResult);
            // 执行CG求解
            long iterStartTime = System.currentTimeMillis();
            CGSolve cgSolve = new CGSolve(instance);
            List<Order> optimalOrders = cgSolve.solve();
            long iterEndTime = System.currentTimeMillis();

            // 计算耗时
            double costTime = (iterEndTime - iterStartTime) / 1000.0;

            // 处理结果
            Orders orders = new Orders(optimalOrders);
            orders.setFences(instance.getFences());

            // 计算第一阶段实际步长（距离）
            double firstStageStep = currentStep.get(0) * Constants.TRUCK_MAX_DISTANCE;

            // 输出当前组结果
            System.out.println("第 " + (groupIdx + 1) + " 组测试完成：");
            System.out.println("步长：" + firstStageStep);
            System.out.println("时间：" + costTime + " 秒");
            System.out.println("收益：" + orders.getTotalDeliverPrice());

            // 写入结果到汇总表
            Row resultRow = resultSheet.createRow(groupIdx + 1);
            resultRow.createCell(0).setCellValue(groupIdx + 1);
            resultRow.createCell(1).setCellValue(currentStep.toString());
            resultRow.createCell(2).setCellValue(firstStageStep);
            resultRow.createCell(3).setCellValue(costTime);
            resultRow.createCell(4).setCellValue(orders.getTotalDeliverPrice());
        }

        // 4. 保存结果汇总文件
        try (FileOutputStream fos = new FileOutputStream("参数灵敏度测试汇总.xlsx")) {
            resultWorkbook.write(fos);
            System.out.println("\n✅ 所有测试完成！结果汇总文件已保存：参数灵敏度测试汇总.xlsx");
        } catch (IOException e) {
            System.err.println("保存结果文件失败：" + e.getMessage());
        } finally {
            resultWorkbook.close();
        }
    }
}