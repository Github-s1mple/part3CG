package stage1;

import baseinfo.Constants;
import com.gurobi.gurobi.*;
import impl.*;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.text.DecimalFormat;

import static Utils.GurobiUtils.getStatusDescription;

/**
 * 第一阶段选址模型：对应数学模型(3.1)-(3.9)
 */
@Setter
@Getter
public class FirstStageLocationModel {
    // 输入数据核心对象
    private final InputData input;
    private final Candidates candidates;
    private final Fences fences;
    // Gurobi核心对象
    private GRBEnv env;
    private GRBModel model;
    // 变量缓存：key=规范变量名，value=变量对象（统一管理）
    private HashMap<String, GRBVar> varMap;
    // 约束缓存：key=规范约束名，value=约束对象（便于后续管理/冲突分析）
    private Map<String, GRBConstr> constrMap;
    // 问题维度参数
    private int candidatesNum;  // 候选配送中心数量
    private int fencesNum;       // 栅格数量
    // 集合定义（统一ID管理）
    private Set<Integer> C;     // 候选点集合（C = candidate IDs）
    private Set<Integer> G;     // 栅格集合（G = fence IDs）
    // 格式化输出（保留2位小数，与第二阶段一致）
    private final DecimalFormat df = new DecimalFormat("0.00");
    // 输出开关（控制日志详细程度）
    private Boolean outputFlag = false;
    // 求解耗时统计
    private double totalTimeSec;

    /**
     * 构造函数：初始化输入数据、问题维度、Gurobi环境
     */
    public FirstStageLocationModel(InputData input) throws GRBException {
        this.input = input;
        this.candidates = input.getCandidates();
        this.fences = input.getFences();
        // 初始化问题维度
        this.candidatesNum = input.getCandidates().size();
        this.fencesNum = input.getFences().size();

        // 初始化集合（统一ID管理，与第二阶段保持一致）
        this.C = new HashSet<>(this.candidates.getCandidateIndexes());  // 候选点集合
        this.G = new HashSet<>(this.fences.getFenceIndexList());      // 栅格集合

        // 初始化变量/约束缓存（统一管理，便于后续查询）
        this.varMap = new HashMap<>();
        this.constrMap = new HashMap<>();

        // 初始化Gurobi环境和模型（参数与第二阶段对齐）
        this.env = new GRBEnv();
        this.model = new GRBModel(env);

        // 设置Gurobi求解参数（与第二阶段保持一致的求解策略）
        model.set(GRB.IntParam.OutputFlag, outputFlag ? 1 : 0);  // 日志输出开关
        model.set(GRB.DoubleParam.FeasibilityTol, 1e-5);         // 可行性公差
        model.set(GRB.IntParam.Presolve, 1);                     // 启用预处理
        model.set(GRB.DoubleParam.MIPGap, 0.01);                 // MIP求解间隙（1%）
        model.set(GRB.StringParam.LogFile, "first_stage.log");   // 日志文件输出
    }

    /**
     * 定义所有决策变量（对应原模型O_i、X^b_ij、X^s_ij）
     * 变量命名规范：统一格式，便于调试和对接第二阶段
     */
    public void defineVariables() throws GRBException {
        // 1. O_i：是否选择候选点i（0-1变量）
        // 命名规范：O_候选点ID
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var = model.addVar(
                    0.0, 1.0,
                    0.0,  // 目标系数：后续设置
                    GRB.BINARY,
                    varName
            );
            varMap.put(varName, var);
        }

        // 2. Xb_ij：栅格i的基础需求分配给候选点j（0-1变量）
        // 命名规范：Xb_栅格ID_候选点ID
        for (int i : G) {
            for (int j : C) {
                String varName = String.format("Xb_%d_%d", i, j);
                GRBVar var = model.addVar(
                        0.0, 1.0,
                        0.0,  // 目标系数：基础分配无直接成本（成本在第二阶段）
                        GRB.BINARY,
                        varName
                );
                varMap.put(varName, var);
            }
        }

        // 3. Xs_ij：栅格i的额外需求分配给候选点j（0-1变量）
        // 命名规范：Xs_栅格ID_候选点ID
        for (int i : G) {
            for (int j : C) {
                String varName = String.format("Xs_%d_%d", i, j);
                GRBVar var = model.addVar(
                        0.0, 1.0,
                        0.0,  // 目标系数：额外分配无直接成本（成本在第二阶段）
                        GRB.BINARY,
                        varName
                );
                varMap.put(varName, var);
            }
        }

        // 变量定义完成后更新模型
        model.update();
        System.out.printf("第一阶段变量定义完成：共%d个变量%n", varMap.size());
    }

    /**
     * 设置目标函数（原模型3.1）：min Σf_i·O_i + 第二阶段成本期望（期望项通过Benders割平面后续添加）
     */
    public void setObjective() throws GRBException {
        GRBLinExpr objExpr = new GRBLinExpr();

        // 目标项：候选点固定成本之和（Σf_i·O_i）
        for (int i : C) {
            String varName = String.format("O_%d", i);
            GRBVar var = varMap.get(varName);
            Candidate candidate = candidates.getCandidate(i);
            double buildCost = candidate.getBuildCost();  // 候选点i的固定成本f_i
            objExpr.addTerm(buildCost, var);
        }

        // 设置目标函数：最小化
        model.setObjective(objExpr, GRB.MINIMIZE);
        model.update();
        System.out.println("第一阶段目标函数设置完成");
    }

    /**
     * 添加所有核心约束（对应原模型3.2-3.6）
     * 约束按类型拆分，便于维护和冲突分析
     */
    public void addCoreConstraints() throws GRBException {
        // 1. 基础需求唯一分配约束（原约束3.2）
        addBaseDemandUniqueAllocationConstraints();

        // 2. 基础需求分配-选址关联约束（原约束3.3）
        addBaseAllocationLocationLinkConstraints();

        // 3. 额外需求唯一分配约束（原约束3.4）
        addExtraDemandUniqueAllocationConstraints();

        // 4. 额外需求特殊约束（原约束3.5）
        addExtraDemandSpecialConstraints();

        // 5. 额外需求分配-选址关联约束（原约束3.6）
        addExtraAllocationLocationLinkConstraints();

        model.update();
        System.out.printf("第一阶段约束添加完成：共%d条约束%n", constrMap.size());
    }

    /**
     * 约束1：基础需求唯一分配（原约束3.2）
     * 数学表达：∀i∈G，Σ(j∈C) Xb_ij = 1
     */
    private void addBaseDemandUniqueAllocationConstraints() throws GRBException {
        for (int i : G) {
            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("BaseDemandUnique_i%d", i);

            for (int j : C) {
                String varName = String.format("Xb_%d_%d", i, j);
                expr.addTerm(1.0, varMap.get(varName));
            }

            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
        System.out.printf("添加基础需求唯一分配约束：%d条%n", G.size());
    }

    /**
     * 约束2：基础需求分配-选址关联（原约束3.3）
     * 数学表达：∀i∈G，∀j∈C，Xb_ij ≤ O_j
     */
    private void addBaseAllocationLocationLinkConstraints() throws GRBException {
        int count = 0;
        for (int i : G) {
            for (int j : C) {
                String constrName = String.format("BaseAllocLink_i%d_j%d", i, j);
                GRBLinExpr expr = new GRBLinExpr();

                String xbVarName = String.format("Xb_%d_%d", i, j);
                String oVarName = String.format("O_%d", j);
                expr.addTerm(1.0, varMap.get(xbVarName));
                expr.addTerm(-1.0, varMap.get(oVarName));

                GRBConstr constr = model.addConstr(expr, GRB.LESS_EQUAL, 0.0, constrName);
                constrMap.put(constrName, constr);
                count++;
            }
        }
        System.out.printf("添加基础需求分配-选址关联约束：%d条%n", count);
    }

    /**
     * 约束3：额外需求唯一分配（原约束3.4）
     * 数学表达：∀i∈G，Σ(j∈C) Xs_ij = 1
     */
    private void addExtraDemandUniqueAllocationConstraints() throws GRBException {
        for (int i : G) {
            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("ExtraDemandUnique_i%d", i);

            for (int j : C) {
                String varName = String.format("Xs_%d_%d", i, j);
                expr.addTerm(1.0, varMap.get(varName));
            }

            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
        System.out.printf("添加额外需求唯一分配约束：%d条%n", G.size());
    }

    /**
     * 约束4：额外需求特殊约束（原约束3.5）
     * 数学表达：∀i∈G，∀m∈C，∀n∈C，M·(2 - Xs_in - O_m) ≥ Δ_in - Δ_mv
     * 注：Δ_in/Δ_mv需替换为你的实际计算逻辑（如栅格-候选点距离差）
     */
    private void addExtraDemandSpecialConstraints() throws GRBException {
        int count = 0;
        for (int i : G) {
            for (int m : C) {
                for (int n : C) {
                    String constrName = String.format("ExtraSpecial_i%d_m%d_n%d", i, m, n);
                    GRBLinExpr expr = new GRBLinExpr();

                    // 变量项：M·Xs_in + M·O_m
                    String xsVarName = String.format("Xs_%d_%d", i, n);
                    String oVarName = String.format("O_%d", m);
                    expr.addTerm(Constants.M, varMap.get(xsVarName));
                    expr.addTerm(Constants.M, varMap.get(oVarName));

                    // Δ_in - Δ_mv：替换为你的实际计算逻辑（示例中暂设为0.0）
                    double delta = 0.0;
                    double rhs = 2 * Constants.M - delta;

                    GRBConstr constr = model.addConstr(expr, GRB.GREATER_EQUAL, rhs, constrName);
                    constrMap.put(constrName, constr);
                    count++;
                }
            }
        }
        System.out.printf("添加额外需求特殊约束：%d条%n", count);
    }

    /**
     * 约束5：额外需求分配-选址关联（原约束3.6）
     * 数学表达：∀i∈G，∀j∈C，Xs_ij ≤ O_j
     */
    private void addExtraAllocationLocationLinkConstraints() throws GRBException {
        int count = 0;
        for (int i : G) {
            for (int j : C) {
                String constrName = String.format("ExtraAllocLink_i%d_j%d", i, j);
                GRBLinExpr expr = new GRBLinExpr();

                String xsVarName = String.format("Xs_%d_%d", i, j);
                String oVarName = String.format("O_%d", j);
                expr.addTerm(1.0, varMap.get(xsVarName));
                expr.addTerm(-1.0, varMap.get(oVarName));

                GRBConstr constr = model.addConstr(expr, GRB.LESS_EQUAL, 0.0, constrName);
                constrMap.put(constrName, constr);
                count++;
            }
        }
        System.out.printf("添加额外需求分配-选址关联约束：%d条%n", count);
    }

    /**
     * 求解模型并返回选址结果（对接第二阶段的核心方法）
     */
    public LocationResult solve() throws GRBException {
        // 记录求解开始时间
        long startTime = System.currentTimeMillis();
        System.out.println("========================================");
        System.out.println("开始求解第一阶段选址模型......");
        System.out.println("========================================");

        try {
            // 执行求解
            model.optimize();

            // 输出求解状态
            int status = model.get(GRB.IntAttr.Status);
            System.out.println("求解状态：" + getStatusDescription(status));

            // 无可行解时，输出冲突约束分析
            if (status == GRB.Status.INFEASIBLE) {
                //printConflictConstraints();
                System.err.println("第一阶段模型无可行解，已输出冲突约束");
                return null;
            }

            // 非可行/最优状态，终止
            if (status != GRB.Status.OPTIMAL && status != GRB.Status.SUBOPTIMAL) {
                System.err.println("未找到最优解或可行解，终止第一阶段求解");
                return null;
            }

            // 输出求解结果摘要
            double totalFixedCost = model.get(GRB.DoubleAttr.ObjVal);
            System.out.println("\n【第一阶段最优结果摘要】");
            System.out.println("========================================");
            System.out.println("最优总固定成本：" + df.format(totalFixedCost) + " 元");
            System.out.println("选中的候选点数量：" + countSelectedCandidates());
            System.out.println("========================================");

            // 输出详细信息（根据outputFlag控制）
            if (outputFlag) {
                outputAllDecisionVariables();
            }

            // 生成选址结果（传递给第二阶段）
            return generateLocationResult();

        } finally {
            // 计算求解耗时
            long endTime = System.currentTimeMillis();
            totalTimeSec = (endTime - startTime) / 1000.0;
            System.out.printf("\n【第一阶段求解耗时】%n");
            System.out.printf("总耗时：%s 秒%n", df.format(totalTimeSec));

            // 释放Gurobi资源（避免内存泄漏）
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

        // 2. 提取基础需求分配结果（Xb_ij=1）
        Map<Integer, Integer> baseAllocation = new HashMap<>();
        for (int i : G) {
            for (int j : C) {
                String varName = String.format("Xb_%d_%d", i, j);
                GRBVar var = varMap.get(varName);
                if (var.get(GRB.DoubleAttr.X) > 0.5) {
                    baseAllocation.put(i, j);
                    break;
                }
            }
        }
        result.setBaseAllocation(baseAllocation);

        // 3. 提取额外需求分配结果（Xs_ij=1）
        Map<Integer, Integer> extraAllocation = new HashMap<>();
        for (int i : G) {
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

        // 2. 输出Xb_ij：基础需求分配变量
        System.out.println("\n2. 基础需求分配变量 Xb_ij（Xb_栅格ID_候选点ID = 取值）");
        System.out.println("----------------------------------------");
        for (int i : G) {
            for (int j : C) {
                String varName = String.format("Xb_%d_%d", i, j);
                GRBVar var = varMap.get(varName);
                double val = var.get(GRB.DoubleAttr.X);
                if (val > 0.5) {
                    System.out.printf("Xb_%d_%d = %s （栅格%d基础需求分配给候选点%d）%n",
                            i, j, df.format(val), i, j);
                }
            }
        }

        // 3. 输出Xs_ij：额外需求分配变量
        System.out.println("\n3. 额外需求分配变量 Xs_ij（Xs_栅格ID_候选点ID = 取值）");
        System.out.println("----------------------------------------");
        for (int i : G) {
            for (int j : C) {
                String varName = String.format("Xs_%d_%d", i, j);
                GRBVar var = varMap.get(varName);
                double val = var.get(GRB.DoubleAttr.X);
                if (val > 0.5) {
                    System.out.printf("Xs_%d_%d = %s （栅格%d额外需求分配给候选点%d）%n",
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
}