package Utils;

import impl.Scenario;
import baseinfo.Constants;
import impl.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static Utils.SelfPickupProbabilityCalculator.calculateSelfPickupProbability;
import static baseinfo.MapDistance.calculateSphericalDistance;

public class Initializer {
    private int fenceNum;
    private int depotNum;
    private int candidateNum;
    private int carrierNum;
    private ArrayList<Fence> fenceList;
    private ArrayList<Depot> depotList;
    private ArrayList<Candidate> candidateList;
    private ArrayList<Carrier> carrierList;
    private ArrayList<Scenario> scenarioList;
    private Boolean outputFlag = true;

    public Initializer() {
        fenceNum = 0;
        depotNum = 0;
        carrierNum = 0;
        fenceList = new ArrayList<>();
        depotList = new ArrayList<>();
        carrierList = new ArrayList<>();
    }

    public ArrayList<Fence> fenceInitializer(List<List<Double>> distanceMatrix, LocationResult result, Scenario scenario) {
        if (outputFlag){
            System.out.println("开始初始化围栏...");
        }
        fenceList = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(scenario != null ? scenario.getAllPointsPath() : Constants.allPointsFilePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0); // 获取第一个工作表
            int headerRowNum = 0; // 表头行索引（假设第0行为表头）
            int index = 1; // 顺序编号，从1开始

            // 遍历数据行（从表头下一行开始）
            for (int rowNum = headerRowNum + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);

                try {
                    int currentIndex = index;
                    index++;

                    double lon = getCellNumericValue(row.getCell(1));
                    double lat = getCellNumericValue(row.getCell(2));
                    double totalDemand = getCellNumericValue(row.getCell(3));
                    double fClass = getCellNumericValue(row.getCell(7));

                    // 创建Fence实例
                    Fence fence = new Fence(
                            currentIndex,
                            lon,
                            lat,
                            totalDemand,
                            0.0,
                            false,
                            fClass
                    );

                    fence.generateDistanceMap(distanceMatrix, fClass);
                    if (result != null){
                        Integer targetDepot = result.getExtraAllocation().get(fence.getIndex());
                        for (Depot depot : depotList) {
                            if (depot.getIndex().equals(targetDepot)){
                                fence.setXs(depot);
                                double distance = depot.getDistance(fence);
                                if(distance < 0.01) fence.setOriginalFenceValue(0.0001);
                                else fence.setOriginalFenceValue(distance * Constants.BIKE_COST_PER_KILOMETER_PER_ORDER);// 即每单骑手直接配送的成本
                                double selfPickDemand = calculateSelfPickupProbability(distance) * totalDemand;
                                fence.setSelfDemand(selfPickDemand);
                                fence.setDeliverDemand(totalDemand - selfPickDemand);
                                fence.setBikeDemand(totalDemand - selfPickDemand);
                                break;
                            }
                        }
                    }
                    fenceList.add(fence);

                } catch (Exception e) {
                    System.err.println("解析行 " + rowNum + " 失败：" + e.getMessage());
                }
            }

        } catch (IOException e) {
            // 捕获IO异常（如文件不存在、读取失败等），打印信息后停止程序
            System.err.println("处理Excel文件时发生错误：" + e.getMessage());
        }
        fenceNum = fenceList.size();

        if (outputFlag){
            System.out.println("成功生成围栏数：" + fenceNum);
        }

        return fenceList;
    }

    public ArrayList<Depot> depotInitializer(List<double[]> fenceCoordinates, LocationResult result) {
        if (outputFlag) {
            System.out.println("开始初始化仓库地图...");
        }
        // 校验围栏坐标合法性
        if (fenceCoordinates == null || fenceCoordinates.isEmpty()) {
            System.err.println("围栏坐标为空，无法创建Depot");
            return new ArrayList<>();
        }

        depotList = new ArrayList<>();
        // 确定文件路径：若有result则用正式路径，否则根据算法模式选择路径
        String filePath = (result != null)
                ? Constants.candidatePointsFilePath
                : Constants.candidatePointsTestFilePath;

        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            // 跳过表头行（第0行）
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                // 若有筛选结果，只处理选中的行（通过rowNum映射的id）
                if (result != null && !result.getSelectedCandidates().contains(-rowNum)) {
                    continue;
                }

                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                // 读取经纬度（C列和D列，索引2和3）
                double dClass = getCellValueAsDouble(row.getCell(0));
                double depotLon = getCellValueAsDouble(row.getCell(2));
                double depotLat = getCellValueAsDouble(row.getCell(3));

                // 过滤无效坐标
                if (Double.isNaN(depotLon) || Double.isNaN(depotLat)) {
                    continue;
                }

                // 创建Depot并计算距离映射
                Depot depot = new Depot(-rowNum, depotLon, depotLat, dClass);
                depot.generateDistanceMap(fenceCoordinates, dClass);
                depotList.add(depot);
            }
        } catch (IOException e) {
            System.err.println("读取仓库失败：" + e.getMessage());
            return new ArrayList<>();
        }

        depotNum = depotList.size();
        if (outputFlag) {
            System.out.println("成功生成仓库数：" + depotList.size());
        }
        return depotList;
    }


    public ArrayList<Carrier> carrierInitializer() {
        if (outputFlag) {
            System.out.println("开始初始化载具...");
        }
        ArrayList<Carrier> carrierList = new ArrayList<>();
        for (int index = 0; index < depotNum; index++) {

            try {
                Depot currentDepot = this.depotList.get(index);
                Carrier carrier = new Carrier(
                        index + 1,
                        Constants.MAX_CAPACITY,
                        Constants.TRUCK_MAX_DISTANCE,
                        currentDepot.getIndex()
                );

                carrierList.add(carrier);
            } catch (Exception e) {
                System.err.println("创建载具 " + index + " 失败：" + e.getMessage());
            }
        }
        if (outputFlag) {
            System.out.println("成功生成载具数：" + carrierList.size());
        }
        return carrierList;
    }

    public ArrayList<Candidate> candidateInitializer(List<double[]> fenceCoordinates) {
        if (outputFlag) {
            System.out.println("开始初始化候选点地图...");
        }

        if (fenceCoordinates == null || fenceCoordinates.isEmpty()) {
            System.err.println("围栏坐标为空，无法创建candidate");
            return new ArrayList<>();
        }

        candidateList = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(Constants.candidatePointsFilePath);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            // 跳过表头行（第0行：Longitude,Latitude）
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;
                double dClass = getCellValueAsDouble(row.getCell(0));
                Double candidateLon = convertNaNToNull(getCellValueAsDouble(row.getCell(2)));
                Double candidateLat = convertNaNToNull(getCellValueAsDouble(row.getCell(3)));
                Double candidateCost = convertNaNToNull(getCellValueAsDouble(row.getCell(4)));

                // 创建Candidate并计算到所有围栏的距离
                Candidate candidate = new Candidate(-rowNum, candidateLon, candidateLat, candidateCost, dClass);
                candidate.generateDistanceMap(fenceCoordinates);
                candidateList.add(candidate);
            }
        } catch (IOException e) {
            System.err.println("读取候选点失败：" + e.getMessage());
            return new ArrayList<>();
        }
        candidateNum = candidateList.size();
        if (outputFlag) {
            System.out.println("成功生成候选点数：" + candidateList.size());
        }
        return candidateList;
    }


    public static List<Scenario> scenarioInitializer() {
        // 1. 配置模拟文件所在目录
        String simDirPath = Constants.scenariosFileFolderPath;
        File simDir = new File(simDirPath);

        // 2. 校验目录是否存在
        if (!simDir.exists() || !simDir.isDirectory()) {
            System.err.println("目录不存在或不是有效目录：" + simDirPath);
            return null;
        }

        // 3. 遍历目录下的Excel文件，生成Scenario列表
        List<Scenario> scenarioList = new ArrayList<>();
        int idCounter = 1; // 自增ID

        File[] files = simDir.listFiles((dir, name) -> name.endsWith(".xlsx") && !name.contains("汇总"));
        if (files == null || files.length == 0) {
            System.err.println("目录下无模拟数据文件！");
            return null;
        }

        for (File file : files) {
            String fileName = file.getName();
            String filePath = file.getAbsolutePath();

            // 4. 提取文件名中的概率
            Double probability = extractProbabilityFromFileName(fileName);
            if (probability == null) {
                System.err.println("无法提取概率，跳过文件：" + fileName);
                continue;
            }

            // 5. 创建Scenario对象并添加到列表
            Scenario scenario = new Scenario(idCounter++, probability, filePath);
            scenarioList.add(scenario);

            if (idCounter > Constants.SCENARIO_NUM) {
                break;
            }
        }

        // 最终结果：scenarioList包含所有文件对应的Scenario对象
        System.out.println("\n共创建" + scenarioList.size() + "个Scenario对象");
        return scenarioList;
    }

    private static final Pattern PROBABILITY_PATTERN = Pattern.compile("概率([0-9.]+)");

    private static Double extractProbabilityFromFileName(String fileName) {
        Matcher matcher = PROBABILITY_PATTERN.matcher(fileName);
        if (matcher.find()) {
            String probStr = matcher.group(1);
            // 清理末尾多余的小数点（如“0.3360.”→“0.3360”）
            probStr = probStr.replaceAll("\\.$", "");
            try {
                double prob = Double.parseDouble(probStr);
                // 额外校验：概率值应在0~1之间（业务合理性）
                if (prob >= 0 && prob <= 1) {
                    return prob;
                } else {
                    System.err.println("概率值超出0~1范围：" + probStr);
                }
            } catch (NumberFormatException e) {
                System.err.println("概率格式错误（无法转数字）：" + probStr);
            }
        }
        return null;
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
        double fenceLon = fence.getLon();
        double fenceLat = fence.getLat();

        for (Depot depot : depotList) {
            double distance = calculateSphericalDistance(depot.getLatitude(), depot.getLongitude(), fenceLat, fenceLon);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }
        return minDistance;
    }

    private static Double convertNaNToNull(Double value) {
        return (value != null && Double.isNaN(value)) ? null : value;
    }
}
