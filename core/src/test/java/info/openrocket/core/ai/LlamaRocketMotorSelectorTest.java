package info.openrocket.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.motor.Motor;
import info.openrocket.core.rocketcomponent.InnerTube;

class LlamaRocketMotorSelectorTest {

	private final LlamaRocketMotorSelector selector = new LlamaRocketMotorSelector();

	@Test
	void deterministicFiltersRejectBadFitAndLowTwrBeforeSimulation() {
		InnerTube mount = standard18mmMount();
		List<Motor> motors = List.of(
				motor("TooWide", 0.024, 0.070, 20.0, 0.050, 12.0, 28.0),
				motor("TooLong", 0.018, 0.140, 20.0, 0.050, 12.0, 28.0),
				motor("Weak", 0.018, 0.070, 3.0, 0.030, 1.0, 2.0),
				motor("C6-5", 0.018, 0.070, 8.8, 0.025, 5.0, 14.0),
				motor("D10-5", 0.018, 0.070, 18.0, 0.045, 10.0, 25.0));

		LlamaRocketMotorSelector.Plan plan = selector.select(0.100, mount, motors, 150.0, 8);

		assertEquals(3, plan.getPhysicalFitCount());
		assertEquals(2, plan.getTwrPassCount());
		assertEquals(2, plan.getImpulseRatioPassCount());
		assertEquals(List.of("C6-5", "D10-5"),
				plan.getCandidates().stream().map(LlamaRocketMotorSelector.Candidate::getDesignation).toList());
	}

	@Test
	void candidateOrderIsStableForSameInputs() {
		InnerTube mount = standard18mmMount();
		List<Motor> motors = List.of(
				motor("D10-5", 0.018, 0.070, 18.0, 0.045, 10.0, 25.0),
				motor("C6-5", 0.018, 0.070, 8.8, 0.025, 5.0, 14.0),
				motor("B6-4", 0.018, 0.070, 4.5, 0.020, 4.5, 12.0));

		LlamaRocketMotorSelector.Plan first = selector.select(0.100, mount, motors, 150.0, 8);
		LlamaRocketMotorSelector.Plan second = selector.select(0.100, mount, motors, 150.0, 8);

		assertEquals(
				first.getCandidates().stream().map(LlamaRocketMotorSelector.Candidate::getDesignation).toList(),
				second.getCandidates().stream().map(LlamaRocketMotorSelector.Candidate::getDesignation).toList());
		assertTrue(first.getCandidates().get(0).getScore() <= first.getCandidates().get(1).getScore());
	}

	private static InnerTube standard18mmMount() {
		InnerTube mount = new InnerTube();
		mount.setLength(0.070);
		mount.setInnerRadius(0.009);
		mount.setMotorMount(true);
		return mount;
	}

	private static Motor motor(String designation, double diameter, double length, double impulse,
			double launchMass, double averageThrust, double maxThrust) {
		return new FakeMotor(designation, diameter, length, impulse, launchMass, averageThrust, maxThrust);
	}

	private static final class FakeMotor implements Motor {
		private final String designation;
		private final double diameter;
		private final double length;
		private final double impulse;
		private final double launchMass;
		private final double averageThrust;
		private final double maxThrust;

		private FakeMotor(String designation, double diameter, double length, double impulse,
				double launchMass, double averageThrust, double maxThrust) {
			this.designation = designation;
			this.diameter = diameter;
			this.length = length;
			this.impulse = impulse;
			this.launchMass = launchMass;
			this.averageThrust = averageThrust;
			this.maxThrust = maxThrust;
		}

		@Override
		public Type getMotorType() {
			return Type.SINGLE;
		}

		@Override
		public String getCode() {
			return designation;
		}

		@Override
		public String getCommonName() {
			return designation;
		}

		@Override
		public String getCommonName(double delay) {
			return designation;
		}

		@Override
		public String getDesignation() {
			return designation;
		}

		@Override
		public String getDesignation(double delay) {
			return designation;
		}

		@Override
		public String getDescription() {
			return "";
		}

		@Override
		public double getDiameter() {
			return diameter;
		}

		@Override
		public double getLength() {
			return length;
		}

		@Override
		public String getDigest() {
			return designation;
		}

		@Override
		public double getLaunchCGx() {
			return 0;
		}

		@Override
		public double getBurnoutCGx() {
			return 0;
		}

		@Override
		public double getLaunchMass() {
			return launchMass;
		}

		@Override
		public double getBurnoutMass() {
			return launchMass * 0.5;
		}

		@Override
		public double getBurnTimeEstimate() {
			return impulse / averageThrust;
		}

		@Override
		public double getAverageThrustEstimate() {
			return averageThrust;
		}

		@Override
		public double getMaxThrustEstimate() {
			return maxThrust;
		}

		@Override
		public double getTotalImpulseEstimate() {
			return impulse;
		}

		@Override
		public double getBurnTime() {
			return getBurnTimeEstimate();
		}

		@Override
		public double getThrust(double motorTime) {
			return averageThrust;
		}

		@Override
		public double getTotalMass(double motorTime) {
			return launchMass;
		}

		@Override
		public double getPropellantMass(Double motorTime) {
			return launchMass - getBurnoutMass();
		}

		@Override
		public double getCMx(double motorTime) {
			return 0;
		}

		@Override
		public double getUnitIxx() {
			return 0;
		}

		@Override
		public double getUnitIyy() {
			return 0;
		}

		@Override
		public double getUnitIzz() {
			return 0;
		}
	}
}
