import info.openrocket.core.rocketcomponent.*;
import info.openrocket.core.startup.Application;
import info.openrocket.core.simulation.Simulation;

public class TestNaN {
    public static void main(String[] args) throws Exception {
        Application.getMotorSetDatabase();

        Rocket rocket = new Rocket();
        AxialStage stage1 = new AxialStage(); stage1.setName("Stage1");
        AxialStage stage2 = new AxialStage(); stage2.setName("Stage2");
        rocket.addChild(stage1);
        rocket.addChild(stage2);

        stage2.addChild(new NoseCone());
        BodyTube stage1body = new BodyTube(); stage1.addChild(stage1body);
        BodyTube stage2body = new BodyTube(); stage2.addChild(stage2body);

        stage1.addChild(new Transition());
        stage2body.addChild(new MassComponent());

        FlightConfiguration config = rocket.getDefaultConfiguration();
        stage1body.setMotorMount(true);
        config.setMotor(stage1body, Application.getMotorSetDatabase().findMotors("M104").get(0));

        Simulation sim = new Simulation(rocket);
        sim.getOptions().setMotorConfigurationID(config.getId());
        sim.simulate();
        System.out.println("Simulated successfully!");
    }
}
