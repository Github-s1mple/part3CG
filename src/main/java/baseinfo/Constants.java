package baseinfo;

import java.util.Arrays;
import java.util.List;

public class Constants {
    // 小规模测试用
    // public static final Double TRUCK_MAX_DISTANCE = 5.0; // 小规模测试用
    // public static final Double TRUCK_COST_PER_METER = 0.01; // 小规模测试用
    // public static final Integer MAX_VISIT_NUM = 6; //必须设置为偶数
    // public static final Double MAX_CAPACITY = 30.0;
    // public static List<Double> EXPAND_STEP = Arrays.asList(0.9, 0.9); // 小规模测试用
    // public static final String allPointsFilePath = "all_points_test.xlsx";
    // public static final String candidatePointsFilePath = "candidate_points_test.xlsx";
    // 模型参数
    public static final Double TRUCK_MAX_DISTANCE = 20.0; //千米
    public static final Double TRUCK_COST_PER_METER = 0.001;
    public static final Integer MAX_VISIT_NUM = 10; //必须设置为偶数
    public static Integer MIN_VISIT_NUM = 3;
    public static final Double MIN_CARRIER_LOAD = 5.0;
    public static final Double MAX_CAPACITY = 200.0;
    public static final Double BIKE_COST_PER_KILOMETER_PER_ORDER = 2.0;
    public static final Integer BIKE_STABLE_COST_PER_ORDER = 4;
    public static final double DEPOT_STABLE_COST_PER_ORDER = 0.4;
    public static List<Double> EXPAND_STEP = Arrays.asList(0.08, 0.1, 0.08, 0.08, 0.08, 0.08); // 标签拓展的步长
    public static final Double BIKE_MAX_DISTANCE = 8.0; //千米
    public static final Double MIN_DISPATCH_NUM = 1.0;

    // 规则参数
    public static final Double OBJ_LB = 0.0;
    public static final double EARTH_RADIUS = 6371.0;
    public static final Integer CARRY_MAX_USE_TIMES = 1;

    // 算法模式
    public static String ALGO_MODE = "multi scenario"; //测GA用
    //public static String ALGO_MODE = "building"; //生成结果储存用

    // 完整数据的文件路径
    public static final String allPointsFilePath = "all_points.xlsx";
    public static final String candidatePointsFilePath = "candidate_points_4.xlsx";
    public static final String scenariosFileFolderPath = "生鲜日订单模拟结果_5";
    public static final String CACHE_SAVE_PATH = "firstStageResult_test.json";

    // 算法控制参数
    public static final Integer M = 100000; // 一个足够大的正数
    public static final Integer MAX_RLMP_COLUMNS = 400000;
    public static final Integer BASELINE_TIME_LIMIT = 60; // 列生成算法的总时间（baseline不适用）
    public static final Integer CG_TIME_LIMIT = 240; // 列生成算法的总时间（baseline不适用）
    public static final Double RMPSOLVE_PROPORTION = 0.005; // RMP求解时间占比
    public static final Double DUAL_MULTIPLIER = 1.0; //对偶值额外调参（标准值是1）
    public static final Integer OUTPUT_INTERVAL = 1000;
    public static final Integer ITERATION_COLUMN_NUM = 10; //每轮生成的列数（对偶值更新频率）
    public static final Integer SCENARIO_NUM = 10;

    // GA算法控制参数
    public static final Integer MAX_ITER = 5; // 最大迭代次数
    public static final Double GAP_THRESHOLD = 1e-4;
}
