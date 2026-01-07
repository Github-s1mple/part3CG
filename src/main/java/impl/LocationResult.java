package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static baseinfo.MapDistance.calculateSphericalDistance;

@Setter
@Getter
public class LocationResult {
    // 选中的候选点（O_i=1）
    public List<Integer> selectedCandidates;
    // 栅格i的需求分配结果：fenceId -> candidateId
    public Map<Integer, Integer> extraAllocation;

    public LocationResult() {
        this.selectedCandidates = null;
        this.extraAllocation = null;
    }


    public void generateInitial(Map<Integer, Integer> initialOj, List<Fence> fenceList,
                                List<Candidate> candidateList) {
        // ========== 步骤1：筛选选中的候选点（initialOj值为1的candidateId） ==========
        for (Map.Entry<Integer, Integer> entry : initialOj.entrySet()) {
            int candidateId = entry.getKey();
            int isSelected = entry.getValue();
            if (isSelected == 1) {
                this.selectedCandidates.add(candidateId);
            }
        }

        // 边界校验：无选中候选点时直接返回
        if (this.selectedCandidates.isEmpty()) {
            System.err.println("警告：initialOj中无选中的候选点！");
            return;
        }

        // ========== 步骤2：过滤出选中的候选点（Candidate对象） ==========
        List<Candidate> selectedCandidates = new ArrayList<>();
        for (Candidate candidate : candidateList) {
            if (this.selectedCandidates.contains(candidate.getIndex())) {
                selectedCandidates.add(candidate);
            }
        }

        // ========== 步骤3：为每个栅格分配最近的选中候选点 ==========
        for (Fence fence : fenceList) {
            int fenceId = fence.getIndex();
            double fenceLat = fence.getLat();
            double fenceLon = fence.getLon();

            // 初始化最小距离和对应候选点ID
            double minDistance = Double.MAX_VALUE;
            int nearestCandidateId = -1;

            // 遍历所有选中的候选点，计算距离并找最近的
            for (Candidate selectedCandidate : selectedCandidates) {
                double candidateLat = selectedCandidate.getLatitude();
                double candidateLon = selectedCandidate.getLongitude();
                // 计算球面距离
                double distance = calculateSphericalDistance(candidateLat, candidateLon, fenceLat, fenceLon);

                // 更新最小距离和最近候选点
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestCandidateId = selectedCandidate.getIndex();
                }
            }

            // 边界校验：找到有效候选点才分配
            if (nearestCandidateId != -1) {
                this.extraAllocation.put(fenceId, nearestCandidateId);
            } else {
                System.err.println("警告：栅格" + fenceId + "未找到可分配的候选点！");
            }
        }
    }
}
