package Stages;

import baseinfo.Constants;
import com.gurobi.gurobi.*;
import impl.*;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.text.DecimalFormat;

import static Utils.GurobiUtils.getStatusDescription;
import static Utils.SelfPickupProbabilityCalculator.calculateSelfPickupProbability;

/**
 * 第一阶段选址模型（集成Benders割平面θ变量）
 * 核心功能：确定候选配送中心的选址方案及栅格配送需求的分配策略 + 第二阶段成本期望θ
 */
@Setter
@Getter
public class FirstStageLocationModel {
    // 输入数据核心对象
    private final InputData input;
    private final Candidates candidates;
    private final Fences fences;
    private final List<HashMap<Integer, Double>> candidateToFenceDist;  // 候选点到栅格的距离矩阵
    // Gurobi核心对象
    private GRBEnv env;         // Gurobi环境
    private GRBModel model;     // Gurobi模型实例
    // 变量缓存：key=规范变量名，value=变量对象（统一管理所有决策变量）
    private HashMap<String, GRBVar> varMap;
    // 约束缓存：key=规范约束名，value=约束对象（便于后续管理和冲突分析）
    private Map<String, GRBConstr> constrMap;
    // 问题维度参数
    private int candidatesNum;  // 候选配送中心数量
    private int fencesNum;       // 栅格数量
    // 集合定义（统一ID管理，与数学模型保持一致）
    private Set<Integer> C;     // 候选点集合
    private Set<Integer> N;     // 栅格集合
    // 格式化输出（保留2位小数，与第二阶段格式统一）
    private final DecimalFormat df = new DecimalFormat("0.00");
    // 输出开关（控制日志详细程度：true输出详细日志，false仅输出关键信息）
    private Boolean outputFlag = false;
    // 求解耗时统计（单位：秒）
    private double totalTimeSec;
    // 存储固定的O_i取值（key=候选点ID，value=0或1，为空表示不固定）
    private Map<Integer, Integer> fixedOValues;
    private double totalCost;
    private boolean isFirstIter = true;

    /**
     * 构造函数：初始化输入数据、问题维度及Gurobi环境
     * @param input 输入数据总对象，包含候选点、栅格及距离矩阵等信息
     * @throws GRBException Gurobi环境初始化可能抛出的异常
     */
    public FirstStageLocationModel(InputData input) throws GRBException {
        this.input = input;
        this.candidates = input.getCandidates();
        this.fences = input.getFences();
        // 初始化问题维度参数
        this.candidatesNum = input.getCandidates().size();
        this.fencesNum = input.getFences().size();
        this.candidateToFenceDist = input.getCandidateDistanceMatrix();
        // 初始化集合（统一ID管理，与第二阶段保持一致）
        this.C = new HashSet<>(this.candidates.getCandidateIndexes());  // 候选点ID集合
        this.N = new HashSet<>(this.fences.getFenceIndexList());         // 栅格ID集合
        this.fixedOValues = input.getInitialOj();
        // 初始化变量/约束缓存（统一管理，便于后续查询和修改）
        this.varMap = new HashMap<>();
        this.constrMap = new HashMap<>();

        // 初始化Gurobi环境和模型（参数与第二阶段对齐，保证求解策略一致性）
        this.env = new GRBEnv();
        this.model = new GRBModel(env);

        // 设置Gurobi求解参数（与第二阶段保持一致的求解策略）
        model.set(GRB.IntParam.OutputFlag, outputFlag ? 1 : 0);  // 日志输出开关（1=开启，0=关闭）
        model.set(GRB.DoubleParam.FeasibilityTol, 1e-5);         // 可行性公差（控制约束满足精度）
        model.set(GRB.IntParam.Presolve, 1);                     // 启用预处理（加速求解）
        model.set(GRB.DoubleParam.MIPGap, 0.01);                 // MIP求解间隙（1%，达到该间隙即停止）
        model.set(GRB.StringParam.LogFile, "first_stage.log");   // 日志文件输出路径
    }


    /**
     * 定义所有决策变量（对应原模型O_i、Xs_ij + 新增θ）
     * 变量命名规范：采用"变量类型_索引1_索引2"格式，便于调试和对接第二阶段
     * @throws GRBException 变量添加过程可能抛出的异常
     */
    public void defineVariables() throws GRBException {
        // 1. O_i：是否选择候选点i（0-1变量）
        // 命名规范：O_候选点ID
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var;

            // 检查是否需要固定当前O_i的值
            Integer fixedValue = fixedOValues.get(i);
            if (fixedValue != null) {
                // 固定为已知值：上下界设为fixedValue，变量类型改为连续型（实际为常数）
                var = model.addVar(
                        fixedValue, fixedValue,  // 上下界锁定为固定值
                        0.0,                      // 目标系数（暂设为0）
                        GRB.BINARY,           // 常数用连续型表示
                        varName
                );
                if (outputFlag){
                    System.out.printf("已固定变量 O_%d = %d%n", i, fixedValue);
                }
            } else {
                // 不固定：保持原有二进制变量特性
                var = model.addVar(
                        0.0, 0.0,                // 变量上下界（0-1）
                        0.0,                      // 目标系数（暂设为0）
                        GRB.BINARY,               // 变量类型（二进制）
                        varName                   // 变量名
                );
            }
            varMap.put(varName, var);
        }

        // 2. Xs_ij：栅格i的配送需求是否分配给候选点j（0-1变量，1=分配，0=不分配）
        // 命名规范：Xs_栅格ID_候选点ID
        for (int i : N) {
            for (int j : C) {
                String varName = String.format("Xs_%d_%d", i, j);
                GRBVar var = model.addVar(
                        0.0, 1.0,                // 变量上下界（0-1）
                        0.0,                      // 目标系数：配送分配无直接成本（成本在第二阶段体现）
                        GRB.BINARY,               // 变量类型（二进制）
                        varName                   // 变量名
                );
                varMap.put(varName, var);
            }
        }
        // 变量定义完成后更新模型
        model.update();
        System.out.printf("第一阶段变量定义完成：共%d个变量%n", varMap.size());
    }

    /**
     * 设置目标函数：最小化候选点固定成本与第二阶段期望成本之和
     * 数学表达：min Σ(f_i·O_i) + 全配送成本 - θ
     * @throws GRBException 目标函数设置可能抛出的异常
     */
    public void setObjective() throws GRBException {
        GRBLinExpr objExpr = new GRBLinExpr();

        // 1. 固定成本项：Σf_i·O_i
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var = varMap.get(varName);
            double buildCost = candidates.getCandidate(i).getBuildCost();
            objExpr.addTerm(buildCost, var);
        }

        // 2. 骑手配送成本项
        double unitTransCost = Constants.BIKE_COST_PER_METER_PER_ORDER; // 单位距离运输成本（元/米）
        for (int i : N) {
            Fence fence = fences.getFence(i);
            for (int j : C) {
                String xName = String.format("Xs_%d_%d", i, j);
                GRBVar xVar = varMap.get(xName);
                if (xVar == null) continue;

                // 计算路径i→j的距离（米）
                HashMap<Integer, Double> distMap = candidateToFenceDist.get(- j - 1);
                double dist = distMap.get(i);
                double bikeDemand = (1 - calculateSelfPickupProbability(dist)) * fence.getTotalDemand();
                objExpr.addTerm(bikeDemand * unitTransCost * dist * 1000, xVar);
            }
        }

        // 设置最小化目标
        model.setObjective(objExpr, GRB.MINIMIZE);
        model.update();
        System.out.println("第一阶段目标函数设置完成");
    }

    /**
     * 添加所有核心约束（对应原模型3.2-3.6）
     * 约束按类型拆分实现，提升代码可维护性和冲突分析效率
     * @throws GRBException 约束添加过程可能抛出的异常
     */
    public void addCoreConstraints() throws GRBException {
        // 1. 最大配送距离约束（限制超出服务范围的分配）
        addMaxDistanceConstraints();

        // 2. 配送需求唯一分配约束（原约束3.4）
        addExtraDemandUniqueAllocationConstraints();

        // 3. 配送需求特殊约束（原约束3.5，确保分配给最近候选点）
        addExtraDemandSpecialConstraints();

        // 4. 配送需求分配-选址关联约束（原约束3.6，确保仅分配给已选中的候选点）
        addExtraAllocationLocationLinkConstraints();

        model.update();  // 更新模型使约束生效
        System.out.printf("第一阶段约束添加完成：共%d条约束%n", constrMap.size());
    }

    /**
     * 约束3.4：配送需求唯一分配约束
     * 数学表达：∀i∈N，Σ(j∈C) Xs_ij = 1
     * 含义：每个栅格的配送需求必须且只能分配给一个候选点
     * @throws GRBException 约束添加可能抛出的异常
     */
    private void addExtraDemandUniqueAllocationConstraints() throws GRBException {
        for (int i : N) {  // 遍历每个栅格i
            GRBLinExpr expr = new GRBLinExpr();  // 构建约束表达式
            String constrName = String.format("ExtraDemandUnique_i%d", i);  // 约束名：唯一分配_栅格i

            for (int j : C) {  // 遍历所有候选点j
                String varName = String.format("Xs_%d_%d", i, j);  // 获取Xs_ij变量
                expr.addTerm(1.0, varMap.get(varName));             // 累加Xs_ij到表达式
            }

            // 添加约束：Xs_i1 + Xs_i2 + ... + Xs_ik = 1
            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
    }

    /**
     * 约束3.5：配送需求特殊约束（最近候选点优先分配）
     * 数学表达：∀i∈N，∀m∈C，∀n∈C（n比m更近于i），Xs_im + O_n ≤ 1
     * 含义：若候选点n比m更近于栅格i，且n被选中，则i的配送需求不能分配给m
     * @throws GRBException 约束添加可能抛出的异常
     */
    private void addExtraDemandSpecialConstraints() throws GRBException {
        for (int i : N) {  // 遍历每个栅格i
            for (int m : C) {  // 遍历每个候选点m（作为潜在分配对象）
                // 寻找比m更近于i的候选点n
                for (int n : C) {
                    if (m == n) continue;  // 跳过自身（m与n为同一候选点时无需判断）

                    // 获取候选点m和n到栅格i的距离（转换为米，确保单位一致）
                    int depotMIdx = -m - 1;  // 距离矩阵中m的索引（业务约定格式）
                    int depotNIdx = -n - 1;  // 距离矩阵中n的索引（业务约定格式）
                    HashMap<Integer, Double> distMMap = candidateToFenceDist.get(depotMIdx);
                    HashMap<Integer, Double> distNMap = candidateToFenceDist.get(depotNIdx);
                    double distM = distMMap.get(i) * 1000;  // 转换为米
                    double distN = distNMap.get(i) * 1000;  // 转换为米

                    // 若n比m严格更近（排除距离相等的情况，避免冗余约束）
                    if (distN < distM - 1e-6) {
                        GRBLinExpr expr = new GRBLinExpr();
                        // 添加Xs_im变量（系数1.0）：表示i的配送需求是否分配给m
                        String xsVarName = String.format("Xs_%d_%d", i, m);
                        expr.addTerm(1.0, varMap.get(xsVarName));
                        // 添加O_n变量（系数1.0）：表示n是否被选中
                        String oVarName = String.format("O_%d", n);
                        expr.addTerm(1.0, varMap.get(oVarName));

                        // 添加约束：Xs_im + O_n ≤ 1（若n被选中，则Xs_im必须为0）
                        String constrName = String.format("NearestConstraint_i%d_m%d_n%d", i, m, n);
                        GRBConstr constr = model.addConstr(expr, GRB.LESS_EQUAL, 1.0, constrName);
                        constrMap.put(constrName, constr);
                    }
                }
            }
        }
    }

    /**
     * 约束3.6：配送需求分配-选址关联约束
     * 数学表达：∀i∈N，∀j∈C，Xs_ij ≤ O_j
     * 含义：栅格i的配送需求只能分配给已选中的候选点j（O_j=1时Xs_ij才可能为1）
     * @throws GRBException 约束添加可能抛出的异常
     */
    private void addExtraAllocationLocationLinkConstraints() throws GRBException {
        for (int i : N) {  // 遍历每个栅格i
            for (int j : C) {  // 遍历每个候选点j
                String constrName = String.format("ExtraAllocLink_i%d_j%d", i, j);  // 约束名：分配关联_栅格i_候选点j
                GRBLinExpr expr = new GRBLinExpr();

                // 获取变量：Xs_ij（i的配送需求分配给j）和O_j（j是否被选中）
                String xsVarName = String.format("Xs_%d_%d", i, j);
                String oVarName = String.format("O_%d", j);
                expr.addTerm(1.0, varMap.get(xsVarName));   // +Xs_ij
                expr.addTerm(-1.0, varMap.get(oVarName));   // -O_j

                // 添加约束：Xs_ij - O_j ≤ 0 → Xs_ij ≤ O_j
                GRBConstr constr = model.addConstr(expr, GRB.LESS_EQUAL, 0.0, constrName);
                constrMap.put(constrName, constr);
            }
        }
    }

    /**
     * 最大配送距离约束（对应业务规则：超出最大服务距离的候选点不能分配需求）
     * 数学表达：∀i∈N，∀j∈C，若d_ij > 最大服务距离，则Xs_ij = 0
     * 含义：栅格i的配送需求不能分配给距离超过最大服务范围的候选点j
     * @throws GRBException 约束添加可能抛出的异常
     */
    private void addMaxDistanceConstraints() throws GRBException {
        for (int i : N) {  // 遍历每个栅格i
            for (int j : C) {  // 遍历每个候选点j
                // 获取候选点j到栅格i的距离（单位：原单位，与Constants中阈值单位一致）
                int depotIdx = -j - 1;  // 距离矩阵中j的索引（业务约定格式）
                HashMap<Integer, Double> distMap = candidateToFenceDist.get(depotIdx);
                double d_ij = distMap.get(i);

                // 若距离超过最大服务距离（附加微小误差避免浮点比较问题）
                if (d_ij > Constants.BIKE_MAX_DISTANCE + 1e-6) {
                    String xsVarName = String.format("Xs_%d_%d", i, j);  // Xs_ij变量
                    String constrName = String.format("MaxDistanceConstraint_i%d_j%d", i, j);
                    // 添加约束：Xs_ij ≤ 0（强制该变量为0）
                    GRBConstr constr = model.addConstr(varMap.get(xsVarName), GRB.LESS_EQUAL, 0.0, constrName);
                    constrMap.put(constrName, constr);
                }
            }
        }
    }


    public LocationResult solve() throws GRBException {
        // 记录求解开始时间
        long startTime = System.currentTimeMillis();
        System.out.println("开始求解第一阶段选址模型");
        // 输出固定O_i的信息
        if (this.outputFlag){
            if (!fixedOValues.isEmpty()) {
                System.out.println("固定的O_i值：" + fixedOValues);
            } else {
                System.out.println("当前模式：自动求解所有变量");
            }
        }

        try {
            model.optimize();
            // 输出求解状态
            int status = model.get(GRB.IntAttr.Status);
            System.out.println("求解状态：" + getStatusDescription(status));

            // 非可行/最优状态，终止
            if (status != GRB.Status.OPTIMAL && status != GRB.Status.SUBOPTIMAL) {
                System.err.println("未找到最优解或可行解，终止第一阶段求解");
                return null;
            }

            // 输出求解结果摘要
            this.totalCost = model.get(GRB.DoubleAttr.ObjVal);
            System.out.println("最优总成本：" + df.format(totalCost) + " 元");
            System.out.println("选中的候选点数量：" + countSelectedCandidates());

            // 输出详细信息
            if (outputFlag) {
                outputAllDecisionVariables();
            }

            // 生成选址结果
            return generateLocationResult();

        } finally {
            // 计算求解耗时
            long endTime = System.currentTimeMillis();
            totalTimeSec = (endTime - startTime) / 1000.0;
            System.out.printf("\n【第一阶段求解耗时】%n");
            System.out.printf("总耗时：%s 秒%n", df.format(totalTimeSec));

            model.dispose();
            env.dispose();
        }
    }


    /**
     * 生成选址结果（封装传递给第二阶段的信息）
     */
    private LocationResult generateLocationResult() throws GRBException {
        LocationResult result = new LocationResult();

        // 1. 提取选中的候选点（O_i=1）
        List<Integer> selectedCandidates = new ArrayList<>();
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var = varMap.get(varName);
            if (var.get(GRB.DoubleAttr.X) > 0.5) {
                selectedCandidates.add(i);
            }
        }
        result.setSelectedCandidates(selectedCandidates);

        // 2. 提取配送需求分配结果（Xs_ij=1）
        Map<Integer, Integer> extraAllocation = new HashMap<>();
        for (int i : N) {
            for (int j : C) {
                String varName = String.format("Xs_%d_%d", i, j);
                GRBVar var = varMap.get(varName);
                if (var.get(GRB.DoubleAttr.X) > 0.5) {
                    extraAllocation.put(i, j);
                    break;
                }
            }
        }
        result.setExtraAllocation(extraAllocation);
        return result;
    }

    /**
     * 输出所有决策变量取值（调试用）
     */
    private void outputAllDecisionVariables() throws GRBException {
        System.out.println("\n【第一阶段决策变量详细取值】");
        System.out.println("========================================");

        // 1. 输出O_i：候选点选择变量
        System.out.println("\n1. 候选点选择变量 O_i（O_候选点ID = 取值）");
        System.out.println("----------------------------------------");
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var = varMap.get(varName);
            double val = var.get(GRB.DoubleAttr.X);
            System.out.printf("O_%d = %s （%s）%n",
                    i, df.format(val), val > 0.5 ? "选中" : "未选中");
        }

        // 2. 输出Xs_ij：配送需求分配变量
        System.out.println("\n3. 配送需求分配变量 Xs_ij（Xs_栅格ID_候选点ID = 取值）");
        System.out.println("----------------------------------------");
        for (int i : N) {
            for (int j : C) {
                String varName = String.format("Xs_%d_%d", i, j);
                GRBVar var = varMap.get(varName);
                double val = var.get(GRB.DoubleAttr.X);
                if (val > 0.5) {
                    System.out.printf("Xs_%d_%d = %s （栅格%d配送配送需求分配给候选点%d）%n",
                            i, j, df.format(val), i, j);
                }
            }
        }
        System.out.println("========================================");
    }

    /**
     * 辅助方法：统计选中的候选点数量
     */
    private int countSelectedCandidates() throws GRBException {
        int count = 0;
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var = varMap.get(varName);
            if (var.get(GRB.DoubleAttr.X) > 0.5) {
                count++;
            }
        }
        return count;
    }


    /**
     * 释放Gurobi资源
     */
    public void releaseResources() throws GRBException {
        if (model != null) model.dispose();
        if (env != null) env.dispose();
        System.out.println("第一阶段Gurobi资源已释放");
    }


    public Map<Integer, Integer> getCurrentOSolution() throws GRBException {
        Map<Integer, Integer> currentOSol = new HashMap<>();
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var = varMap.get(varName);
            double val = var.get(GRB.DoubleAttr.X);
            currentOSol.put(i, val > 1e-6 ? 1 : 0);
        }
        return currentOSol;
    }
}
