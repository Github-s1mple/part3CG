package baseinfo;

public class Constants {
    // 模型参数
    public static final Double TRUCK_MAX_DISTANCE = 20.0; //千米
    public static final Double TRUCK_COST_PER_METER = 0.0005;
    public static final Integer MAX_VISIT_NUM = 20; //必须设置为偶数
    public static final Double MIN_CARRIER_LOAD = 10.0;
    public static final Double MAX_CAPACITY = 30.0;
    public static final Double BIKE_COST_PER_METER_PER_ORDER = 0.001;
    public static final Double EXPAND_STEP = 0.1; // 标签拓展的步长
    public static final Double BIKE_MAX_DISTANCE = 8.0; //千米
    public static final Double MIN_DISPATCH_NUM = 1.0;

    // 规则参数
    public static final Double OBJ_LB = 0.0;
    public static final double EARTH_RADIUS = 6371.0;
    public static final Integer CARRY_MAX_USE_TIMES = 1;

    // 算法模式
    //public static String ALGO_MODE = "single scenario";
    //public static String ALGO_MODE = "multi scenario";
    public static String ALGO_MODE = "building";

    // 完整数据的文件路径
    public static final String allPointsFilePath = "all_points.xlsx";
    public static final String candidatePointsFilePath = "candidate_points.xlsx";
    // 小规模测试数据的文件路径
    public static final String allPointsTestFilePath = "all_points_test.xlsx";
    public static final String candidatePointsTestFilePath = "candidate_points_test.xlsx";
    public static final String scenariosFileFolderPath = "生鲜日订单模拟结果_5";

    // 算法控制参数
    public static final Integer M = 10000000; // 一个足够大的正数
    public static final Integer MAX_RLMP_COLUMNS = 1000000;
    public static final Integer ITERATION_TIME_LIMIT = 300; // 列生成算法的总时间（baseline不适用）
    public static final Double RMPSOLVE_PROPORTION = 0.3; // RMP求解时间占比
    public static final Double DUAL_MULTIPLIER = 1.0; //对偶值额外调参（标准值是1）
    public static final Integer OUTPUT_INTERVAL = 1000;
    public static final Integer ITERATION_COLUMN_NUM = 1000; //每轮生成的列数（对偶值更新频率）
    public static final Boolean START_WITH_INITIALSOLUTION = true; //用初始解启动
    public static final Integer SCENARIO_NUM = 1;

    // GA算法控制参数
    public static final Integer MAX_ITER = 20; // 最大迭代次数
    public static final Double GAP_THRESHOLD = 1e-4;
}
