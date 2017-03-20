/*******************************************************************************
 * Copyright 2016 vanilladb.org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.elasql.cache.naive;

import org.elasql.cache.CacheMgr;
import org.elasql.cache.CachedRecord;
import org.elasql.cache.LocalRecordMgr;
import org.elasql.sql.RecordKey;
import org.vanilladb.core.storage.tx.Transaction;

public class NaiveCacheMgr implements CacheMgr {

	public CachedRecord read(RecordKey key, Transaction tx) {
		return LocalRecordMgr.read(key, tx);
	}

	public void update(RecordKey key, CachedRecord rec, Transaction tx) {
		LocalRecordMgr.update(key, rec, tx);
	}

	public void insert(RecordKey key, CachedRecord rec, Transaction tx) {
		LocalRecordMgr.insert(key, rec, tx);
	}

	public void delete(RecordKey key, Transaction tx) {
		LocalRecordMgr.delete(key, tx);
	}
}
