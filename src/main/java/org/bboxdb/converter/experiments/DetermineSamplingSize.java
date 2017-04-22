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
package org.bboxdb.converter.experiments;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.bboxdb.converter.osm.util.Polygon;
import org.bboxdb.converter.osm.util.SerializerHelper;
import org.bboxdb.storage.entity.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DetermineSamplingSize implements Runnable {

	/**
	 * The file to import
	 */
	protected final String filename;

	/**
	 * The element counter
	 */
	protected long elementCounter;
	
	/**
	 * The node serializer
	 */
	protected final SerializerHelper<Polygon> serializerHelper = new SerializerHelper<>();

	/**
	 * The retry counter for the experiments
	 */
	public final static int EXPERIMENT_RETRY = 50;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(DetermineSamplingSize.class);
	
	public DetermineSamplingSize(final String filename) throws IOException {
		
		this.filename = filename;
		this.elementCounter = 0;
	}
	
	@Override
	public void run() {
		System.out.format("Reading %s\n", filename);
		
		final List<Double> sampleSizes = Arrays.asList(
				0.01d, 0.02d, 0.03d, 0.04d, 0.05d,
				0.06d, 0.07d, 0.08d, 0.09d,
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
		

		try {
			final long numberOfElements = Files.lines(Paths.get(filename)).count();
			final int numberOfSamples = (int) (numberOfElements / 100 * sampleSize);
			
			final ExperimentSeriesStatistics experimentSeriesStatistics = new ExperimentSeriesStatistics();
			
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

		try (
				final BufferedReader reader = new BufferedReader(new FileReader(new File(filename)));
			) {

		    String line;
		    while ((line = reader.readLine()) != null) {
		    	final Polygon polygon = Polygon.fromGeoJson(line);

				final BoundingBox polygonBoundingBox = polygon.getBoundingBox();
				
				if(polygonBoundingBox.overlaps(leftBox)) {
					statistics.increaseLeft();
				}
				
				if(polygonBoundingBox.overlaps(rightBox)) {
					statistics.increaseRight();
				}
				
				statistics.increaseTotal();
		    }
		} catch (IOException e) {
			logger.error("Error while processing data", e);
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
			
			try (final Stream<String> lines = Files.lines(Paths.get(filename))) {
			    final String line = lines.skip(sampleId - 1).findFirst().get();
				
				final Polygon polygon = Polygon.fromGeoJson(line);
				samples.add(polygon.getBoundingBox());
			}
		}
		
		samples.sort((b1, b2) -> Double.compare(b1.getCoordinateLow(0), b2.getCoordinateLow(0)));
		
		return samples.get(samples.size() / 2).getCoordinateLow(0);
	}
	
	/**
	 * Main * Main * Main
	 */
	public static void main(final String[] args) throws IOException {
		
		// Check parameter
		if(args.length != 2) {
			System.err.println("Usage: programm <filename>");
			System.exit(-1);
		}
		
		final String filename = args[0];
		
		// Check file
		final File inputFile = new File(filename);
		if(! inputFile.isFile()) {
			System.err.println("Unable to open file: " + filename);
			System.exit(-1);
		}
		
		final DetermineSamplingSize determineSamplingSize = new DetermineSamplingSize(filename);
		determineSamplingSize.run();
	}

}

class ExperimentSeriesStatistics {
	protected long minDiff = Long.MAX_VALUE;
	protected long maxDiff = Long.MIN_VALUE;
	protected long avgDiffAll = 0;
	protected long totalElements = 0;
	protected int numberOfExperiments = 0;
	
	public void addExperiment(final ExperimentStatistics experimentStatistics) {
		minDiff = Math.min(minDiff, experimentStatistics.getDiff());
		maxDiff = Math.max(maxDiff, experimentStatistics.getDiff());
		avgDiffAll = avgDiffAll + experimentStatistics.getDiff();
		totalElements = experimentStatistics.getTotal();
		numberOfExperiments++;
	}
	
	public void printStatistics() {
		System.out.println("#Min diff\tMax diff\tAvg diff\tMin diff(%f)\tMax diff(%)\tAvg diff(%)");
		final long avgDiff = avgDiffAll / numberOfExperiments;
		final double pAvgDiff = (avgDiff / (double) totalElements) * 100.0;
		final double pMaxDiff = (maxDiff / (double) totalElements) * 100.0;
		final double pMinDiff = (minDiff / (double) totalElements) * 100.0;
		
		System.out.format("%d\t%d\t%d\t%f\t%f\t%f\n", minDiff, maxDiff, avgDiff, pMinDiff, pMaxDiff, pAvgDiff);
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
		System.out.println("#Experiment\tTotal\tLeft\tRight\tDiff\t% diff");
	}
	
	public void printExperimentResult(final int experiment) {
		final long diff = getDiff();
		final double pDiff = ((left - right) / (double) total) * 100.0;
		System.out.format("%d\t%d\t%d\t%d\t%d\t%f\n", experiment, total, left, right, diff, pDiff);
	}

	protected long getDiff() {
		return Math.abs(left - right);
	}
	
	public long getTotal() {
		return total;
	}

	@Override
	public String toString() {
		return String.format("Total %d, left %d, right %d, diff %d\n", total, left, right, getDiff());
	}
}