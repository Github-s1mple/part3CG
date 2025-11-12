package baseinfo;

public class Constants {
    public static final Integer MAXVISITNUMBER = 10;
    public static final Double MAXDISTANCE = 5.0;
    public static final Double DELIVERCOSTPERMETER = 0.00005;
    public static final Double DUALMULTIPLIER = 1.0; //对偶值调参
    public static final Integer OUTPUTINTERVAL = 100;
    public static final Double MAXCAPACITY = 20.0;
    public static final Double MINCARRIERLOAD = 5.0;
    public static final Double OBJ_LB = 0.0;
    public static final Integer ITERATIONCOLUMNNUM = 1000;
    public static final Integer MAXRMLPCOLUMNS = 100000;
    public static final double EARTH_RADIUS = 6371.0;
    public static final Boolean ISDIFFERENTCARRIER = false;
    public static final double DEPOTDISTANCETOFENCEVALUE = 0.1;
    public static final Integer CARRYMAXUSETIMES = 1;
    // 算法模式
    public static final String ALGOMODE = "C"; //"CG"是使用完整数据进行测试
    // 完整数据的文件路径
    public static final String allPointsFilePath = "all_points.xlsx";
    public static final String candidatePointsFilePath = "candidate_points.xlsx";
    // 小规模测试数据的文件路径
    public static final String allPointsTestFilePath = "all_points_test.xlsx";
    public static final String candidatePointsTestFilePath = "candidate_points_test.xlsx";
}
