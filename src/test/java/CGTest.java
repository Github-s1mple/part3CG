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

public class CGTest {
    public static void main(String[] args) throws IOException {
        Constants.ALGO_MODE = "CG";
        List<Scenario> scenarioList = scenarioInitializer();
        Scenarios scenarios = new Scenarios(scenarioList);
        LocationResult firstStageResult = ResultPersistenceUtil.loadFirstStageResult("firstStageResult_test.json");
        Instance instance = new Instance(firstStageResult, scenarios.getScenarioList().get(0));
        long gaIterStartTime = System.currentTimeMillis();
        CGSolve cgSolve = new CGSolve(instance);
        List<Order> optimalOrders = cgSolve.solve();
        long gaIterEndTime = System.currentTimeMillis();
        double gaIterCostTime = (gaIterEndTime - gaIterStartTime) / 1000.0;
        Orders orders = new Orders(optimalOrders);
        orders.setTime(gaIterCostTime);
        System.out.println("时间：" + orders.getTime());
        //ResultProcess resultProcess = new ResultProcess(orders);
        //resultProcess.showOrderDetail();
    }
}
