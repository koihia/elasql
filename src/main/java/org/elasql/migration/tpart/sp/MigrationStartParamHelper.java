package org.elasql.migration.tpart.sp;

import org.elasql.storage.metadata.RangePartitionPlan;
import org.vanilladb.core.remote.storedprocedure.SpResultSet;
import org.vanilladb.core.sql.Schema;
import org.vanilladb.core.sql.Type;
import org.vanilladb.core.sql.VarcharConstant;
import org.vanilladb.core.sql.storedprocedure.SpResultRecord;
import org.vanilladb.core.sql.storedprocedure.StoredProcedureParamHelper;

public class MigrationStartParamHelper extends StoredProcedureParamHelper {
	
	private RangePartitionPlan oldPlan, newPlan;
	private String tableName;
	
	@Override
	public void prepareParameters(Object... pars) {
		oldPlan = (RangePartitionPlan) pars[0];
		newPlan = (RangePartitionPlan) pars[1];
		tableName = (String) pars[2];
	}
	
	@Override
	public boolean isReadOnly() {
		return false;
	}
	
	public RangePartitionPlan getOldPartitionPlan() {
		return oldPlan;
	}
	
	public RangePartitionPlan getNewPartitionPlan() {
		return newPlan;
	}
	
	public String getTableName() {
		return tableName;
	}
	
	@Override
	public SpResultSet createResultSet() {
		// Return the result
		Schema sch = new Schema();
		Type t = Type.VARCHAR(10);
		sch.addField("status", t);
		SpResultRecord rec = new SpResultRecord();
		String status = isCommitted ? "committed" : "abort";
		rec.setVal("status", new VarcharConstant(status, t));
		return new SpResultSet(sch, rec);
	}
}
