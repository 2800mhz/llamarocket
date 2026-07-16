package info.openrocket.core.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;

/**
 * Deterministic motor pre-filter for LlamaRocket.
 *
 * The language model is intentionally not asked to choose motors for numeric
 * goals.  This class turns the motor database into a small, stable candidate
 * list using physical fit, thrust-to-weight and impulse-per-mass checks.  The UI
 * can then run real OpenRocket simulations for only those candidates.
 */
public final class LlamaRocketMotorSelector {

	private static final double G = 9.80665;
	private static final double DIAMETER_TOLERANCE_M = 0.0005;
	private static final double LENGTH_OVERHANG_ALLOWANCE_M = 0.015;
	private static final double MIN_AVERAGE_TWR = 2.0;
	private static final double MIN_MAX_TWR = 5.0;
	private static final double TARGET_IMPULSE_RATIO_SCALE = 1.35;
	private static final double MIN_TARGET_RATIO_FACTOR = 0.45;
	private static final double MAX_TARGET_RATIO_FACTOR = 2.40;

	public Plan select(FlightConfiguration configuration, MotorMount mount, List<? extends Motor> motors,
			double targetApogeeMeters, int maxCandidates) {
		if (configuration == null) {
			throw new IllegalArgumentException("Flight configuration is required.");
		}
		double structureMassKg = MassCalculator.calculateStructure(configuration).getMass();
		return select(structureMassKg, mount, motors, targetApogeeMeters, maxCandidates);
	}

	public Plan select(double structureMassKg, MotorMount mount, List<? extends Motor> motors,
			double targetApogeeMeters, int maxCandidates) {
		if (mount == null) {
			throw new IllegalArgumentException("Motor mount is required.");
		}
		if (motors == null) {
			throw new IllegalArgumentException("Motor list is required.");
		}
		if (!Double.isFinite(structureMassKg) || structureMassKg <= 0) {
			structureMassKg = 0.001;
		}

		double targetRatio = targetImpulseRatio(targetApogeeMeters);
		int physicalCount = 0;
		int twrCount = 0;
		int ratioCount = 0;

		Map<String, Candidate> unique = new LinkedHashMap<>();
		for (Motor motor : motors) {
			Candidate candidate = buildCandidate(structureMassKg, mount, motor, targetRatio);
			if (candidate == null) {
				continue;
			}
			physicalCount++;
			if (!candidate.twrPass) {
				continue;
			}
			twrCount++;
			if (!candidate.impulseRatioPass) {
				continue;
			}
			ratioCount++;
			Candidate previous = unique.get(candidate.designationKey);
			if (previous == null || candidate.score < previous.score) {
				unique.put(candidate.designationKey, candidate);
			}
		}

		List<Candidate> selected = new ArrayList<>(unique.values());
		selected.sort(Comparator
				.comparingDouble((Candidate c) -> c.score)
				.thenComparing(c -> c.designation));
		if (selected.size() > maxCandidates) {
			selected = new ArrayList<>(selected.subList(0, maxCandidates));
		}
		return new Plan(motors.size(), physicalCount, twrCount, ratioCount, targetRatio, selected);
	}

	private Candidate buildCandidate(double structureMassKg, MotorMount mount, Motor motor, double targetRatio) {
		if (motor == null) {
			return null;
		}
		double diameter = motor.getDiameter();
		double length = motor.getLength();
		double impulse = motor.getTotalImpulseEstimate();
		double launchMass = motor.getLaunchMass();
		double averageThrust = finiteOrEstimateAverageThrust(motor, impulse);
		double maxThrust = motor.getMaxThrustEstimate();

		if (!positiveFinite(diameter) || !positiveFinite(length) || !positiveFinite(impulse) ||
				!positiveFinite(launchMass) || !positiveFinite(averageThrust)) {
			return null;
		}
		if (impulse <= 1.0) {
			return null;
		}
		double mountDiameter = mount.getMotorMountDiameter();
		if (positiveFinite(mountDiameter) && diameter > mountDiameter + DIAMETER_TOLERANCE_M) {
			return null;
		}
		double allowedLength = mount.getLength() + Math.max(0.0, mount.getMotorOverhang()) + LENGTH_OVERHANG_ALLOWANCE_M;
		if (positiveFinite(allowedLength) && length > allowedLength) {
			return null;
		}

		int motorCount = Math.max(1, mount.getInstanceCount());
		double liftoffMass = structureMassKg + launchMass * motorCount;
		double averageTwr = averageThrust * motorCount / (liftoffMass * G);
		double maxTwr = positiveFinite(maxThrust) ? maxThrust * motorCount / (liftoffMass * G) : Double.NaN;
		boolean twrPass = averageTwr >= MIN_AVERAGE_TWR || (Double.isFinite(maxTwr) && maxTwr >= MIN_MAX_TWR);

		double impulseRatio = impulse * motorCount / liftoffMass;
		boolean ratioPass = !Double.isFinite(targetRatio) ||
				(impulseRatio >= targetRatio * MIN_TARGET_RATIO_FACTOR &&
						impulseRatio <= targetRatio * MAX_TARGET_RATIO_FACTOR);

		double ratioScore = Double.isFinite(targetRatio)
				? Math.abs(Math.log(Math.max(impulseRatio, 0.000001) / targetRatio))
				: 0.0;
		double twrScore = Math.max(0.0, (4.0 - averageTwr) / 4.0);
		double score = ratioScore * 3.0 + twrScore;

		return new Candidate(motor, motor.getDesignation(), normalizeDesignation(motor.getDesignation()),
				diameter, length, impulse, launchMass, liftoffMass, averageThrust, maxThrust,
				averageTwr, maxTwr, impulseRatio, twrPass, ratioPass, score);
	}

	private static double finiteOrEstimateAverageThrust(Motor motor, double impulse) {
		double average = motor.getAverageThrustEstimate();
		if (positiveFinite(average)) {
			return average;
		}
		double burnTime = motor.getBurnTimeEstimate();
		if (positiveFinite(burnTime)) {
			return impulse / burnTime;
		}
		return Double.NaN;
	}

	private static double targetImpulseRatio(double targetApogeeMeters) {
		if (!Double.isFinite(targetApogeeMeters) || targetApogeeMeters <= 0) {
			return Double.NaN;
		}
		return TARGET_IMPULSE_RATIO_SCALE * Math.sqrt(2.0 * G * targetApogeeMeters);
	}

	private static boolean positiveFinite(double value) {
		return Double.isFinite(value) && value > 0;
	}

	private static String normalizeDesignation(String designation) {
		return designation == null ? "" : designation.trim().toLowerCase(Locale.ROOT);
	}

	public static final class Plan {
		private final int totalMotors;
		private final int physicalFitCount;
		private final int twrPassCount;
		private final int impulseRatioPassCount;
		private final double targetImpulseRatioNsPerKg;
		private final List<Candidate> candidates;

		private Plan(int totalMotors, int physicalFitCount, int twrPassCount, int impulseRatioPassCount,
				double targetImpulseRatioNsPerKg, List<Candidate> candidates) {
			this.totalMotors = totalMotors;
			this.physicalFitCount = physicalFitCount;
			this.twrPassCount = twrPassCount;
			this.impulseRatioPassCount = impulseRatioPassCount;
			this.targetImpulseRatioNsPerKg = targetImpulseRatioNsPerKg;
			this.candidates = List.copyOf(candidates);
		}

		public int getTotalMotors() {
			return totalMotors;
		}

		public int getPhysicalFitCount() {
			return physicalFitCount;
		}

		public int getTwrPassCount() {
			return twrPassCount;
		}

		public int getImpulseRatioPassCount() {
			return impulseRatioPassCount;
		}

		public double getTargetImpulseRatioNsPerKg() {
			return targetImpulseRatioNsPerKg;
		}

		public List<Candidate> getCandidates() {
			return candidates;
		}
	}

	public static final class Candidate {
		private final Motor motor;
		private final String designation;
		private final String designationKey;
		private final double diameterMeters;
		private final double lengthMeters;
		private final double totalImpulseNs;
		private final double motorLaunchMassKg;
		private final double estimatedLaunchMassKg;
		private final double averageThrustN;
		private final double maxThrustN;
		private final double averageTwr;
		private final double maxTwr;
		private final double impulseRatioNsPerKg;
		private final boolean twrPass;
		private final boolean impulseRatioPass;
		private final double score;

		private Candidate(Motor motor, String designation, String designationKey, double diameterMeters,
				double lengthMeters, double totalImpulseNs, double motorLaunchMassKg, double estimatedLaunchMassKg,
				double averageThrustN, double maxThrustN, double averageTwr, double maxTwr,
				double impulseRatioNsPerKg, boolean twrPass, boolean impulseRatioPass, double score) {
			this.motor = motor;
			this.designation = designation;
			this.designationKey = designationKey;
			this.diameterMeters = diameterMeters;
			this.lengthMeters = lengthMeters;
			this.totalImpulseNs = totalImpulseNs;
			this.motorLaunchMassKg = motorLaunchMassKg;
			this.estimatedLaunchMassKg = estimatedLaunchMassKg;
			this.averageThrustN = averageThrustN;
			this.maxThrustN = maxThrustN;
			this.averageTwr = averageTwr;
			this.maxTwr = maxTwr;
			this.impulseRatioNsPerKg = impulseRatioNsPerKg;
			this.twrPass = twrPass;
			this.impulseRatioPass = impulseRatioPass;
			this.score = score;
		}

		public Motor getMotor() {
			return motor;
		}

		public String getDesignation() {
			return designation;
		}

		public double getDiameterMeters() {
			return diameterMeters;
		}

		public double getLengthMeters() {
			return lengthMeters;
		}

		public double getTotalImpulseNs() {
			return totalImpulseNs;
		}

		public double getMotorLaunchMassKg() {
			return motorLaunchMassKg;
		}

		public double getEstimatedLaunchMassKg() {
			return estimatedLaunchMassKg;
		}

		public double getAverageThrustN() {
			return averageThrustN;
		}

		public double getMaxThrustN() {
			return maxThrustN;
		}

		public double getAverageTwr() {
			return averageTwr;
		}

		public double getMaxTwr() {
			return maxTwr;
		}

		public double getImpulseRatioNsPerKg() {
			return impulseRatioNsPerKg;
		}

		public double getScore() {
			return score;
		}
	}
}
