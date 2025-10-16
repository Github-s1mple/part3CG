package algo;

import impl.Carrier;
import impl.Fence;
import impl.Fences;
import impl.Instance;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import baseinfo.Constants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
public class OrderColumnGeneration {
    final Instance instance; // 算例数据
    final Fences fences;
    public Boolean outputFlag = false; // 是否输出过程信息

    @Setter
    private Integer iterationTimeLimit = Integer.MAX_VALUE; // 迭代环节时间限制
    private Integer startTime; // 算法开始时间

    @Getter
    private Integer iterationCnt; // 记录迭代次数

    final HashMap<String, Double> dualsOfRLMP; // 主问题对偶值
    final MPSolver RLMPSolver; // 松弛主问题求解器
    final BidLabeling bidLabeling; // 双向标号法
    HashMap<String, Variable> RLMPVariables;
    final AlgoParam algoParam;
    public OrderColumnGeneration(Instance instance){
        this.instance = instance;
        this.algoParam = instance.getAlgoParam();
        this.fences = instance.getFences();
        this.dualsOfRLMP = new HashMap<>();
        this.RLMPVariables = new HashMap<>();
        this.bidLabeling = new BidLabeling(this.instance);
        this.bidLabeling.setOutputFlag(false);
        this.bidLabeling.setOrderLimit(algoParam.getIterationColumnNum());
        this.RLMPSolver = Utils.buildSolver(NameConstants.GLOP, 1, fences, instance.getCarrierList());
        this.RLMPSolver.setSolverSpecificParametersAsString("primal_feasibility_tolerance:1e-5");
        this.RLMPSolver.setSolverSpecificParametersAsString("change_status_to_imprecise:false");
        // 初始化对偶值
        for(Fence fence: fences.getFenceList()){
            this.dualsOfRLMP.put(fence.getConstName(), 0.0);
        }

        for(Carrier carrier:instance.getCarrierList()){
            this.dualsOfRLMP.put(carrier.getConstName(), 0.0);
        }
    }

    public List<Order> solve() {
        this.startTime = CommonUtils.currentTimeInSecond();
        this.iterationCnt = 0;
        int lastIterationTime = 0;
        //这里额外减去一个lastIterationTime，是为了给需要跑的这个iteration留下buffer
        List<Order> orders = new ArrayList<>();
        while (getIterationTimeLimitLeft() > lastIterationTime && orders.size() <= algoParam.getMaxRMLPColumns()) {
            int sTime = CommonUtils.currentTimeInSecond();
            // 根据对偶值生成列
            List<Order> newOrders = this.generateOrders();
            orders.addAll(newOrders);
            if (this.outputFlag) {
                this.displayIterationInformation();
            }
            this.iterationCnt += 1;
            // 若找不到更优列则退出
            if (newOrders.size() == 0) {
                break;
            }
            // 找到更优列则加入RLMP继续迭代
            this.addRLMPColumns(newOrders);
            // 求解RLMP更新对偶值
            this.solveRLMPAndUpdateDuals();

            lastIterationTime = CommonUtils.currentTimeInSecond() - sTime;
            if(getIterationTimeLimitLeft() < lastIterationTime){
                log.info("超时退出迭代");
            }
            if(orders.size() > algoParam.getMaxRMLPColumns()){
                log.info("超过最大列数退出迭代");
            }
        }
        return orders;
    }

    private List<Order> generateOrders() {
        // 设置时间约束、工单数约束
        this.bidLabeling.setTimeLimit(this.getIterationTimeLimitLeft());
        boolean streetSweep = false;
        return this.bidLabeling.solve(dualsOfRLMP, streetSweep);
    }

    private List<Order> longDistanceGenerateOrders() {
        // 设置时间约束、工单数约束
        this.bidLabeling.setTimeLimit(this.getIterationTimeLimitLeft());
        boolean isLongDistance = true;
        return this.bidLabeling.solve(dualsOfRLMP, isLongDistance);
    }

    private void displayIterationInformation() {
        System.out.println("CG Iteration " + this.iterationCnt + ": RLMPObj = " + this.RLMPSolver.objective().value()
                + ", ColumnNum = " + this.RLMPVariables.size() + ", bestSPObj = " + this.bidLabeling.getBestObj()
                + ", TimeCost = " + this.getTimeSinceStartTime() + "s"
        );
    }

    private int getIterationTimeLimitLeft() {
        return this.iterationTimeLimit - this.getTimeSinceStartTime();
    }
    private int getTimeSinceStartTime(){
        return CommonUtils.currentTimeInSecond() - this.startTime;
    }

    private void addRLMPColumns(List<Order> newOrders) {
        Utils.addColumns(newOrders, this.RLMPSolver, RLMPVariables, false, fences);}

    private void solveRLMPAndUpdateDuals() {
        int timeLimit = this.getIterationTimeLimitLeft();
        this.RLMPSolver.setTimeLimit((long) timeLimit * 1000);

        MPSolver.ResultStatus status = this.RLMPSolver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE && outputFlag) {
//            System.out.println("The problem does not have an optimal solution!");
            log.info("The problem does not have an optimal solution!");

            return;
        }

        for(MPConstraint constraint:this.RLMPSolver.constraints()){
            this.dualsOfRLMP.put(constraint.name(), constraint.dualValue());
        }
    }
}