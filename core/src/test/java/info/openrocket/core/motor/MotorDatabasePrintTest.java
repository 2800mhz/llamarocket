package info.openrocket.core.motor;
import org.junit.jupiter.api.Test;
import info.openrocket.core.startup.Application;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import info.openrocket.core.motor.ThrustCurveMotor;
import java.util.List;

public class MotorDatabasePrintTest {
    @Test
    public void printAllMotors() throws Exception {
        info.openrocket.core.startup.Providers.guiceInit();
        ThrustCurveMotorSetDatabase db = (ThrustCurveMotorSetDatabase) Application.getMotorSetDatabase();
        if (db == null) {
            System.out.println("Database is null!");
            return;
        }
        int count = 0;
        for (ThrustCurveMotorSet set : db.getMotorSets()) {
            for (ThrustCurveMotor m : set.getMotors()) {
                System.out.println("MOTOR_DESIGNATION: " + m.getDesignation() + " | COMMON: " + m.getCommonName() + " | MFR: " + m.getManufacturer());
                count++;
                if (count > 50) return; // Print first 50
            }
        }
        System.out.println("Total motors checked: " + count);
    }
}
