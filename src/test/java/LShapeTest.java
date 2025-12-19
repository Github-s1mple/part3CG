import Stages.LShapeIteration;
import impl.Scenario;
import impl.Scenarios;
import com.gurobi.gurobi.GRBException;

import java.io.IOException;
import java.util.List;

import static Utils.Initializer.scenarioInitializer;

public class LShapeTest {
    public static void main(String[] args) throws GRBException, IOException {
        List<Scenario> scenarioList = scenarioInitializer();
        Scenarios scenarios = new Scenarios(scenarioList);
        LShapeIteration lShapeIteration = new LShapeIteration(scenarios);
        lShapeIteration.solve();
    }
}
