package Utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import impl.LocationResult;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 持久化工具类：保存/读取 LocationResult 到本地文件
 */
public class ResultPersistenceUtil {
    // 保存路径（可自定义，比如项目根目录下的 result 文件夹）
    private static final String SAVE_PATH = "firstStageResult.json";
    // Gson 实例（配置格式化输出，便于查看）
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting() // 格式化JSON
            .serializeNulls()    // 序列化null值
            .create();

    /**
     * 保存 firstStageResult 到本地JSON文件
     * @param result 要保存的结果对象
     * @throws IOException 读写文件异常
     */
    public static void saveFirstStageResult(LocationResult result) throws IOException {
        // 1. 创建文件父目录（避免路径不存在）
        File file = new File(SAVE_PATH);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // 2. 将对象转为JSON并写入文件
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(result, writer);
        }
        System.out.println("FirstStageResult 已保存到：" + file.getAbsolutePath());
    }

    /**
     * 从本地JSON文件读取 firstStageResult
     * @return 读取的结果对象（null表示文件不存在/读取失败）
     */
    public static LocationResult loadFirstStageResult(String SAVE_PATH) {
        File file = new File(SAVE_PATH);
        if (!file.exists()) {
            System.out.println("未找到保存的 FirstStageResult 文件，将重新求解");
            return null;
        }

        // 读取文件并转为对象
        try (Reader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, LocationResult.class);
        } catch (Exception e) {
            System.err.println("读取 FirstStageResult 失败，将重新求解：" + e.getMessage());
            return null;
        }
    }

    /**
     * 可选：删除已保存的结果文件（比如数据过期时）
     */
    public static void deleteSavedResult() {
        File file = new File(SAVE_PATH);
        if (file.exists() && file.delete()) {
            System.out.println("已删除旧的 FirstStageResult 文件");
        }
    }
}