package org.elasql.perf.tpart.control;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.elasql.perf.tpart.metric.TpartMetricWarehouse;
import org.elasql.server.Elasql;
import org.elasql.storage.metadata.PartitionMetaMgr;
import org.elasql.util.ElasqlProperties;
import org.vanilladb.core.server.task.Task;

/**
 * The actuator controls the parameters of {@code ControlBasedRouter} based
 * on the current system metrics.
 * 
 * @author Yu-Shan Lin
 */
public class RoutingControlActuator extends Task {
	private static Logger logger = Logger.getLogger(RoutingControlActuator.class.getName());
	
	private static final long UPDATE_PERIOD;
	private static final double INITIAL_ALPHA;
	private static final double[] CPU_MAX_CAPACITIES;
	
	static {
		UPDATE_PERIOD = ElasqlProperties.getLoader().getPropertyAsLong(
				RoutingControlActuator.class.getName() + ".UPDATE_PERIOD", 5_000);
		INITIAL_ALPHA = ElasqlProperties.getLoader().getPropertyAsDouble(
				RoutingControlActuator.class.getName() + ".INITIAL_ALPHA", 1.0);
		
		// Gather CPU MAXs
		double[] cpuMaxCapacities = new double[PartitionMetaMgr.NUM_PARTITIONS];
		for (int i = 0; i < cpuMaxCapacities.length; i++)
			cpuMaxCapacities[i] = 1.0;
		
		String cpuMaxStr = ElasqlProperties.getLoader().getPropertyAsString(
				RoutingControlActuator.class.getName() + ".CPU_MAX_CAPACITIES", "");
		if (!cpuMaxStr.isEmpty()) {
			String[] cpuMaxValues = cpuMaxStr.split(",");
			for (int i = 0; i < cpuMaxValues.length; i++) {
				double value = Double.parseDouble(cpuMaxValues[i].trim());
				cpuMaxCapacities[i] = value;
			}
		}
		CPU_MAX_CAPACITIES = cpuMaxCapacities;
	}
	
	// Alpha parameters control the weights of CPU cost
	// Beta parameters control the weights of disk cost
	// Gamma parameters control the weights of network cost
	private PidController[] alpha;
	private PidController[] beta;
	private PidController[] gamma;
	
	private TpartMetricWarehouse metricWarehouse;

	public RoutingControlActuator(TpartMetricWarehouse metricWarehouse) {
		this.metricWarehouse = metricWarehouse;
		
		alpha = new PidController[PartitionMetaMgr.NUM_PARTITIONS];
		beta = new PidController[PartitionMetaMgr.NUM_PARTITIONS];
		gamma = new PidController[PartitionMetaMgr.NUM_PARTITIONS];
		
		for (int nodeId = 0; nodeId < PartitionMetaMgr.NUM_PARTITIONS; nodeId++) {
			alpha[nodeId] = new PidController(INITIAL_ALPHA);
			beta[nodeId] = new PidController(1.0);
			gamma[nodeId] = new PidController(1.0);
		}
	}
	
	@Override
	public void run() {
		Thread.currentThread().setName("routing-control-actuator");
		
		waitForServersReady();
		
		if (logger.isLoggable(Level.INFO))
			logger.info("Starting the routing control actuator");
		
		long startTimeInMs = System.currentTimeMillis();
		
		while (true) {
			// Wait for the next update
			waitForUpdate(startTimeInMs);
			
			// Get the time offset
			long currentTimeInMs = System.currentTimeMillis();
			double timeOffsetInSecs = (currentTimeInMs - startTimeInMs) / 1000.0;
			startTimeInMs = currentTimeInMs;
			
			// Get observation values
			acquireObservations();
			
			// Update reference values
			updateReferences();
			
			// Update parameters
			updateParameters(timeOffsetInSecs);
			
			// Issue an update transaction
			issueUpdateTransaction();
		}
	}
	
	private void waitForServersReady() {
		while (!Elasql.connectionMgr().areAllServersReady()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void waitForUpdate(long startTime) {
		while (System.currentTimeMillis() - startTime < UPDATE_PERIOD) {
			try {
				Thread.sleep(UPDATE_PERIOD / 10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void acquireObservations() {
		// TODO: add disk and network I/O
		// XXX: right observation?
		for (int nodeId = 0; nodeId < PartitionMetaMgr.NUM_PARTITIONS; nodeId++) {
			double observation = metricWarehouse.getAveragedSystemCpuLoad(nodeId, UPDATE_PERIOD);
			observation = observation / CPU_MAX_CAPACITIES[nodeId];
			observation = Math.min(observation, 1.0);
			alpha[nodeId].setObservation(observation);
		}
	}
	
	private void updateReferences() {
		// TODO: add disk and network I/O
		// XXX: right reference?
		double sum = 0;
		for (int nodeId = 0; nodeId < PartitionMetaMgr.NUM_PARTITIONS; nodeId++)
			sum += alpha[nodeId].getObservation();
		double average = sum / PartitionMetaMgr.NUM_PARTITIONS;
		for (int nodeId = 0; nodeId < PartitionMetaMgr.NUM_PARTITIONS; nodeId++)
			alpha[nodeId].setReference(average);
	}
	
	private void updateParameters(double timeOffsetInSecs) {
		for (int nodeId = 0; nodeId < PartitionMetaMgr.NUM_PARTITIONS; nodeId++) {
			String info = alpha[nodeId].updateControlParameters(timeOffsetInSecs);
			System.out.print("Alpha #" + nodeId + ": " + info);
		}
	}
	
	private void issueUpdateTransaction() {
		// Prepare the parameters
		Object[] params = new Object[PartitionMetaMgr.NUM_PARTITIONS * 3];
		for (int nodeId = 0; nodeId < PartitionMetaMgr.NUM_PARTITIONS; nodeId++) {
			params[nodeId] = 
					alpha[nodeId].getControlParameter();
			params[PartitionMetaMgr.NUM_PARTITIONS + nodeId] = 
					beta[nodeId].getControlParameter();
			params[PartitionMetaMgr.NUM_PARTITIONS * 2 + nodeId] = 
					gamma[nodeId].getControlParameter();
		}
		
		// Send a store procedure call
		Elasql.connectionMgr().sendStoredProcedureCall(false, 
				ControlStoredProcedureFactory.SP_CONTROL_PARAM_UPDATE, params);
	}
}
