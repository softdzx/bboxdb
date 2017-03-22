/*******************************************************************************
 *
 *    Copyright (C) 2015-2017 the BBoxDB project
 *  
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License. 
 *    
 *******************************************************************************/
package org.bboxdb.performance.osm.experiments;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bboxdb.performance.osm.OSMFileReader;
import org.bboxdb.performance.osm.OSMStructureCallback;
import org.bboxdb.performance.osm.OSMType;
import org.bboxdb.performance.osm.util.Polygon;
import org.bboxdb.performance.osm.util.SerializerHelper;
import org.bboxdb.storage.entity.BoundingBox;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DetermineSamplingSize implements Runnable, OSMStructureCallback {

	/**
	 * The db instance
	 */
	protected final DB db;
	
	/**
	 * The node map
	 */
	protected final Map<Long, byte[]> nodeMap;
	
	/**
	 * The node serializer
	 */
	protected final SerializerHelper<Polygon> serializerHelper = new SerializerHelper<>();

	/**
	 * The file to import
	 */
	protected final String filename;
	
	/**
	 * The element counter
	 */
	protected long elementCounter;

	/**
	 * The retry counter for the experiments
	 */
	public final static int EXPERIMENT_RETRY = 10;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(DetermineSamplingSize.class);
	
	public DetermineSamplingSize(final String filename) throws IOException {
		this.filename = filename;
		
		this.elementCounter = 0;
		
		final File dbFile = File.createTempFile("osm-db-sampling", ".tmp");
		dbFile.delete();
		
		// Use a disk backed map, to process files > Memory
		this.db = DBMaker.fileDB(dbFile).fileMmapEnableIfSupported().fileDeleteAfterClose().make();
		this.nodeMap = db.hashMap("osm-id-map").keySerializer(Serializer.LONG)
		        .valueSerializer(Serializer.BYTE_ARRAY)
		        .create();
	}
	
	@Override
	public void run() {
		System.out.format("Importing %s\n", filename);
		final OSMFileReader osmFileReader = new OSMFileReader(filename, OSMType.TREE, this);
		osmFileReader.run();
		final int numberOfElements = nodeMap.keySet().size();
		System.out.format("Imported %d objects\n", numberOfElements);
		
		final List<Double> sampleSizes = Arrays.asList(
				0.1d, 0.2d, 0.3d, 0.4d, 0.5d, 
				0.6d, 0.7d, 0.8d, 0.9d, 1.0d, 
				5d, 10d, 20d, 30d, 40d, 50d, 60d);
		
		for(final Double sampleSize : sampleSizes) {
			runExperiment(sampleSize);
		}
	}

	/**
	 * Run the experiment with the given sample size
	 * @param sampleSize
	 */
	protected void runExperiment(final double sampleSize) {
		System.out.println("Simulating with sample size: " + sampleSize);
		
		final int numberOfElements = nodeMap.keySet().size();
		final int numberOfSamples = (int) (numberOfElements / 100 * sampleSize);
		
		final ExperimentSeriesStatistics experimentSeriesStatistics = new ExperimentSeriesStatistics();
		
		try {
			ExperimentStatistics.printExperientHeader();
			for(int experiment = 0; experiment < EXPERIMENT_RETRY; experiment++) {
				final double splitPos = getSplit(numberOfSamples);
				final ExperimentStatistics statistics = runExperimentForPos(splitPos);
				statistics.printExperimentResult(experiment);
				experimentSeriesStatistics.addExperiment(statistics);		
			}
			
			experimentSeriesStatistics.printStatistics();
			System.out.println("\n\n");
			
		} catch (ClassNotFoundException | IOException e) {
			System.err.println(e.getStackTrace());
		}
	}

	/**
	 * Run the experiment for the given position
	 * @param splitPos
	 * @return 
	 * @throws IOException 
	 * @throws ClassNotFoundException 
	 */
	protected ExperimentStatistics runExperimentForPos(final double splitPos) throws ClassNotFoundException, IOException {

		final ExperimentStatistics statistics = new ExperimentStatistics();		
		final BoundingBox fullBox = BoundingBox.createFullCoveringDimensionBoundingBox(2);
		final BoundingBox leftBox = fullBox.splitAndGetLeft(splitPos, 0, true);
		final BoundingBox rightBox = fullBox.splitAndGetRight(splitPos, 0, false);
		
		for(final Long id : nodeMap.keySet()) {
			final byte[] ploygonBytes = nodeMap.get(id);
			final Polygon polygon = serializerHelper.loadFromByteArray(ploygonBytes);
			
			final BoundingBox polygonBoundingBox = polygon.getBoundingBox();
			
			if(polygonBoundingBox.overlaps(leftBox)) {
				statistics.increaseLeft();
			}
			
			if(polygonBoundingBox.overlaps(rightBox)) {
				statistics.increaseRight();
			}
			
			statistics.increaseTotal();
		}
		
		
		return statistics;
	}
	
	/**
	 * Take a certain number of samples and generate a split position
	 * @param sampleSize
	 * @return 
	 * @throws IOException 
	 * @throws ClassNotFoundException 
	 */
	protected double getSplit(final float sampleSize) throws ClassNotFoundException, IOException {
		final Set<Long> takenSamples = new HashSet<>();
		final Random random = new Random(System.currentTimeMillis());
		final List<BoundingBox> samples = new ArrayList<>();

		while(takenSamples.size() < sampleSize) {
			final long sampleId = Math.abs(random.nextLong()) % elementCounter;
			
			if(takenSamples.contains(sampleId)) {
				continue;
			}
			
			takenSamples.add(sampleId);
			final byte[] ploygonBytes = nodeMap.get(sampleId);
			
			final Polygon polygon = serializerHelper.loadFromByteArray(ploygonBytes);
			samples.add(polygon.getBoundingBox());
		}
		
		samples.sort((b1, b2) -> Double.compare(b1.getCoordinateLow(0), b2.getCoordinateLow(0)));
		
		return samples.get(samples.size() / 2).getCoordinateLow(0);
	}

	@Override
	public void processStructure(final Polygon geometricalStructure) {
		try {
			final byte[] data = serializerHelper.toByteArray(geometricalStructure);
			nodeMap.put(elementCounter++, data);
		} catch (IOException e) {
			logger.error("Got an exception during encoding", e);
		}
	}
	
	/**
	 *
	 * Main * Main * Main
	 * @throws IOException 
	 * 
	 */
	public static void main(final String[] args) throws IOException {
		final String filename = "/Users/kristofnidzwetzki/Downloads/berlin-latest.osm.pbf";

		final DetermineSamplingSize determineSamplingSize = new DetermineSamplingSize(filename);
		determineSamplingSize.run();
	}

}

class ExperimentSeriesStatistics {
	protected long minDiff = Long.MAX_VALUE;
	protected long maxDiff = Long.MIN_VALUE;
	protected long avgDiff = 0;
	protected int numberOfExperiments = 0;
	
	public void addExperiment(final ExperimentStatistics experimentStatistics) {
		minDiff = Math.min(minDiff, experimentStatistics.getDiff());
		maxDiff = Math.max(maxDiff, experimentStatistics.getDiff());
		avgDiff = avgDiff + experimentStatistics.getDiff();
		numberOfExperiments++;
	}
	
	public void printStatistics() {
		System.out.println("#Min Diff\tMax diff\tAvgDiff");
		System.out.format("%d\t%d\t%d\n", minDiff, maxDiff, avgDiff / numberOfExperiments);
	}
}

class ExperimentStatistics {
	protected long left = 0;
	protected long right = 0;
	protected long total = 0;
	
	public void increaseLeft() {
		left++;
	}
	
	public void increaseRight() {
		right++;
	}
	
	public void increaseTotal() {
		total++;
	}
	
	public static void printExperientHeader() {
		System.out.println("#Experiment\tTotal\tLeft\tRight\tDiff");
	}
	
	public void printExperimentResult(final int experiment) {
		final long diff = getDiff();
		System.out.format("%d\t%d\t%d\t%d\t%d\n", experiment, total, left, right, diff);
	}

	protected long getDiff() {
		return Math.abs(left - right);
	}

	@Override
	public String toString() {
		return String.format("Total %d, left %d, right %d, diff %d\n", total, left, right, getDiff());
	}
}
