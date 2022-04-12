package org.elasql.storage.tx.concurrency;

import java.util.concurrent.ConcurrentHashMap;

import org.elasql.storage.tx.concurrency.fifolocker.FifoLock;
import org.elasql.storage.tx.concurrency.fifolocker.FifoLockers;

public class FifoOrderedLockTable {
	/**
	 * lockerMap maps Objects to FifoLockers. Be aware that the key type is declared
	 * as Object though, it is type of PrimaryKey under the hood.
	 */
	private ConcurrentHashMap<Object, FifoLockers> lockerMap = new ConcurrentHashMap<Object, FifoLockers>();

	/**
	 * RequestLock will add the given fifoLock into the requestQueue of the given
	 * primaryKey. Object is type of PrimaryKey under the hood.
	 * 
	 * @param obj
	 * @param fifoLock
	 */
	void requestLock(Object obj, FifoLock fifoLock) {
		// prevent executing "new FifoLockers()" frequently,
		if (!lockerMap.contains(obj)) {
			/*
			 * putIfAbsent is an atomic operation. It's OK to let two threads put a new
			 * FifoLockers into the lockerMap because the second thread's fifoLockers will
			 * be ignored. After that, the two threads will get the exactly same FifoLockers
			 * from the map again.
			 */
			lockerMap.putIfAbsent(obj, new FifoLockers());
		}

		FifoLockers lks = lockerMap.get(obj);
		lks.addToRequestQueue(fifoLock);
	}

	void sLock(Object obj, FifoLock fifoLock) {
		FifoLockers lks = lockerMap.get(obj);

		lks.waitOrPossessSLock(fifoLock);
	}

	void xLock(Object obj, FifoLock fifoLock) {
		FifoLockers lks = lockerMap.get(obj);

		lks.waitOrPossessXLock(fifoLock);
	}

	void releaseSLock(Object obj, long txNum) {
		FifoLockers lks = lockerMap.get(obj);

		lks.releaseSLock(txNum);
	}

	void releaseXLock(Object obj, long txNum) {
		FifoLockers lks = lockerMap.get(obj);

		lks.releaseXLock(txNum);
	}
}
