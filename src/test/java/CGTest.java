import Utils.FenceVisualizer;
import Utils.ResultPersistenceUtil;
import algoCG.ResultProcess;
import algoCG.CGSolve;
import baseinfo.Constants;
import impl.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static Utils.CommonUtils.getCellValue;
import static Utils.Initializer.scenarioInitializer;
import static Utils.OrderExcelExporter.exportOrdersToXlsx;

public class CGTest {
    public static void main(String[] args) throws IOException {
        //Constants.EXPAND_STEP = Arrays.asList(0.1, 0.1, 0.1, 0.1, 0.1, 0.1);
        LocationResult firstStageResult = ResultPersistenceUtil.loadFirstStageResult("firstStageResult.json");
        Instance instance = new Instance(firstStageResult);
        long gaIterStartTime = System.currentTimeMillis();
        CGSolve cgSolve = new CGSolve(instance);
        List<Order> optimalOrders = cgSolve.solve();
        long gaIterEndTime = System.currentTimeMillis();
        double gaIterCostTime = (gaIterEndTime - gaIterStartTime) / 1000.0;

        Orders orders = new Orders(optimalOrders);
        orders.setFences(instance.getFences());
        exportOrdersToXlsx(orders, "order_detail.xlsx");
        //作图
        orders.generateFenceList();
        ArrayList<Fence> baseFenceList = instance.getFences().getFenceList();
        List<Fence> highlightFenceList = orders.getVisitedFences();
        FenceVisualizer.plotFenceWithHighlight(baseFenceList, highlightFenceList);
        orders.setTime(gaIterCostTime);
        System.out.println("步长：" +Constants.EXPAND_STEP.get(0) * Constants.TRUCK_MAX_DISTANCE);
        System.out.println("时间：" + orders.getTime());
        System.out.println("收益：" + orders.getTotalDeliverPrice());
        //ResultProcess resultProcess = new ResultProcess(orders);
        //resultProcess.showOrderDetail();
    }
}
