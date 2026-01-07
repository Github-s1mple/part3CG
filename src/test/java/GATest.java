import Stages.GAIteration;
import impl.Scenario;
import impl.Scenarios;
import com.gurobi.gurobi.GRBException;

import java.io.IOException;
import java.util.List;

import static Utils.Initializer.scenarioInitializer;

public class GATest {
    public static void main(String[] args) throws GRBException, IOException {
        List<Scenario> scenarioList = scenarioInitializer();
        Scenarios scenarios = new Scenarios(scenarioList);
        GAIteration GAIteration = new GAIteration(scenarios);
        GAIteration.solve();
    }
}
