/*******************************************************************************
 *
 *    Copyright (C) 2015-2018 the BBoxDB project
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
package org.bboxdb.tools.demo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.bboxdb.network.client.BBoxDBCluster;
import org.bboxdb.network.client.BBoxDBException;
import org.bboxdb.network.client.future.EmptyResultFuture;
import org.bboxdb.network.client.tools.FixedSizeFutureStore;
import org.bboxdb.storage.entity.DistributionGroupConfiguration;
import org.bboxdb.storage.entity.DistributionGroupConfigurationBuilder;
import org.bboxdb.storage.entity.Tuple;
import org.bboxdb.storage.entity.TupleStoreConfiguration;
import org.bboxdb.storage.entity.TupleStoreConfigurationBuilder;
import org.bboxdb.tools.converter.tuple.GeoJSONTupleBuilder;
import org.bboxdb.tools.converter.tuple.TupleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataRedistributionLoader implements Runnable {
	
	/**
	 * The files to load
	 */
	private final String[] files;
	
	/**
	 * The BBoxDB cluster connection
	 */
	private BBoxDBCluster bboxDBCluster;
	
	/**
	 * The pending futures
	 */
	private final FixedSizeFutureStore pendingFutures;
	
	/**
	 * The loaded files
	 */
	private final Set<String> loadedFiles;
	
	/**
	 * The GEOJSON tuple builder
	 */
	private final TupleBuilder tupleBuilder;
	
	/**
	 * The random generator
	 */
	private final Random random;
	
	/**
	 * The amount of pending insert futures
	 */
	private final static int MAX_PENDING_FUTURES = 5000;
	
	/**
	 * The name of the distribution group
	 */
	private final static String DGROUP = "demogroup";
	
	/**
	 * The name of the data table
	 */
	private final static String TABLE = DGROUP + "_osmtable";
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(DataRedistributionLoader.class);

	public DataRedistributionLoader(final String files, final BBoxDBCluster bboxDBCluster) {
		this.bboxDBCluster = bboxDBCluster;
		this.loadedFiles = new HashSet<>();
		this.pendingFutures = new FixedSizeFutureStore(MAX_PENDING_FUTURES);
		this.files = files.split(":");
		this.tupleBuilder = new GeoJSONTupleBuilder();
		this.random = new Random();
		
		// Log failed futures
		pendingFutures.addFailedFutureCallback(
				(f) -> logger.error("Failed future detected: {} / {}", f, f.getAllMessages()
		));
	}

	/**
	 * Execute the loader
	 */
	@Override
	public void run() {
		checkFilesExist();
		initBBoxDB();
		
		try {
			while(true) {
				
				if(loadedFiles.size() == 0) {
					// No files loaded, load one
					loadFile(1);
				} else if(loadedFiles.size() == files.length) {
					// All files loaded, remove one
					deleteFile(1);
				} else {
					final int loadOrDelete = random.nextInt(10);
					final int fileid = random.nextInt(files.length);

					if(loadOrDelete > 5) {
						// Load file
						loadFile(fileid);
					} else {
						// Delete file
						deleteFile(fileid);
					}
				}
			}
		} catch (InterruptedException e) {
			logger.error("Got exception while running demo class", e);
		}
	}
	
	/**
	 * Re-Create the distribution group and the table
	 */
	private void initBBoxDB() {
		try {
			// Delete old distribution group
			System.out.println("Delete old distribution group");
			final EmptyResultFuture dgroupDeleteResult = bboxDBCluster.deleteDistributionGroup(DGROUP);
			dgroupDeleteResult.waitForAll();
			
			if(dgroupDeleteResult.isFailed()) {
				System.err.println(dgroupDeleteResult.getAllMessages());
				System.exit(-1);
			}
			
			// Create new distribution group
			System.out.println("Create new distribution group");
			final DistributionGroupConfiguration dgroupConfig 
				= DistributionGroupConfigurationBuilder.create(2).withReplicationFactor((short) 1).build();
			
			final EmptyResultFuture dgroupCreateResult = bboxDBCluster.createDistributionGroup(DGROUP, dgroupConfig);
			dgroupCreateResult.waitForAll();
			
			if(dgroupCreateResult.isFailed()) {
				System.err.println(dgroupCreateResult.getAllMessages());
				System.exit(-1);
			}
			
			// Create new table
			System.out.println("Create new table");
			final TupleStoreConfiguration storeConfiguration 
				= TupleStoreConfigurationBuilder.create().allowDuplicates(false).build();
			
			final EmptyResultFuture tableCreateResult = bboxDBCluster.createTable(TABLE, storeConfiguration);
			tableCreateResult.waitForAll();
			
			if(tableCreateResult.isFailed()) {
				System.err.println(tableCreateResult.getAllMessages());
				System.exit(-1);
			}
		} catch (Exception e) {
			System.err.println("Got an exception while prepating BBoxDB");
			e.printStackTrace();
			System.exit(-1);
		} 
	}

	/**
	 * Load the given file
	 * @param id
	 * @throws InterruptedException 
	 */
	private void loadFile(final int fileid) throws InterruptedException {
		final String filename = files[fileid];
		
		if(loadedFiles.contains(filename)) {
			System.err.println("File " + filename + " is already loaded");
			return;
		}
		
		System.out.println("Loading content from: " + filename);
		final AtomicInteger lineNumber = new AtomicInteger(0);
		final String prefix = Integer.toString(fileid) + "_";
		
		try(final Stream<String> lines = Files.lines(Paths.get(filename))) {
			lines.forEach(l -> {
				final String key = prefix + lineNumber.getAndIncrement();
				final Tuple tuple = tupleBuilder.buildTuple(key, l);
				
				try {
					final EmptyResultFuture insertFuture = bboxDBCluster.insertTuple(TABLE, tuple);
					pendingFutures.put(insertFuture);
				} catch (BBoxDBException e) {
					logger.error("Got error while inserting tuple", e);
				}
				
				if(lineNumber.get() % 1000 == 0) {
					System.out.format("Loaded %d elements\n", lineNumber.get());
				}
			});
		} catch (IOException e) {
			System.err.println("Got an exeption while reading file: " + e);
			System.exit(-1);
		}
		
		pendingFutures.waitForCompletion();
	
		loadedFiles.add(filename);
	}
	
	/**
	 * Remove the given file
	 * @param fileid
	 * @throws InterruptedException 
	 */
	private void deleteFile(final int fileid) throws InterruptedException {
		final String filename = files[fileid];
		
		if(! loadedFiles.contains(filename)) {
			System.err.println("File " + filename + " is not loaded");
			return;
		}
		
		System.out.println("Removing content from: " + filename);
		
		final AtomicInteger lineNumber = new AtomicInteger(0);
		final String prefix = Integer.toString(fileid) + "_";
		
		try(final Stream<String> lines = Files.lines(Paths.get(filename))) {
			lines.forEach(l -> {
				final String key = prefix + lineNumber.getAndIncrement();
				try {
					final EmptyResultFuture resultFuture = bboxDBCluster.deleteTuple(TABLE, key);
					pendingFutures.put(resultFuture);
					
					if(lineNumber.get() % 1000 == 0) {
						System.out.format("Deleted %d elements\n", lineNumber.get());
					}
				} catch (BBoxDBException e) {
					logger.error("Got error while deleting tuple", e);
				}
			});
		} catch (IOException e) {
			System.err.println("Got an exeption while reading file: " + e);
			System.exit(-1);
		}
		
		pendingFutures.waitForCompletion();
		
		loadedFiles.remove(filename);
	}

	/**
	 * Are the files readable?
	 */
	private void checkFilesExist() {
		for(final String filename : files) {
			final File file = new File(filename);
			if(! file.exists()) {
				System.err.println("Unable to open file: " + filename);
				System.exit(-1);
			}
		}
	}

	/**
	 * Main Main Main Main Main Main
	 * @param args
	 */
	public static void main(final String[] args) {
		if(args.length != 3) {
			System.err.println("Usage: <Class> <File1>:<File2>:<FileN> <ZookeeperEndpoint> <Clustername>");
			System.exit(-1);
		}
		
		final String zookeeperEndpoint = args[1];
		final String clustername = args[2];
		
		final BBoxDBCluster bboxDBCluster = new BBoxDBCluster(zookeeperEndpoint, clustername);
		bboxDBCluster.connect();
		
		if(! bboxDBCluster.isConnected()) {
			System.err.println("Unable to connect to zookeeper at: " + zookeeperEndpoint);
			System.exit(-1);
		}
		
		final DataRedistributionLoader dataRedistributionLoader = new DataRedistributionLoader(args[0], bboxDBCluster);
		dataRedistributionLoader.run();
	}
}