import Stages.LShapeIteration;
import Stages.Scenarios;
import com.gurobi.gurobi.GRBException;

import java.io.IOException;

public class LShapeTest {
    public static void main(String[] args) throws GRBException, IOException {
        Scenarios scenarios = new Scenarios();
        LShapeIteration lShapeIteration = new LShapeIteration(scenarios);
        lShapeIteration.solve();
    }
}
