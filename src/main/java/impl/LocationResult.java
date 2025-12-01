package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Setter
@Getter
public class LocationResult {
    // 选中的候选点（O_i=1）
    public List<Integer> selectedCandidates;
    // 栅格i的基础需求分配结果：gridId -> candidateId
    public Map<Integer, Integer> baseAllocation;
    // 栅格i的额外需求分配结果：gridId -> candidateId
    public Map<Integer, Integer> extraAllocation;
}
