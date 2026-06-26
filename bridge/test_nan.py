from orhelper import OpenRocketInstance
with OpenRocketInstance() as instance:
    from info.openrocket.core.rocketcomponent import Rocket, AxialStage, BodyTube, NoseCone, Transition, MassComponent, Parachute, TrapezoidFinSet, ShockCord
    from info.openrocket.core.startup import Application
    from info.openrocket.core.motor import MotorConfiguration
    from info.openrocket.core.simulation import Simulation

    Application.getMotorSetDatabase() # init

    rocket = Rocket()
    stage1 = AxialStage()
    stage1.setName("Stage1")
    rocket.addChild(stage1)
    
    stage2 = AxialStage()
    stage2.setName("Stage2")
    rocket.addChild(stage2)

    stage2nose = NoseCone()
    stage2.addChild(stage2nose)

    stage1body = BodyTube()
    stage1.addChild(stage1body)

    stage2body = BodyTube()
    stage2.addChild(stage2body)

    trans = Transition()
    stage1.addChild(trans)

    mass = MassComponent()
    stage2body.addChild(mass)

    config = rocket.getDefaultConfiguration()

    motor = Application.getMotorSetDatabase().findMotors("M104").get(0)
    stage1body.setMotorMount(True)
    config.setMotor(stage1body, motor)
    
    sim = Simulation(rocket)
    sim.getOptions().setMotorConfigurationID(config.getId())
    sim.simulate()
    print("Simulated successfully!")
