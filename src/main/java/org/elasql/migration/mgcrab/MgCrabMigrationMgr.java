package org.elasql.migration.mgcrab;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.elasql.cache.CachedRecord;
import org.elasql.migration.MigrationComponentFactory;
import org.elasql.migration.MigrationMgr;
import org.elasql.migration.MigrationRange;
import org.elasql.migration.MigrationRangeFinishMessage;
import org.elasql.migration.MigrationRangeUpdate;
import org.elasql.migration.MigrationSystemController;
import org.elasql.remote.groupcomm.StoredProcedureCall;
import org.elasql.remote.groupcomm.TupleSet;
import org.elasql.schedule.calvin.CalvinScheduler;
import org.elasql.schedule.calvin.ReadWriteSetAnalyzer;
import org.elasql.schedule.calvin.mgcrab.CaughtUpAnalyzer;
import org.elasql.schedule.calvin.mgcrab.CrabbingAnalyzer;
import org.elasql.server.Elasql;
import org.elasql.sql.RecordKey;
import org.elasql.storage.metadata.NotificationPartitionPlan;
import org.elasql.storage.metadata.PartitionPlan;
import org.vanilladb.core.storage.tx.Transaction;

/**
 * The migration manager that exists in each node. Job: 
 * (1) trace the migration states, 
 * (2) initialize a background push transaction, and 
 * (3) send the finish notification to the main controller on the sequencer node.
 */
public class MgCrabMigrationMgr implements MigrationMgr {
	private static Logger logger = Logger.getLogger(MgCrabMigrationMgr.class.getName());
	
	private Phase currentPhase = Phase.NORMAL;
	private List<MigrationRange> migrationRanges;
	private List<MigrationRange> pushRanges = new ArrayList<MigrationRange>(); // the ranges whose destination is this node.
	private PartitionPlan newPartitionPlan;
	private MigrationComponentFactory comsFactory;
	
	// XXX: We assume that the destination only has one range to receive at a time. 
	private MigrationRangeUpdate lastUpdate;
	
	// For the source node to record the last pushed keys in the two phase
	// background pushing.
	private Map<Long, Set<RecordKey>> txNumToPushKeys = 
			new ConcurrentHashMap<Long, Set<RecordKey>>();
	
	// Two phase background pushing stores the pushed records on the
	// destination node.
	private Map<Long, Map<RecordKey, CachedRecord>> txNumToPushedRecords = 
			new ConcurrentHashMap<Long, Map<RecordKey, CachedRecord>>();
	
	public MgCrabMigrationMgr(MigrationComponentFactory comsFactory) {
		this.comsFactory = comsFactory;
	}
	
	public void initializeMigration(Transaction tx, Object[] params) {
		// Parse parameters
		PartitionPlan newPartPlan = (PartitionPlan) params[0];
		Phase initialPhase = (Phase) params[1];
		
		if (logger.isLoggable(Level.INFO)) {
			long time = System.currentTimeMillis() - CalvinScheduler.FIRST_TX_ARRIVAL_TIME.get();
			PartitionPlan currentPartPlan = Elasql.partitionMetaMgr().getPartitionPlan();
			logger.info(String.format("a new migration starts at %d. Current Plan: %s, New Plan: %s"
					, time / 1000, currentPartPlan, newPartPlan));
		}
		
		// Initialize states
		currentPhase = initialPhase;
		PartitionPlan currentPlan = Elasql.partitionMetaMgr().getPartitionPlan();
		if (currentPlan.getClass().equals(NotificationPartitionPlan.class))
			currentPlan = ((NotificationPartitionPlan) currentPlan).getUnderlayerPlan();
		migrationRanges = comsFactory.generateMigrationRanges(currentPlan, newPartPlan);
		for (MigrationRange range : migrationRanges)
			if (range.getDestPartId() == Elasql.serverId())
				pushRanges.add(range);
		newPartitionPlan = newPartPlan;
		
		if (!pushRanges.isEmpty())
			scheduleNextBGPushRequest(-1);
		
		if (logger.isLoggable(Level.INFO)) {
			logger.info(String.format("migration ranges: %s", migrationRanges.toString()));
		}
	}
	
	public void scheduleNextBGPushRequest(long lastPushedTxNum) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				// XXX: If there is multiple ranges to this destinations
				// we should know which range pairs to this transaction number
				for (MigrationRange range : pushRanges) {
					Set<RecordKey> chunk = range.generateNextMigrationChunk(USE_BYTES_FOR_CHUNK_SIZE, CHUNK_SIZE);
					if (chunk.size() > 0) {
						sendBGPushRequest(chunk, range.getSourcePartId(), 
								range.getDestPartId(), lastPushedTxNum);
						lastUpdate = range.generateStatusUpdate();
						return;
					}
				}
				
				// If it reach here, this should the last push
				sendBGPushRequest(new HashSet<RecordKey>(), lastUpdate.getSourcePartId(),
							lastUpdate.getDestPartId(), lastPushedTxNum);
			}
		}).start();
	}
	
	public void sendBGPushRequest(Set<RecordKey> chunk, int sourceNodeId, int destNodeId, long lastPushedTxNum) {
		if (logger.isLoggable(Level.INFO))
			logger.info("send a background push request with " + chunk.size() + " keys.");
		
		// Prepare the parameters
		Object[] params = new Object[5 + chunk.size()];
		
		params[0] = lastUpdate;
		params[1] = sourceNodeId;
		params[2] = destNodeId;
		params[3] = lastPushedTxNum;
		params[4] = chunk.size();
		
		int i = 5;
		for (RecordKey key : chunk)
			params[i++] = key;
		
		// Send a store procedure call
		Object[] call = { new StoredProcedureCall(-1, -1, 
				MgCrabStoredProcFactory.SP_BG_PUSH, params)};
		Elasql.connectionMgr().sendBroadcastRequest(call, false);
	}
	
	public void cachePushKeys(long pushTxNum, Set<RecordKey> pushKeys) {
		txNumToPushKeys.put(pushTxNum, pushKeys);
	}
	
	public void cachePushedRecords(long pushTxNum, Map<RecordKey, CachedRecord> pushedRecords) {
		txNumToPushedRecords.put(pushTxNum, pushedRecords);
	}
	
	public Set<RecordKey> retrievePushKeys(long pushTxNum) {
		return txNumToPushKeys.remove(pushTxNum);
	}
	
	public Map<RecordKey, CachedRecord> retrievePushedRecords(long pushTxNum) {
		return txNumToPushedRecords.remove(pushTxNum);
	}
	
	public void changePhase(Phase newPhase) {
		if (logger.isLoggable(Level.INFO)) {
			long time = System.currentTimeMillis() - CalvinScheduler.FIRST_TX_ARRIVAL_TIME.get();
			logger.info(String.format("the migration changes to %s phase at %d ms."
					, newPhase, time / 1000));
		}
		
		currentPhase = newPhase;
	}
	
	public void updateMigrationRange(MigrationRangeUpdate update) {
		for (MigrationRange range : migrationRanges)
			if (range.updateMigrationStatus(update))
				return;
		throw new RuntimeException(String.format("This is no match for the update", update));
	}
	
	// XXX: Currently, we do not identify which range finished.
	public void sendRangeFinishNotification() {
		if (logger.isLoggable(Level.INFO))
			logger.info("send a range finish notification to the system controller");
		
		TupleSet ts = new TupleSet(MigrationSystemController.MSG_RANGE_FINISH);
		ts.setMetadata(new MigrationRangeFinishMessage(pushRanges.size())); // notify how many ranges are migrated
		Elasql.connectionMgr().pushTupleSet(MigrationSystemController.CONTROLLER_NODE_ID, ts);
	}
	
	public void finishMigration(Transaction tx, Object[] params) {
		if (logger.isLoggable(Level.INFO)) {
			long time = System.currentTimeMillis() - CalvinScheduler.FIRST_TX_ARRIVAL_TIME.get();
			logger.info(String.format("the migration finishes at %d."
					, time / 1000));
		}
		
		// Change the current partition plan of the system
		Elasql.partitionMetaMgr().setNewPartitionPlan(newPartitionPlan);
		
		// Clear the migration states
		currentPhase = Phase.NORMAL;
		lastUpdate = null;
		migrationRanges.clear();
		pushRanges.clear();
	}
	
	public boolean isMigratingRecord(RecordKey key) {
		for (MigrationRange range : migrationRanges)
			if (range.contains(key))
				return true;
		return false;
	}
	
	public boolean isMigrated(RecordKey key) {
		for (MigrationRange range : migrationRanges)
			if (range.contains(key))
				return range.isMigrated(key);
		throw new RuntimeException(String.format("%s is not a migrating record", key));
	}
	
	public void setMigrated(RecordKey key) {
		for (MigrationRange range : migrationRanges)
			if (range.contains(key)) {
				range.setMigrated(key);
				return;
			}
		throw new RuntimeException(String.format("%s is not a migrating record", key));
	}
	
	public int checkSourceNode(RecordKey key) {
		for (MigrationRange range : migrationRanges)
			if (range.contains(key))
				return range.getSourcePartId();
		throw new RuntimeException(String.format("%s is not a migrating record", key));
	}
	
	public int checkDestNode(RecordKey key) {
		for (MigrationRange range : migrationRanges)
			if (range.contains(key))
				return range.getDestPartId();
		throw new RuntimeException(String.format("%s is not a migrating record", key));
	}
	
	public Phase getCurrentPhase() {
		return currentPhase;
	}
	
	public boolean isInMigration() {
		return currentPhase != Phase.NORMAL;
	}
	
	public ReadWriteSetAnalyzer newAnalyzer() {
		if (currentPhase == Phase.CRABBING)
			return new CrabbingAnalyzer();
		else if (currentPhase == Phase.CAUGHT_UP)
			return new CaughtUpAnalyzer();
		else
			throw new RuntimeException(
					String.format("We haven't implement %s phase yet.", currentPhase));
	}
}
