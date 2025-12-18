package Stages;

import impl.Instance;
import impl.Order;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class Scenarios {
    private List<Scenario> scenarioList;
    private int scenarioNumber;

    //TODO
    public Scenarios() {
        this.scenarioList = null;
        this.scenarioNumber = scenarioList.size();
    }

    public double getProbability(Instance instance) {
        Integer targetId = instance.getScenario().getId();
        for (Scenario scenario : scenarioList) {
            if (scenario.getId().equals(targetId)) return scenario.getProbability();
        }
        return -1;
    }
}
