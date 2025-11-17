package algo;

import Utils.GurobiUtils;
import com.gurobi.gurobi.*;
import impl.*;
import lombok.Getter;
import lombok.Setter;
import baseinfo.Constants;

import java.util.*;

import java.text.DecimalFormat;

@Setter
@Getter
public class GurobiSolve {
    private final Instance instance;
    private final Fences fences;
    private final Depots depots;
    private final List<Carrier> carrierList;
    private final List<Fence> fakeFences;
    private Map<Integer, Integer> carrierToDepotMap; // key=载具ID，value=所属仓库ID（如-1、-2）
    private Boolean outputFlag = false;
    private double totalTimeSec;
    // 核心数据集合
    private Set<Integer> N;  // 围栏集合（点ID），例如 {1,2,...,F}（F为围栏数量）
    private Set<Integer> M;  // 仓库集合（点ID），例如 {-1, -F}（虚拟点）
    private Set<Integer> K;  // 载具集合（载具ID），例如 {1,2,...,V}（V为载具数量）
    private Set<Integer> V;  // 所有点的集合（V = M ∪ N）
    // Gurobi核心对象
    private GRBEnv env;
    private GRBModel model;
    private GurobiUtils gurobiUtils;
    // 变量缓存：key=变量名（规范命名），value=变量对象
    private HashMap<String, GRBVar> varMap;
    // 约束缓存：后续用于约束管理
    private Map<String, GRBConstr> constrMap = new HashMap<>();
    // 问题维度参数
    private int numDepots;           // 仓库数量
    private int numFences;           // 围栏数量
    private int numCarriers;         // 载具数量
    private List<Order> orderList;   // 最终结果
    // 格式化输出数值（保留2位小数）
    private final DecimalFormat df = new DecimalFormat("0.00");


    public GurobiSolve(Instance instance) throws GRBException {
        this.instance = instance;
        this.fences = instance.getFences();
        this.depots = instance.getDepots();
        this.carrierList = instance.getCarrierList();
        this.fakeFences = new ArrayList<>();

        // 初始化问题维度
        this.numDepots = depots.getDepotList().size();
        this.numFences = fences.getFenceList().size();
        this.numCarriers = carrierList.size();
        this.orderList = new ArrayList<>();

        // 围栏集合N：点ID从1开始（避免与仓库ID冲突）
        N = new HashSet<>();
        for (int i = 0; i < numFences; i++) {
            N.add(i + 1);  // 围栏ID：1,2,...,numFences
        }

        // 仓库集合M：点ID从0开始（虚拟点）
        M = new HashSet<>();
        for (int i = 0; i < numDepots; i++) {
            M.add(-i - 1);  // 仓库ID：-1,...（用负数区分围栏）
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
        int fakeCount = 0;
        for(Depot depot : depots.getDepotList()) {
            Fence fakefence = depot.depot2Fence(fakeCount);
            fakeCount ++;
            fakeFences.add(fakefence);
        }

        // 初始化载具-仓库映射
        carrierToDepotMap = new HashMap<>();
        for (int k : K) {
            Carrier carrier = carrierList.get(k - 1); // 载具ID从1开始
            int depotId = - carrier.getDepot() - 1; // 例如：返回-1（仓库0）或-2（仓库1）
            carrierToDepotMap.put(k, depotId);
        }

        // 初始化Gurobi环境和模型
        this.env = new GRBEnv();
        this.model = new GRBModel(env);

        // 设置模型参数（控制求解行为）
        model.set(GRB.IntParam.OutputFlag, outputFlag ? 1 : 0);  // 日志输出开关
        model.set(GRB.DoubleParam.FeasibilityTol, 1e-5);         // 可行性 tolerance
        model.set(GRB.IntParam.Presolve, 1);                     // 启用预处理
        model.set(GRB.DoubleParam.MIPGap, 0.01);                 // MIP求解间隙（1%）
        this.gurobiUtils = new GurobiUtils(env, model, constrMap);
    }


    /**
     * 定义所有决策变量
     */
    public void defineVariables() throws GRBException {
        // 1. Xik：载具k是否访问点i（0-1变量）
        // 点i ∈ N，载具k ∈ K
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

        // 2. Zijk：载具k访问i后访问j（0-1变量）
        // 点i,j ∈ V，i ≠ j；载具k ∈ K
        for (int i : V) {
            for (int j : V) {
                if (i == j) continue;  // 排除自环
                for (int k : K) {
                    String varName = String.format("Z_%d_%d_%d", i, j, k);
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
        // 点i ∈ N，载具k ∈ K；取值范围通常为[2, |V|]（避免与起点冲突）
        for (int i : N) {
            for (int k : K) {
                String varName = String.format("U_%d_%d", i, k);
                // 取值范围：1到总点数（足够大的整数即可）
                GRBVar var = model.addVar(
                        1.0, N.size(),
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


    /**
     * 设置目标函数（总收益 - 总载具成本 - 总运输成本）
     */
    public void setObjective() throws GRBException {
        GRBLinExpr objExpr = new GRBLinExpr(); // 目标函数表达式

        // 1. 总收益：∑(k∈K) ∑(i∈N) (dik × 围栏i单位价值)
        for (int i : N) {
            Fence fence = fences.getFenceList().get(i - 1);
            double unitValue = fence.getOriginalFenceValue(); // 围栏单位价值

            for (int k : K) {
                String dName = String.format("d_%d_%d", i, k);
                GRBVar dVar = varMap.get(dName);
                objExpr.addTerm(unitValue, dVar); // 装载量×单位价值，累加收益
            }
        }

        // 2. 总运输成本：∑(k∈K) ∑(i∈V) ∑(j∈V) (Zijk × 路径i→j距离 × 单位距离成本) → 减成本，系数为负
        double unitTransCost = Constants.DELIVER_COST_PER_METER; // 单位距离运输成本（元/米）
        List<HashMap<Integer, Double>> depotToFenceDist = instance.getDepotDistanceMatrix(); // 仓库-围栏距离
        List<List<Double>> fenceToFenceDist = instance.getDistanceMatrix(); // 围栏-围栏距离

        for (int k : K) {
            for (int i : V) {
                for (int j : V) {
                    if (i == j) continue;
                    String zName = String.format("Z_%d_%d_%d", i, j, k); // 注意Z变量名格式：Z_起点_终点_载具
                    GRBVar zVar = varMap.get(zName);
                    if (zVar == null) continue;

                    // 计算路径i→j的距离（米）
                    double dist = 0.0;
                    try {
                        if (M.contains(i) && N.contains(j)) {
                            // 仓库i → 围栏j
                            int depotIdx = -i - 1;
                            int fenceIdx = j - 1;
                            HashMap<Integer, Double> distMap = depotToFenceDist.get(depotIdx);
                            dist = distMap.get(fenceIdx) * 1000; // 千米转米
                        } else if (N.contains(i) && M.contains(j)) {
                            // 围栏i → 仓库j
                            int fenceIdx = i - 1;
                            int depotIdx = -j - 1;
                            HashMap<Integer, Double> distMap = depotToFenceDist.get(depotIdx);
                            dist = distMap.get(fenceIdx) * 1000;
                        } else if (N.contains(i) && N.contains(j)) {
                            // 围栏i → 围栏j
                            int fenceI = i - 1;
                            int fenceJ = j - 1;
                            dist = fenceToFenceDist.get(fenceI).get(fenceJ) * 1000;
                        }
                    } catch (Exception e) {
                        System.err.printf("路径%d→%d（载具%d）距离计算失败，按0处理：%s%n", i, j, k, e.getMessage());
                        dist = 0.0;
                    }

                    // 运输成本 = 距离 × 单位成本，目标函数中“减成本”，系数为负
                    double transCostCoeff = -dist * unitTransCost;
                    objExpr.addTerm(transCostCoeff, zVar);
                }
            }
        }

        // 设置目标函数：最大化（GRB.MAXIMIZE）
        model.setObjective(objExpr, GRB.MAXIMIZE);
        model.update();
        System.out.println("目标函数设置完成");
    }


    /**
     * 添加所有核心约束
     */
    public void addCoreConstraints() throws GRBException {
        // 1. 载具从仓库出发约束
        addDepartFromDepotConstraints();

        // 2. 载具返回仓库约束
        addReturnAndStartDepotConstraints();

        // 3. 路径连续性约束（流量守恒）
        addFlowConservationConstraints();

        // 4. 访问与路径关联约束（Zijk → Xik）
        addVisitPathLinkConstraints();

        // 5. 装载量与访问关联约束（dik → Xik）
        addLoadVisitLinkConstraints();

        // 6. 载具容量约束
        addVehicleCapacityConstraints();

        // 7. 围栏需求总数约束
        addFenceDemandConstraints();

        // 8. MTZ子回路消除约束
        addMTZConstraints();

        // 9. 仓库不相连强约束
        addForbidDepotToDepotConstraints();

        // 10. 围栏单车单次约束
        addOneCarrierOnceConstraints();

        // 11. 单载具单仓库强约束
        addDepotOneCarrierConstraints();

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
        for (int k : K) {
            int depotId = carrierToDepotMap.get(k); // 载具k所属的仓库ID（如-1）
            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("depart_k%d", k);

            // 只允许从所属仓库出发，且目标只能是围栏（排除其他仓库）
            for (int j : N) { // j必须是围栏（N），不能是仓库（M）
                String zName = String.format("Z_%d_%d_%d", depotId, j, k);
                GRBVar zVar = varMap.get(zName);
                if (zVar != null) {
                    expr.addTerm(1.0, zVar);
                }
            }

            // 约束：载具k必须从所属仓库出发到某个围栏（路径数=1）
            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
    }


    /**
     * 2. 载具返回仓库约束
     * 逻辑：每个使用的载具k必须返回某个仓库m
     * 数学表达：∀k∈K，∑(m∈M) ∑(i∈V\m) Zimk = 1
     */
    private void addReturnAndStartDepotConstraints() throws GRBException {
        for (int k : K) {
            int depotId = carrierToDepotMap.get(k); // 载具所属仓库ID
            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("return_k%d", k);

            // 只允许从围栏返回【所属仓库】（i∈N，j=depotId）
            for (int i : N) {
                String zName = String.format("Z_%d_%d_%d", i, depotId, k);
                GRBVar zVar = varMap.get(zName);
                if (zVar != null) {
                    expr.addTerm(1.0, zVar);
                }
            }
            // 约束：必须返回所属仓库（路径数=1）
            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
    }


    /**
     * 3. 路径连续性约束（流量守恒）
     * 逻辑：对于围栏点i，载具k进入的路径数 = 离开的路径数
     * 数学表达：∀i∈N，∀k∈K，∑(j∈V\i) Zijk = ∑(j∈V\i) Zjik
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
     * 4. 访问与路径关联约束（∑j Zjik = Xik）
     * 逻辑：载具k访问i 当且仅当 存在j使得载具k从j到i
     */
    private void addVisitPathLinkConstraints() throws GRBException {
        for (int i : N) {  // 围栏节点（只有围栏有Xik变量）
            for (int k : K) {  // 载具
                String constrName = String.format("link_Z_X_%d_%d", i, k);
                String xIName = String.format("X_%d_%d", i, k);
                GRBVar xVar = varMap.get(xIName);

                // 判空：若X变量未定义，跳过（避免null）
                if (xVar == null) {
                    System.err.printf("警告：访问变量X_%d_%d未定义，跳过约束%n", i, k);
                    continue;
                }

                GRBLinExpr expr = new GRBLinExpr();
                for (int j : V) {  // 所有可能的起点（仓库/围栏）
                    if (j == i) continue;  // 排除自环
                    String zName = String.format("Z_%d_%d_%d", j, i, k);
                    GRBVar zVar = varMap.get(zName);

                    // 判空：若Z变量未定义，跳过（避免null）
                    if (zVar == null) {
                        // System.err.printf("警告：路径变量Z_%d_%d_%d未定义，跳过该term%n", j, i, k);
                        continue;
                    }
                    expr.addTerm(1.0, zVar);
                }

                // 约束：∑j Zjik = Xik
                expr.addTerm(-1.0, xVar);
                GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 0.0, constrName);
                constrMap.put(constrName, constr);
            }
        }
    }


    /**
     * 5. 装载量与访问关联约束（双向）
     */
    private void addLoadVisitLinkConstraints() throws GRBException {
        double eps = 1e-6; // 极小值，避免数值误差
        for (int i : N) {
            Fence fence = fences.getFenceList().get(i - 1);
            double demand = fence.getDeliverDemand();

            for (int k : K) {
                String dName = String.format("d_%d_%d", i, k);
                String xName = String.format("X_%d_%d", i, k);
                GRBVar dVar = varMap.get(dName);
                GRBVar xVar = varMap.get(xName);
                String constrName = String.format("load_link_i%d_k%d", i, k);

                // 约束1：装载→访问（原有）：dik ≤ demand * Xik
                GRBLinExpr right1 = new GRBLinExpr();
                right1.addTerm(demand, xVar);
                model.addConstr(dVar, GRB.LESS_EQUAL, right1, constrName + "_load2visit");

                // 约束2：访问→装载：Xik ≤ dik / eps（访问则必须有装载量）
                GRBLinExpr left2 = new GRBLinExpr();
                left2.addTerm(1.0, xVar);
                GRBLinExpr right2 = new GRBLinExpr();
                right2.addTerm(1/eps, dVar);
                model.addConstr(left2, GRB.LESS_EQUAL, right2, constrName + "_visit2load");

                // 约束3：访问次数≤1（原有Xik是二进制变量，此约束可省略，但确保定义正确）
                model.addConstr(xVar, GRB.LESS_EQUAL, 1.0, constrName + "_visit_once");
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
            Carrier carrier = carrierList.get(k - 1);  // k为载具ID（1-based）
            double capacity = carrier.getCapacity();

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
     * 7. 围栏需求量约束
     * 逻辑：每个载具的总装载量 ≤ 围栏的最大容量
     * 数学表达：∀i∈N，∑(k∈K) dik ≤ D_i
     */
    private void addFenceDemandConstraints() throws GRBException {
        for (int i : N) {
            Fence fence = fences.getFenceList().get(i - 1);  // i为围栏ID（1-based）
            double demand = fence.getDeliverDemand();

            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("demand_i%d", i);

            // 累加所有围栏的装载量
            for (int k : K) {
                String dName = String.format("d_%d_%d", i, k);
                expr.addTerm(1.0, varMap.get(dName));
            }

            // 约束：总装载量 ≤ 载具容量
            GRBConstr constr = model.addConstr(expr, GRB.LESS_EQUAL, demand, constrName);
            constrMap.put(constrName, constr);
        }
    }


    /**
     * 8. MTZ子回路消除约束（仅对围栏生效）
     */
    private void addMTZConstraints() throws GRBException {
        int totalNodes = V.size();
        for (int i : N) { // 只遍历围栏（i是围栏）
            for (int j : N) { // 只遍历围栏（j是围栏）
                if (i == j) continue;
                for (int k : K) {
                    String zName = String.format("Z_%d_%d_%d", i, j, k);
                    String uIName = String.format("U_%d_%d", i, k);
                    String uJName = String.format("U_%d_%d", j, k);
                    GRBVar zVar = varMap.get(zName);
                    GRBVar uIVar = varMap.get(uIName);
                    GRBVar uJVar = varMap.get(uJName);
                    if (zVar == null || uIVar == null || uJVar == null) continue;

                    GRBLinExpr right = new GRBLinExpr();
                    right.addTerm(1.0, uIVar);
                    right.addConstant(1.0 - totalNodes);
                    right.addTerm(totalNodes, zVar);
                    model.addConstr(uJVar, GRB.GREATER_EQUAL, right, "mtz_i%d_j%d_k%d".formatted(i,j,k));
                }
            }
        }
    }


    /**
     * 9. 仓库间路径禁止约束
     */
    private void addForbidDepotToDepotConstraints() throws GRBException {
        for (int m1 : M) { // 仓库m1
            for (int m2 : M) { // 仓库m2
                if (m1 == m2) continue;
                for (int k : K) { // 所有载具
                    String zName = String.format("Z_%d_%d_%d", m1, m2, k);
                    GRBVar zVar = varMap.get(zName);
                    if (zVar != null) {
                        // 约束：仓库m1到m2的路径变量必须为0
                        String constrName = String.format("forbid_depot_%d_to_%d_k%d", m1, m2, k);
                        GRBConstr constr = model.addConstr(zVar, GRB.EQUAL, 0.0, constrName);
                        constrMap.put(constrName, constr);
                    }
                }
            }
        }
    }


    /**
     * 10. 围栏单车单次约束：同一载具最多访问同一围栏1次
     */
    private void addOneCarrierOnceConstraints() throws GRBException {
        for (int k : K) { // 遍历载具
            for (int i : N) { // 遍历围栏（仓库无此约束）
                GRBLinExpr expr = new GRBLinExpr();
                String constrName = String.format("once_k%d_i%d", k, i);
                // 累加“所有到达围栏i的路径”（j→i）
                for (int j : V) {
                    if (j == i) continue;
                    String zName = String.format("Z_%d_%d_%d", j, i, k);
                    GRBVar zVar = varMap.get(zName);
                    if (zVar != null) {
                        expr.addTerm(1.0, zVar);
                    }
                }
                // 约束：到达围栏i的路径数≤1（最多访问1次）
                GRBConstr constr = model.addConstr(expr, GRB.LESS_EQUAL, 1.0, constrName);
                constrMap.put(constrName, constr);
            }
        }
    }


    /**
     * 11. 载具从仓库出发约束
     * 逻辑：每个仓库只许有一辆载具
     * 数学表达：∀m∈M，∑(k∈K) ∑(j∈N) Zmjk = 1
     */
    private void addDepotOneCarrierConstraints() throws GRBException {
        for (int i : M) {
            GRBLinExpr expr = new GRBLinExpr();
            String constrName = String.format("depotOneCarrier_i%d", i);

            for (int j : N) {
                for (int k : K) {
                    String zName = String.format("Z_%d_%d_%d", i, j, k);
                    GRBVar zVar = varMap.get(zName);
                    if (zVar != null) {
                        expr.addTerm(1.0, zVar);
                    }
                }
            }
            GRBConstr constr = model.addConstr(expr, GRB.EQUAL, 1.0, constrName);
            constrMap.put(constrName, constr);
        }
    }


    /**
     * 求解模型并输出完整结果（包含冲突分析）
     */
    public List<Order> solve() throws GRBException {
        // 记录求解开始时间（毫秒）
        long startTime = System.currentTimeMillis();
        System.out.println("开始求解......");
        try {
            // 执行求解
            model.optimize();

            // 输出求解状态
            int status = model.get(GRB.IntAttr.Status);
            System.out.println("求解成功！");
            System.out.println("求解状态：" + GurobiUtils.getStatusDescription(status));

            // 无可行解时，调用冲突约束分析
            if (status == GRB.Status.INFEASIBLE) {
                gurobiUtils.printConflictConstraints();
                System.out.println("未找到可行解，已输出冲突约束分析");
                return null;
            }

            // 非可行/最优状态，终止输出
            if (status != GRB.Status.OPTIMAL && status != GRB.Status.SUBOPTIMAL) {
                System.out.println("未找到可行解或最优解，终止输出");
                return null;
            }

            // 正常求解后的输出逻辑
            double totalProfit = model.get(GRB.DoubleAttr.ObjVal);
            System.out.println("【全局最优结果】");
            System.out.println("最优净收益：" + df.format(totalProfit));

            if(outputFlag){
                outputAllDecisionVariables();
                outputVehicleBusinessDetails();
            }else{
                produceVehicleBusinessDetails();
            }

            return orderList;
        } finally {
            // 记录求解结束时间（毫秒），计算总耗时（转换为秒，保留2位小数）
            long endTime = System.currentTimeMillis();
            totalTimeSec = (endTime - startTime) / 1000.0;
            System.out.printf("【求解耗时统计】%n");
            System.out.printf("完整求解过程总耗时：%s 秒%n", df.format(totalTimeSec));

            // 释放模型和环境资源
            model.dispose();
            env.dispose();
        }
    }


    /**
     * 输出所有决策变量的取值
     */
    private void outputAllDecisionVariables() throws GRBException {
        System.out.println("\n【所有决策变量取值】");
        System.out.println("注：值≈1表示选中（二进制变量），保留2位小数");
        System.out.println("========================================");

        // 1. 输出Xik：载具k是否访问点i
        System.out.println("\n1. 访问变量 Xik（X_围栏ID_载具ID = 取值）");
        System.out.println("----------------------------------------");
        for (int i : N) {
            for (int k : K) {
                String varName = String.format("X_%d_%d", i, k);
                GRBVar var = varMap.get(varName);
                if (var != null) {
                    double val = var.get(GRB.DoubleAttr.X);
                    // 只输出取值非0的变量（减少冗余）
                    if (val > 1e-6) {
                        System.out.printf("X_%d_%d = %s%n", i, k, df.format(val));
                    }
                }
            }
        }

        // 2. 输出Zijk：载具k访问i后访问j
        System.out.println("\n2. 路径变量 Zijk（Z_起点ID_终点ID_载具ID = 取值）");
        System.out.println("----------------------------------------");
        for (int i : V) {
            for (int j : V) {
                if (i == j) continue;
                for (int k : K) {
                    String varName = String.format("Z_%d_%d_%d", i, j, k);
                    GRBVar var = varMap.get(varName);
                    if (var != null) {
                        double val = var.get(GRB.DoubleAttr.X);
                        if (val > 1e-6) { // 只输出选中的路径
                            String startNode = M.contains(i) ? "仓库" + (-i - 1) : "围栏" + i;
                            String endNode = M.contains(j) ? "仓库" + (-j - 1) : "围栏" + j;
                            System.out.printf("Z_%d_%d_%d = %s （%s → %s）%n",
                                    i, j, k, df.format(val), startNode, endNode);
                        }
                    }
                }
            }
        }

        // 3. 输出dik：载具k在点i的装载量
        System.out.println("\n3. 装载量变量 dik（d_围栏ID_载具ID = 装载量）");
        System.out.println("----------------------------------------");
        for (int i : N) {
            for (int k : K) {
                String varName = String.format("d_%d_%d", i, k);
                GRBVar var = varMap.get(varName);
                if (var != null) {
                    double val = var.get(GRB.DoubleAttr.X);
                    if (val > 1e-6) { // 只输出有装载量的记录
                        System.out.printf("d_%d_%d = %s%n", i, k, df.format(val));
                    }
                }
            }
        }

//        // 4. 输出Uik：MTZ约束辅助变量
//        System.out.println("\n4. MTZ辅助变量 Uik（U_围栏ID_载具ID = 取值）");
//        System.out.println("----------------------------------------");
//        for (int i : N) {
//            for (int k : K) {
//                String varName = String.format("U_%d_%d", i, k);
//                GRBVar var = varMap.get(varName);
//                if (var != null) {
//                    double val = var.get(GRB.DoubleAttr.X);
//                    if (val > 1e-6) { // 只输出非0值
//                        System.out.printf("U_%d_%d = %s%n", i, k, df.format(val));
//                    }
//                }
//            }
//        }
        System.out.println("========================================");
    }


    /**
     * 输出载具业务详情（路径、装载量、收益等结构化信息）
     */
    private void produceVehicleBusinessDetails() throws GRBException {
        List<Fence> fenceList = fences.getFenceList();

        for (int k : K) { // 按载具ID遍历
            Order order = new Order();
            Carrier carrier = carrierList.get(k - 1); // 载具ID从1开始，列表索引从0开始
            order.setFences(fences);
            order.setOrderId(k);
            order.setCarrier(carrier);
            order.setDepot(carrier.getDepot());

            double totalDistance = calculateVehicleTotalDistance(k);
            double transportCost = totalDistance * Constants.DELIVER_COST_PER_METER;
            order.setCarrierCost(transportCost);
            order.setDistance(totalDistance);


            double totalLoad = 0;
            double totalLoadProfit = 0;

            int fenceCount = 0;
            for (int i : N) {
                Fence fence = fenceList.get(i - 1);
                String dVarName = String.format("d_%d_%d", i, k);
                GRBVar dVar = varMap.get(dVarName);
                if (dVar != null) {
                    double load = dVar.get(GRB.DoubleAttr.X);
                    if (load > 1e-6) {
                        fenceCount += 1;
                        double profit = load * fence.getOriginalFenceValue();
                        totalLoad += load;
                        totalLoadProfit += profit;
                        order.addLoad(i, load);
                    }
                }
            }
            order.setFenceNumber(fenceCount);
            order.setDispatchNum(totalLoad);

            double vehicleNetProfit = totalLoadProfit - transportCost;
            order.setOriginalPrice(vehicleNetProfit);
            List<String> path = getVehiclePathInOrder(k, order);
            this.orderList.add(order);
        }
    }


    /**
     * 输出载具业务详情（路径、装载量、收益等结构化信息）
     */
    private void outputVehicleBusinessDetails() throws GRBException {
        List<Fence> fenceList = fences.getFenceList();
        System.out.println("\n【载具业务详情】");
        System.out.println("========================================");

        for (int k : K) { // 按载具ID遍历
            Order order = new Order();
            Carrier carrier = carrierList.get(k - 1); // 载具ID从1开始，列表索引从0开始
            System.out.printf("\n载具%d 详情：", k);
            System.out.printf("所属仓库=%d，载具容量=%.2f",
                    carrier.getDepot(), carrier.getCapacity());

            order.setFences(fences);
            order.setOrderId(k);
            order.setCarrier(carrier);
            order.setDepot(carrier.getDepot());

            System.out.println("----------------------------------------");

            // ① 载具k的总行驶距离（需计算：所有选中路径的距离之和）
            double totalDistance = calculateVehicleTotalDistance(k);
            double transportCost = totalDistance * Constants.DELIVER_COST_PER_METER;
            System.out.printf("行驶距离：%s米，运输成本：%s%n",
                    df.format(totalDistance), df.format(transportCost));
            order.setCarrierCost(transportCost);
            order.setDistance(totalDistance);

            // ② 载具k的装载详情
            double totalLoad = 0;
            double totalLoadProfit = 0;
            System.out.println("装载详情：");
            int fenceCount = 0;
            for (int i : N) {
                Fence fence = fenceList.get(i - 1);
                String dVarName = String.format("d_%d_%d", i, k);
                GRBVar dVar = varMap.get(dVarName);
                if (dVar != null) {
                    double load = dVar.get(GRB.DoubleAttr.X);
                    if (load > 1e-6) {
                        fenceCount += 1;
                        double profit = load * fence.getOriginalFenceValue();
                        totalLoad += load;
                        totalLoadProfit += profit;
                        System.out.printf("  围栏%d：装载量=%s（需求=%s），收益=%s%n",
                                i - 1, df.format(load), df.format(fence.getDeliverDemand()), df.format(profit));
                        order.addLoad(i, load);
                    }
                }
            }
            order.setFenceNumber(fenceCount);
            order.setDispatchNum(totalLoad);
            System.out.printf("总装载量：%s，总装载收益：%s%n",
                    df.format(totalLoad), df.format(totalLoadProfit));

            // ③ 载具k的净收益
            double vehicleNetProfit = totalLoadProfit - transportCost;
            System.out.printf("载具净收益：%s%n", df.format(vehicleNetProfit));
            order.setOriginalPrice(vehicleNetProfit);

            // ④ 载具k的行驶路径（按顺序展示）
            System.out.println("行驶路径（按顺序）：");
            List<String> path = getVehiclePathInOrder(k, order);
            if (!path.isEmpty()) {
                System.out.println("  " + String.join(" → ", path));
            } else {
                System.out.println("  无有效路径");
            }
            this.orderList.add(order);
        }
        System.out.println("========================================");
    }


    /**
     * 辅助方法：计算载具k的总行驶距离（所有选中路径的距离之和）
     */
    private double calculateVehicleTotalDistance(int k) throws GRBException {
        double totalDist = 0;
        List<HashMap<Integer, Double>> depotToFenceDist = instance.getDepotDistanceMatrix();
        List<List<Double>> fenceToFenceDist = instance.getDistanceMatrix();

        if (depotToFenceDist == null || depotToFenceDist.isEmpty()) {
            System.err.println("警告：depotToFenceDist为空，仓库到围栏的距离按0处理");
            return 0;
        }
        if (fenceToFenceDist == null || fenceToFenceDist.isEmpty()) {
            System.err.println("警告：fenceToFenceDist为空，围栏到围栏的距离按0处理");
        }

        for (int i : V) {
            for (int j : V) {
                if (i == j) continue;
                String zVarName = String.format("Z_%d_%d_%d", i, j, k);
                GRBVar zVar = varMap.get(zVarName);
                if (zVar == null || zVar.get(GRB.DoubleAttr.X) <= 0.5) {
                    continue;
                }

                double dist = 0; // 距离（米）
                try {
                    if (M.contains(i) && N.contains(j)) {
                        // 1. 仓库i → 围栏j：
                        int depotIdx = -i - 1;
                        int fenceIdx = j - 1;

                        // 校验仓库索引
                        if (depotIdx < 0 || depotIdx >= numDepots) {
                            throw new IndexOutOfBoundsException("仓库索引无效：" + depotIdx + "（仓库数：" + numDepots + "）");
                        }
                        // 校验仓库对应的距离数组是否存在
                        if (depotIdx >= depotToFenceDist.size()) {
                            throw new IndexOutOfBoundsException("仓库" + depotIdx + "在depotToFenceDist中无数据");
                        }
                        HashMap<Integer, Double> distArray = depotToFenceDist.get(depotIdx);

                        // 校验围栏索引
                        if (fenceIdx < 0 || fenceIdx >= numFences) {
                            throw new IndexOutOfBoundsException("围栏索引无效：" + fenceIdx + "（围栏数：" + numFences + "）");
                        }
                        // 校验围栏在距离数组中的索引
                        if (fenceIdx >= distArray.size()) {
                            throw new IndexOutOfBoundsException("仓库" + depotIdx + "到围栏" + fenceIdx + "的距离不存在（数组长度：" + distArray.size() + "）");
                        }

                        // 距离（千米转米）
                        dist = distArray.get(fenceIdx) * 1000;

                    } else if (N.contains(i) && M.contains(j)) {
                        // 2. 围栏i → 仓库j：
                        int fenceIdx = i - 1;
                        int depotIdx = -j - 1;

                        // 校验仓库索引
                        if (depotIdx < 0 || depotIdx >= numDepots) {
                            throw new IndexOutOfBoundsException("仓库索引无效：" + depotIdx);
                        }
                        if (depotIdx >= depotToFenceDist.size()) {
                            throw new IndexOutOfBoundsException("仓库" + depotIdx + "在depotToFenceDist中无数据");
                        }
                        HashMap<Integer, Double> distArray = depotToFenceDist.get(depotIdx);

                        // 校验围栏索引
                        if (fenceIdx < 0 || fenceIdx >= numFences || fenceIdx >= distArray.size()) {
                            throw new IndexOutOfBoundsException("围栏索引无效：" + fenceIdx + "（数组长度：" + distArray.size() + "）");
                        }

                        // 距离（千米转米）
                        dist = distArray.get(fenceIdx) * 1000;

                    } else if (N.contains(i) && N.contains(j)) {
                        // 3. 围栏i → 围栏j：
                        int fenceI = i - 1;
                        int fenceJ = j - 1;

                        if (fenceI < 0 || fenceI >= numFences || fenceJ < 0 || fenceJ >= numFences) {
                            throw new IndexOutOfBoundsException("围栏索引无效：" + fenceI + "→" + fenceJ);
                        }
                        if (fenceI >= fenceToFenceDist.size()) {
                            throw new IndexOutOfBoundsException("围栏" + fenceI + "在fenceToFenceDist中无数据");
                        }
                        List<Double> distList = fenceToFenceDist.get(fenceI);
                        if (fenceJ >= distList.size()) {
                            throw new IndexOutOfBoundsException("围栏" + fenceI + "到" + fenceJ + "的距离不存在");
                        }

                        dist = distList.get(fenceJ) * 1000; // 千米转米
                    }

                    totalDist += dist;
                } catch (IndexOutOfBoundsException e) {
                    System.err.printf("路径 %d→%d（载具%d）距离计算失败：%s%n", i, j, k, e.getMessage());
                }
            }
        }
        return totalDist;
    }


    /**
     * 辅助方法：获取载具k的行驶路径（按顺序排列）
     */
    private List<String> getVehiclePathInOrder(int k, Order order) throws GRBException {
        List<String> path = new ArrayList<>();
        Map<Integer, Integer> nextNodeMap = new HashMap<>(); // key = 当前节点，value = 下一个节点
        Set<Integer> visitedNodes = new HashSet<>();

        // 第一步：构建路径映射（当前节点→下一个节点）
        for (int i : V) {
            for (int j : V) {
                if (i == j) continue;
                String zVarName = String.format("Z_%d_%d_%d", i, j, k);
                GRBVar zVar = varMap.get(zVarName);
                if (zVar != null && zVar.get(GRB.DoubleAttr.X) > 0.5) {
                    nextNodeMap.put(i, j);
                    visitedNodes.add(i);
                    visitedNodes.add(j);
                }
            }
        }

        if (nextNodeMap.isEmpty()) return path;

        // 第二步：找到起点（仓库节点）
        Integer currentNode = null;
        for (int node : visitedNodes) {
            if (M.contains(node) && nextNodeMap.containsKey(node)) {
                currentNode = node;
                break;
            }
        }

        // 第三步：按映射遍历路径
        if (currentNode != null) {
            while (currentNode != null) {
                String nodeName;
                if(M.contains(currentNode)){
                    nodeName = "仓库" + (-currentNode - 1);
                }else {
                    nodeName = "围栏" + (currentNode - 1);
                    order.addFence(currentNode - 1);
                }

                path.add(nodeName);
                // 获取下一个节点
                currentNode = nextNodeMap.get(currentNode);
                // 防止循环（理论上MTZ约束已避免）
                if (path.size() > visitedNodes.size()) break;
            }
        }
        return path;
    }
}