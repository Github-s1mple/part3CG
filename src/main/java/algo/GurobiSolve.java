package algo;

import com.gurobi.gurobi.*;
import impl.*;
import lombok.Getter;
import lombok.Setter;
import baseinfo.Constants;

import java.util.*;

import static Utils.CommonUtils.calculateDistance;
import static baseinfo.MapDistance.calculateSphericalDistance;

@Setter
@Getter
public class GurobiSolve {
    private final Instance instance;
    private final Fences fences;
    private final Depots depots;
    private final List<Carrier> carriers;
    private final List<Fence> fakeFences;
    private Boolean outputFlag = false;
    // 核心数据集合
    private Set<Integer> N;  // 围栏集合（点ID），例如 {1,2,...,F}（F为围栏数量）
    private Set<Integer> M;  // 仓库集合（点ID），例如 {0, F+1}（虚拟点）
    private Set<Integer> K;  // 载具集合（载具ID），例如 {1,2,...,V}（V为载具数量）
    private Set<Integer> V;  // 所有点的集合（V = M ∪ N）
    // Gurobi核心对象
    private GRBEnv env;
    private GRBModel model;

    // 变量缓存：key=变量名（规范命名），value=变量对象
    private HashMap<String, GRBVar> varMap;
    // 约束缓存：后续用于约束管理
    private HashMap<String, GRBConstr> constraintsMap;

    // 问题维度参数
    private int numDepots;           // 仓库数量
    private int numFences;           // 围栏数量
    private int numCarriers;         // 载具数量
    private int totalNodes;          // 总节点数（仓库 + 围栏）


    public GurobiSolve(Instance instance) throws GRBException {
        this.instance = instance;
        this.fences = instance.getFences();
        this.depots = instance.getDepots();
        this.carriers = instance.getCarrierList();
        this.fakeFences = new ArrayList<>();

        // 初始化问题维度
        this.numDepots = depots.getDepotList().size();
        this.numFences = fences.getFenceList().size();
        this.numCarriers = carriers.size();
        this.totalNodes = numDepots + numFences;  // 仓库节点编号：0~numDepots-1；围栏节点编号：numDepots~totalNodes-1

        // 围栏集合N：点ID从1开始（避免与仓库ID冲突）
        N = new HashSet<>();
        for (int i = 0; i < numFences; i++) {
            N.add(i + 1);  // 围栏ID：1,2,...,numFences
        }

        // 仓库集合M：点ID从0开始（虚拟点）
        M = new HashSet<>();
        for (int i = 0; i < numDepots; i++) {
            M.add(-i - 1);  // 仓库ID：0,-1,...（用负数区分围栏）
        }

        // 载具集合K：载具ID从1开始
        K = new HashSet<>();
        for (int i = 0; i < numCarriers; i++) {
            K.add(i + 1);  // 载具ID：1,2,...,numVehicles
        }

        // 所有点的集合V = M ∪ N
        V = new HashSet<>(M);
        V.addAll(N);

        // 初始化缓存容器
        this.varMap = new HashMap<>();
        this.constraintsMap = new HashMap<>();
        int fakeCount = 0;
        for(Depot depot : depots.getDepotList()) {
            Fence fakefence = depot.depot2Fence(fakeCount);
            fakeCount ++;
            fakeFences.add(fakefence);
        }
        // 初始化Gurobi环境和模型（遵循示例语法）
        this.env = new GRBEnv();
        this.model = new GRBModel(env);

        // 设置模型参数（控制求解行为）
        model.set(GRB.IntParam.OutputFlag, outputFlag ? 1 : 0);  // 日志输出开关
        model.set(GRB.DoubleParam.FeasibilityTol, 1e-5);         // 可行性 tolerance
        model.set(GRB.IntParam.Presolve, 1);                     // 启用预处理
        model.set(GRB.DoubleParam.MIPGap, 0.01);                 // MIP求解间隙（1%）

        // 2. 初始化Gurobi环境
        env = new GRBEnv();
        model = new GRBModel(env);
        model.set(GRB.IntParam.OutputFlag, 0);  // 关闭日志输出（按需开启）
        model.set(GRB.DoubleParam.MIPGap, 0.01); // 求解精度
    }


    /**
     * 定义所有决策变量
     */
    public void defineVariables() throws GRBException {
        // 1. Xik：载具k是否访问点i（二进制变量）
        // 点i ∈ V（所有点），载具k ∈ K
        for (int i : N) {
            for (int k : K) {
                String varName = String.format("X_%d_%d", i, k);
                // 取值范围：0-1（未访问-访问）
                GRBVar var = model.addVar(
                        0.0, 1.0,
                        0.0,  // 目标系数后续在目标函数中设置
                        GRB.BINARY,
                        varName
                );
                varMap.put(varName, var);
            }
        }

        // 2. Zijk：载具k访问i后访问j（二进制变量）
        // 点i,j ∈ V，i ≠ j；载具k ∈ K
        for (int i : V) {
            for (int j : V) {
                if (i == j) continue;  // 排除自环
                for (int k : K) {
                    String varName = String.format("Z_%d_%d_%d", i, j, k);
                    // 取值范围：0-1（不行驶-行驶）
                    GRBVar var = model.addVar(
                            0.0, 1.0,
                            0.0,  // 目标系数后续在运输成本中设置
                            GRB.BINARY,
                            varName
                    );
                    varMap.put(varName, var);
                }
            }
        }

        // 3. dik：载具k在点i的装载量（连续变量）
        // 仅围栏有装载量（仓库无需求），i ∈ N；载具k ∈ K
        for (int i : N) {
            // 找到围栏i对应的需求（i是围栏ID，对应fences中索引为i-1）
            Fence fence = fences.getFenceList().get(i - 1);  // 围栏ID从1开始，列表索引从0开始
            double demand = fence.getDeliverDemand();

            for (int k : K) {
                String varName = String.format("d_%d_%d", i, k);
                // 取值范围：0 ≤ dik ≤ 围栏i的需求（不超过总需求）
                GRBVar var = model.addVar(
                        0.0, demand,
                        0.0,  // 目标系数后续在收益中设置
                        GRB.CONTINUOUS,
                        varName
                );
                varMap.put(varName, var);
            }
        }

        // 4. Uik：MTZ约束中的整数变量（用于消除子回路）
        // 点i ∈ V，载具k ∈ K；取值范围通常为[2, |V|]（避免与起点冲突）
        for (int i : N) {
            for (int k : K) {
                String varName = String.format("U_%d_%d", i, k);
                // 取值范围：1到总点数（足够大的整数即可）
                GRBVar var = model.addVar(
                        1.0, V.size(),
                        0.0,  // 无直接目标系数
                        GRB.INTEGER,
                        varName
                );
                varMap.put(varName, var);
            }
        }

        // 变量定义完成后更新模型
        model.update();
        System.out.printf("变量定义完成：共%d个变量%n", varMap.size());
    }

    private Map<String, GRBConstr> constrMap = new HashMap<>();

    /**
     * 添加所有核心约束
     */
    public void addCoreConstraints() throws GRBException {
        // 1. 载具从仓库出发约束
        addDepartFromDepotConstraints();

        // 2. 载具返回仓库约束
        addReturnToDepotConstraints();

        // 3. 路径连续性约束（流量守恒）
        addFlowConservationConstraints();

        // 4. 访问与路径关联约束（Zijk → Xik）
        addVisitPathLinkConstraints();

        // 5. 装载量与访问关联约束（dik → Xik）
        addLoadVisitLinkConstraints();

        // 6. 载具容量约束
        addVehicleCapacityConstraints();

        // 7. MTZ子回路消除约束
        addMTZConstraints();

        model.update();
        System.out.printf("约束添加完成：共%d条约束%n", constrMap.size());
    }


    /**
     * 1. 载具从仓库出发约束
     * 逻辑：每个使用的载具k必须从某个仓库m出发
     * 数学表达：∀k∈K，∑(m∈M) ∑(j∈V\m) Zmjk = 1 （若载具k被使用）
     * 简化：载具k的出发路径数 = 1（通过Xmk间接关联）
     */
    private void addDepartFromDepotConstraints() throws GRBException {
        for (int k : K) {  // 遍历载具
            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("depart_k%d", k);

            for (int m : M) {  // 遍历仓库
                for (int j : N) {  // 遍历目标点
                    // 累加从仓库m到j的路径变量
                    String zName = String.format("Z_%d_%d_%d", m, j, k);
                    expr.addTerm(1.0, varMap.get(zName));
                }
            }

            // 约束：载具k的出发路径数 = 1（若使用则必须出发）
            // 注：实际可关联载具使用变量（如是否启用），此处简化为必须使用
            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
    }


    /**
     * 2. 载具返回仓库约束
     * 逻辑：每个使用的载具k必须返回某个仓库m
     * 数学表达：∀k∈K，∑(m∈M) ∑(i∈V\m) Zikm = 1
     */
    private void addReturnToDepotConstraints() throws GRBException {
        for (int k : K) {  // 遍历载具
            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("return_k%d", k);

            for (int m : M) {  // 遍历仓库
                for (int i : N) {  // 遍历起点
                    // 累加从i到仓库m的路径变量
                    String zName = String.format("Z_%d_%d_%d", i, m, k);
                    expr.addTerm(1.0, varMap.get(zName));
                }
            }

            // 约束：载具k的返回路径数 = 1
            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
    }


    /**
     * 3. 路径连续性约束（流量守恒）
     * 逻辑：对于围栏点i，载具k进入的路径数 = 离开的路径数
     * 数学表达：∀i∈N，∀k∈K，∑(j∈V\i) Zijkr = ∑(j∈V\i) Zjik
     */
    private void addFlowConservationConstraints() throws GRBException {
        for (int i : N) {  // 遍历围栏（仓库无需流量守恒）
            for (int k : K) {  // 遍历载具
                GRBLinExpr expr = new GRBLinExpr();
                String constrName = String.format("flow_i%d_k%d", i, k);

                // 左侧：进入i的路径（∑Zjik）
                for (int j : V) {
                    if (j == i) continue;
                    String zInName = String.format("Z_%d_%d_%d", j, i, k);
                    expr.addTerm(1.0, varMap.get(zInName));
                }

                // 右侧：离开i的路径（∑Zijk），移到左侧变为减号
                for (int j : V) {
                    if (j == i) continue;
                    String zOutName = String.format("Z_%d_%d_%d", i, j, k);
                    expr.addTerm(-1.0, varMap.get(zOutName));
                }

                // 约束：进入路径数 = 离开路径数
                GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 0.0, constrName);
                constrMap.put(constrName, constr);
            }
        }
    }


    /**
     * 4. 访问与路径关联约束（Zijk → Xik）
     * 逻辑：若载具k从i到j，则必须访问i和j
     * 数学表达：∀i,j∈V,i≠j，∀k∈K，Zijk ≤ Xik 且 Zijk ≤ Xjk
     */
    private void addVisitPathLinkConstraints() throws GRBException {
        for (int i : N) {
            for (int k : K) {
                String constrName = String.format("link_Z_X_%d_%d", i, k);
                String xIName = String.format("X_%d_%d", i, k);
                GRBLinExpr expr = new GRBLinExpr();
                for (int j : V) {
                    String zName = String.format("Z_%d_%d_%d", j, i, k);
                    expr.addTerm(1.0, varMap.get(zName));
                }
                expr.addTerm(-1.0, varMap.get(xIName));
                GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 0.0, constrName);
                constrMap.put(constrName, constr);
            }
        }
    }

    //TODO：
    /**
     * 5. 装载量与访问关联约束（dik → Xik）
     * 逻辑：若载具k在i点有装载量，则必须访问i
     * 数学表达：∀i∈N，∀k∈K，dik ≤ demand_i * Xik
     */
    private void addLoadVisitLinkConstraints() throws GRBException {
        for (int i : N) {  // 仅围栏有装载量
            Fence fence = fences.get(i - 1);  // i为围栏ID（1-based）
            double demand = fence.getDeliverDemand();

            for (int k : K) {
                String dName = String.format("d_%d_%d", i, k);
                String xName = String.format("X_%d_%d", i, k);
                String constrName = String.format("load_link_i%d_k%d", i, k);

                // 构建右侧：demand_i * Xik
                GRBLinExpr rightExpr = new GRBLinExpr();
                rightExpr.addTerm(demand, varMap.get(xName));

                // 约束：dik ≤ demand_i * Xik（未访问则装载量为0）
                GRBConstr constr = model.addConstr(
                        varMap.get(dName), GRB.LESS_EQUAL,
                        rightExpr,
                        constrName
                );
                constrMap.put(constrName, constr);
            }
        }
    }


    /**
     * 6. 载具容量约束
     * 逻辑：载具k的总装载量 ≤ 其最大容量
     * 数学表达：∀k∈K，∑(i∈N) dik ≤ capacity_k
     */
    private void addVehicleCapacityConstraints() throws GRBException {
        for (int k : K) {
            Vehicle vehicle = vehicles.get(k - 1);  // k为载具ID（1-based）
            double capacity = vehicle.getCapacity();

            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("capacity_k%d", k);

            // 累加所有围栏的装载量
            for (int i : N) {
                String dName = String.format("d_%d_%d", i, k);
                expr.addTerm(1.0, varMap.get(dName));
            }

            // 约束：总装载量 ≤ 载具容量
            GRBConstr constr = model.addConstr(expr, GRB.LESS_EQUAL, capacity, constrName);
            constrMap.put(constrName, constr);
        }
    }


    /**
     * 7. MTZ子回路消除约束（简化版）
     * 逻辑：避免载具路径中出现不包含仓库的子回路
     * 数学表达：∀i,j∈V\M,i≠j，∀k∈K，Ujk ≥ Uik + 1 - (|V|)(1 - Zijk)
     * （Uik为MTZ变量，|V|为总点数）
     */
    private void addMTZConstraints() throws GRBException {
        int totalNodes = V.size();  // 总点数（用于约束系数）

        for (int i : V) {
            if (M.contains(i)) continue;  // 排除仓库点
            for (int j : V) {
                if (M.contains(j) || i == j) continue;  // 排除仓库点和自环
                for (int k : K) {
                    String zName = String.format("Z_%d_%d_%d", i, j, k);
                    String uIName = String.format("U_%d_%d", i, k);
                    String uJName = String.format("U_%d_%d", j, k);
                    String constrName = String.format("mtz_i%d_j%d_k%d", i, j, k);

                    // 构建右侧表达式：Uik + 1 - totalNodes*(1 - Zijk)
                    GRBLinExpr rightExpr = new GRBLinExpr();
                    rightExpr.addTerm(1.0, varMap.get(uIName));  // +Uik
                    rightExpr.addConstant(1.0);  // +1
                    rightExpr.addTerm(totalNodes, varMap.get(zName));  // +totalNodes*Zijk（展开后）
                    rightExpr.addConstant(-totalNodes);  // -totalNodes

                    // 约束：Ujk ≥ 右侧表达式
                    GRBConstr constr = model.addConstr(
                            varMap.get(uJName), GRB.GREATER_EQUAL,
                            rightExpr,
                            constrName
                    );
                    constrMap.put(constrName, constr);
                }
            }
        }
    }
    /**
     * 求解模型并输出结果
     */
    public void solve() throws GRBException {
        // 执行求解
        model.optimize();

        // 输出求解状态
        int status = model.get(GRB.IntAttr.Status);
        System.out.println("求解状态：" + getStatusDescription(status));
        if (status != GRB.Status.OPTIMAL && status != GRB.Status.SUBOPTIMAL) {
            System.out.println("未找到可行解或最优解，终止输出");
            return;
        }

        // 输出目标函数值
        double totalProfit = model.get(GRB.DoubleAttr.ObjVal);
        System.out.printf("最优净收益：%.2f%n", totalProfit);

        // 输出载具使用情况及路径详情
        List<Fence> fenceList = fences.getFenceList();
        for (int k = 0; k < numCarriers; k++) {
            Carrier carrier = carriers.get(k);
            String zVarName = String.format("z_carrier%d", carrier.getIndex());
            GRBVar zVar = varMap.get(zVarName);
            if (zVar.get(GRB.DoubleAttr.X) < 0.5) {
                continue;  // 跳过未使用的载具
            }

            // 载具基本信息
            System.out.printf("\n载具%d（所属仓库%d）：已使用%n", carrier.getIndex(), carrier.getDepot());
            System.out.printf("  载具成本：%.2f%n", carrier.getCarrierValue());

            // 行驶距离
            String dVarName = String.format("d_carrier%d", carrier.getIndex());
            GRBVar dVar = varMap.get(dVarName);
            double distance = dVar.get(GRB.DoubleAttr.X);
            System.out.printf("  行驶距离：%.2f米，运输成本：%.2f%n", distance, distance * Constants.DELIVERCOSTPERMETER);

            // 装载量详情
            double totalLoad = 0;
            double totalLoadProfit = 0;
            System.out.println("  装载详情：");
            for (int i = 0; i < numFences; i++) {
                Fence fence = fenceList.get(i);
                if (fence.getDeliverDemand() <= 1e-6) continue;

                String qVarName = String.format("q_fence%d_carrier%d", fence.getIndex(), carrier.getIndex());
                GRBVar qVar = varMap.get(qVarName);
                double load = qVar.get(GRB.DoubleAttr.X);
                if (load < 1e-6) continue;  // 跳过无装载的围栏

                double unitValue = fence.getOriginalFenceValue() / fence.getDeliverDemand();
                totalLoad += load;
                totalLoadProfit += load * unitValue;
                System.out.printf("    围栏%d：装载量=%.2f（总需求=%.2f），收益=%.2f%n",
                        fence.getIndex(), load, fence.getDeliverDemand(), load * unitValue);
            }
            System.out.printf("  总装载量：%.2f，总装载收益：%.2f%n", totalLoad, totalLoadProfit);
            System.out.printf("  载具净收益：%.2f（装载收益 - 载具成本 - 运输成本）%n",
                    totalLoadProfit - carrier.getCarrierValue() - distance * Constants.DELIVERCOSTPERMETER);

            // 路径详情（可选：输出实际行驶路径）
            printVehiclePath(carrier, fenceList);
        }

        // 释放资源
        model.dispose();
        env.dispose();
    }


    /**
     * 输出载具的行驶路径（辅助方法）
     */
    private void printVehiclePath(Carrier carrier, List<Fence> fenceList) throws GRBException {
        int k = carriers.indexOf(carrier);
        int depotNode = carrier.getDepot();
        System.out.println("  行驶路径：");

        // 从仓库出发，追踪路径
        int currentNode = depotNode;
        boolean hasNext = true;
        while (hasNext) {
            hasNext = false;
            // 查找从当前节点出发的下一个节点
            for (int j = 0; j < totalNodes; j++) {
                if (j == currentNode) continue;
                String xVarName = String.format("x_node%d_node%d_carrier%d", currentNode, j, carrier.getIndex());
                GRBVar xVar = varMap.get(xVarName);
                if (xVar != null && xVar.get(GRB.DoubleAttr.X) > 0.5) {
                    // 输出节点名称（仓库或围栏）
                    String currentNodeName = (currentNode < numDepots)
                            ? "仓库" + currentNode
                            : "围栏" + fenceList.get(currentNode - numDepots).getIndex();
                    String nextNodeName = (j < numDepots)
                            ? "仓库" + j
                            : "围栏" + fenceList.get(j - numDepots).getIndex();
                    System.out.printf("    %s → %s%n", currentNodeName, nextNodeName);

                    currentNode = j;
                    hasNext = true;
                    break;
                }
            }
        }
    }


    /**
     * 将Gurobi求解状态码转换为描述文字
     */
    private String getStatusDescription(int status) {
        return switch (status) {
            case GRB.Status.OPTIMAL -> "最优解（已找到全局最优解）";
            case GRB.Status.SUBOPTIMAL -> "次优解（因时间或精度限制，未找到全局最优）";
            case GRB.Status.INFEASIBLE -> "无可行解（约束冲突）";
            case GRB.Status.UNBOUNDED -> "无界解（目标函数可无限增大）";
            case GRB.Status.TIME_LIMIT -> "时间限制终止";
            default -> "未知状态（状态码：" + status + "）";
        };
    }
}