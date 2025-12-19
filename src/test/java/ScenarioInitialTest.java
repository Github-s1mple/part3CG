import impl.Scenario;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScenarioInitialTest {
    // 正则表达式：匹配文件名末尾的“概率xxxx”
    private static final Pattern PROBABILITY_PATTERN = Pattern.compile("概率([0-9.]+)");

    public static void main(String[] args) {
        // 1. 配置模拟文件所在目录
        String simDirPath = "生鲜日订单模拟结果";
        File simDir = new File(simDirPath);

        // 2. 校验目录是否存在
        if (!simDir.exists() || !simDir.isDirectory()) {
            System.err.println("目录不存在或不是有效目录：" + simDirPath);
            return;
        }

        // 3. 遍历目录下的Excel文件，生成Scenario列表
        List<Scenario> scenarioList = new ArrayList<>();
        int idCounter = 1; // 自增ID

        File[] files = simDir.listFiles((dir, name) -> name.endsWith(".xlsx") && !name.contains("汇总"));
        if (files == null || files.length == 0) {
            System.err.println("目录下无模拟数据文件！");
            return;
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
            System.out.println("成功创建Scenario：" + scenario);
        }

        // 最终结果：scenarioList包含所有文件对应的Scenario对象
        System.out.println("\n共创建" + scenarioList.size() + "个Scenario对象");
    }

    /**
     * 从文件名中提取概率（如“概率0.3360”→0.3360）
     */
    private static Double extractProbabilityFromFileName(String fileName) {
        Matcher matcher = PROBABILITY_PATTERN.matcher(fileName);
        if (matcher.find()) {
            String probStr = matcher.group(1);
            probStr = probStr.replaceAll("\\.$", "");
            try {
                double prob = Double.parseDouble(probStr);
                // 额外校验：概率值应在0~1之间
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
}