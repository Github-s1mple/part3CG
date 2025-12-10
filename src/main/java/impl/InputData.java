package impl;

import Utils.Initializer;
import baseinfo.Constants;
import baseinfo.MapDistance;
import lombok.Getter;
import lombok.Setter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static Utils.CommonUtils.getCellValue;

@Setter
@Getter
public class InputData {
    private Fences fences;
    private Candidates candidates;
    private MapDistance fenceMapDistance;
    private List<List<Double>> distanceMatrix;
    private List<HashMap<Integer, Double>> candidateDistanceMatrix;
    private Initializer initializer;
    private Map<Integer, Integer> initialOj;

    // 候选点i的固定成本f_i
    public Map<Integer, Double> fixedCost;

    public InputData() throws IOException {
        distanceMatrix = MapDistance.initialDistanceMatrix();
        List<double []> candidateMap = MapDistance.initialCandidateMap();
        fences = new Fences();
        candidates = new Candidates();
        initializer = new Initializer();
        candidates.setCandidateList(initializer.candidateInitializer(candidateMap));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix));
        fences.generateFenceIndexList();
        candidates.generateCandidateIndexList();
        candidateDistanceMatrix = candidates.generateCandidateDistanceMatrix();
        initialOj = new HashMap<>();
        if(Constants.START_WITH_INITIALSOLUTION) generateInitialSolution();
    }

    public void generateInitialSolution() throws IOException {
        Map<Integer, Integer> fixedOValues = new HashMap<>();
        // 存储去重后的class顺序（只保留每个class第一个seq=1的行）
        List<Integer> classOrder = new ArrayList<>();
        // 记录已处理的class，避免重复
        Set<Integer> processedClass = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(Constants.candidatePointsFilePath);
             Workbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = workbook.getSheetAt(0); // 读取第一个工作表

            // 遍历行（跳过表头行，从第2行开始）
            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                // 读取当前行的class和seq（假设class在A列，seq在B列）
                int classId;
                int seq;
                try {
                    classId = (int) getCellValue(row.getCell(0)); // A列：class
                    seq = (int) getCellValue(row.getCell(1));     // B列：seq
                } catch (Exception e) {
                    System.err.printf("行号%d数据格式错误，跳过：%s%n", rowNum + 1, e.getMessage());
                    continue;
                }

                // 只处理seq=1且未处理过的class
                if (seq == 1 && !processedClass.contains(classId)) {
                    classOrder.add(rowNum - 1);
                    processedClass.add(classId);
                }
            }
        }

        // 按class顺序匹配候选点index（classOrder的索引 = 候选点的index）
        List<Candidate> candidateList = candidates.getCandidateList(); // 假设获取候选点列表（按index排序）
        for (int i = 0; i < classOrder.size(); i++) {
            if (i >= candidateList.size()) {
                System.err.printf("class顺序索引%d超出候选点数量（候选点共%d个），跳过%n",
                        i, candidateList.size());
                continue;
            }
            // 候选点index = i，获取其ID
            Candidate candidate = candidateList.get(classOrder.get(i));
            int candidateId = candidate.getIndex(); // 假设Candidate有getId()方法
            // 设置该候选点为选中（1）
            fixedOValues.put(candidateId, 1);
        }

        setInitialOj(fixedOValues);
    }
}