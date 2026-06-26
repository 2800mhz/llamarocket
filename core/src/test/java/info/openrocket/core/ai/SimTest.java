package info.openrocket.core.ai;
import org.junit.jupiter.api.Test;
import info.openrocket.core.startup.Application;
import info.openrocket.core.rocketcomponent.*;
import info.openrocket.core.motor.*;
import info.openrocket.core.simulation.*;
import java.util.List;

public class SimTest {
    @Test
    public void testSim() throws Exception {
        try {
            info.openrocket.core.startup.Providers.guiceInit();
        } catch(Throwable t) {}
        
        info.openrocket.core.database.motor.MotorDatabase db = Application.getMotorSetDatabase();
        if (db == null) {
            System.out.println("No Motor DB.");
            return;
        }
        
        Rocket rocket = new Rocket();
        Stage stage = new Stage();
        rocket.addChild(stage);
        
        NoseCone nose = new NoseCone();
        stage.addChild(nose);
        
        BodyTube body = new BodyTube();
        stage.addChild(body);
        
        TrapezoidFinSet fins = new TrapezoidFinSet();
        fins.setAxialMethod(info.openrocket.core.rocketcomponent.position.AxialMethod.BOTTOM);
        fins.setAxialOffset(0.0);
        body.addChild(fins);
        
        body.setMotorMount(true);
        List<? extends Motor> motors = db.findMotors(null, null, null, "A8", Double.NaN, Double.NaN);
        if (motors.isEmpty()) return;
        Motor motor = motors.get(0);
        
        FlightConfigurationId fcid = FlightConfigurationId.DEFAULT_VALUE_FCID;
        MotorConfiguration newConfig = new MotorConfiguration(body, fcid);
        newConfig.setMotor(motor);
        newConfig.setEjectionDelay(3.0);
        body.setMotorConfig(newConfig, fcid);
        
        Simulation sim = new Simulation(rocket, rocket.getDefaultConfiguration());
        sim.getOptions().setMotorConfigurationID(fcid);
        
        try {
            sim.simulate();
            System.out.println("APOGEE RESULT: " + sim.getSimulatedData().getMaxAltitude());
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }
}
