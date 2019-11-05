package org.elasql.server.migration.clay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.elasql.server.migration.heatgraph.OutEdge;
import org.elasql.server.migration.heatgraph.Vertex;

public class Partition implements Comparable<Partition> {

	private class VertexWeightComparator implements Comparator<Vertex> {
		@Override
		public int compare(Vertex v1, Vertex v2) {
			if (v1.getVertexWeight() > v2.getVertexWeight())
				return 1;
			else if (v1.getVertexWeight() < v2.getVertexWeight())
				return -1;
			return 0;
		}
	}
	
	private int partId;
	private double localLoad;
	private double crossPartLoad;
	private List<Vertex> vertices;

	public Partition(int partId) {
		this.partId = partId;
		this.localLoad = 0;
		this.crossPartLoad = 0;
		this.vertices = new ArrayList<Vertex>();
	}

	public int getPartId() {
		return partId;
	}
	
	public double getLocalLoad() {
		return localLoad;
	}
	
	public double getCrossPartLoad() {
		return crossPartLoad;
	}

	public double getTotalLoad() {
		return localLoad + ClayPlanner.MULTI_PARTS_COST * crossPartLoad;
	}

	public void addVertex(Vertex v) {
		if (v.getPartId() != partId)
			throw new RuntimeException("Vertex " + v + " is not in partition " + partId);
		
		localLoad += v.getNormalizedVertexWeight();
		for (OutEdge e : v.getOutEdges().values())
			if (e.getOpposite().getPartId() != partId)
				crossPartLoad += e.getNormalizedWeight();
		vertices.add(v);
	}

	public Vertex getHotestVertex() {
		return Collections.max(vertices, new VertexWeightComparator());
	}

	public Vertex[] getFirstTen() {
		Vertex[] vtxs = new Vertex[10];
		Collections.sort(vertices, new VertexWeightComparator());
		int l = vertices.size();
		int j = 0;
		for (int i = l - 1; i >= l - 10; i--) {
			vtxs[j++] = vertices.get(i);
		}
		return vtxs;
	}

	@Override
	public int compareTo(Partition other) {
		double load = getTotalLoad();
		double otherLoad = other.getTotalLoad();
		
		if (load > otherLoad)
			return 1;
		else if (load < otherLoad)
			return -1;
		return 0;
	}

}
