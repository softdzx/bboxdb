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
package org.bboxdb.storage.sstable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.Collectors;

import org.bboxdb.storage.BloomFilterBuilder;
import org.bboxdb.storage.StorageManagerException;
import org.bboxdb.storage.entity.SSTableName;
import org.bboxdb.storage.entity.SStableMetaData;
import org.bboxdb.storage.entity.Tuple;
import org.bboxdb.storage.sstable.spatialindex.SpatialIndex;
import org.bboxdb.storage.sstable.spatialindex.SpatialIndexEntry;
import org.bboxdb.storage.sstable.spatialindex.SpatialIndexFactory;
import org.bboxdb.util.DataEncoderHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.BloomFilter;
import com.google.common.io.CountingOutputStream;

public class SSTableWriter implements AutoCloseable {
	
	/**
	 * The number of the table
	 */
	protected final int tablenumber;
	
	/**
	 * The name of the table
	 */
	protected final SSTableName name;
	
	/**
	 * The directory for the SSTables
	 */
	protected final String directory;
	
	/**
	 * SSTable output stream
	 */
	protected CountingOutputStream sstableOutputStream;
	
	/**
	 * SSTable index stream
	 */
	protected OutputStream sstableIndexOutputStream;
	
	/**
	 * The SSTable file object
	 */
	protected File sstableFile;
	
	/**
	 * The SSTable index file object
	 */
	protected File sstableIndexFile;
	
	/**
	 * The bloom filter file
	 */
	protected File sstableBloomFilterFile;
	
	/**
	 * The spatial index file
	 */
	protected File spatialIndexFile;
	
	/**
	 * The meta data file
	 */
	protected File metadatafile;
	
	/**
	 * A counter for the written tuples
	 */
	protected final SSTableMetadataBuilder metadataBuilder;
	
	/**
	 * The bloom filter
	 */
	protected final BloomFilter<String> bloomFilter;
	
	/**
	 * The spatial index
	 */
	protected final SpatialIndex spatialIndex;
	
	/**
	 * The error flag
	 */
	protected boolean exceptionDuringWrite;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(SSTableWriter.class);
	
	public SSTableWriter(final String directory, final SSTableName name, 
			final int tablenumber, final long estimatedNumberOfTuples) {
		
		this.directory = directory;
		this.name = name;
		this.tablenumber = tablenumber;		
		this.metadataBuilder = new SSTableMetadataBuilder();
		this.exceptionDuringWrite = false;
		
		// Bloom Filter
		final String sstableBloomFilterFilename = SSTableHelper.getSSTableBloomFilterFilename(directory, name, tablenumber);
		this.sstableBloomFilterFile = new File(sstableBloomFilterFilename);
		this.bloomFilter = BloomFilterBuilder.buildBloomFilter(estimatedNumberOfTuples);
		
		// Spatial index
		final String spatialIndexFilename =  SSTableHelper.getSSTableSpatialIndexFilename(directory, name, tablenumber);
		this.spatialIndexFile = new File(spatialIndexFilename);
		this.spatialIndex = SpatialIndexFactory.getInstance();
		
		// Metadata
		final String ssTableMetadataFilename = SSTableHelper.getSSTableMetadataFilename(directory, name, tablenumber);
		this.metadatafile = new File(ssTableMetadataFilename);
	}
	
	/**
	 * Open all required files
	 * @throws StorageManagerException
	 */
	public void open() throws StorageManagerException {
		final String directoryName = SSTableHelper.getSSTableDir(directory, name);
		final File directoryHandle = new File(directoryName);
		
		if(! directoryHandle.isDirectory()) {
			final String error = "Directory for SSTable " + name + " does not exist: " + directoryName;
			logger.error(error);
			throw new StorageManagerException(error);
		}
		
		final String sstableOutputFileName = SSTableHelper.getSSTableFilename(directory, name, tablenumber);
		sstableFile = new File(sstableOutputFileName);
		
		final String outputIndexFileName = SSTableHelper.getSSTableIndexFilename(directory, name, tablenumber);
		sstableIndexFile = new File(outputIndexFileName);
		
		// Don't overwrite old data
		if(sstableFile.exists()) {
			throw new StorageManagerException("Table file already exists: " + sstableOutputFileName);
		}
		
		if(sstableIndexFile.exists()) {
			throw new StorageManagerException("Table file already exists: " + sstableIndexFile);
		}
		
		if(sstableBloomFilterFile.exists()) {
			throw new StorageManagerException("Bloom filter file already exists: " + sstableBloomFilterFile);
		}
		
		try {
			logger.info("Writing new SSTable for relation: {} file: {}", name.getFullname(), sstableOutputFileName);
			final BufferedOutputStream sstableFileOutputStream = new BufferedOutputStream(new FileOutputStream(sstableFile));
			sstableOutputStream = new CountingOutputStream(sstableFileOutputStream);
			sstableOutputStream.write(SSTableConst.MAGIC_BYTES);
			
			sstableIndexOutputStream = new BufferedOutputStream(new FileOutputStream(sstableIndexFile));
			sstableIndexOutputStream.write(SSTableConst.MAGIC_BYTES_INDEX);
		} catch (FileNotFoundException e) {
			exceptionDuringWrite = true;
			throw new StorageManagerException("Unable to open output file", e);
		} catch (IOException e) {
			exceptionDuringWrite = true;
			throw new StorageManagerException("Unable to write into output file", e);
		}
	}
	
	/**
	 * Close all open file handles and write the meta data
	 */
	public void close() throws StorageManagerException {
		try {			
			logger.debug("Closing new written SSTable for relation: {} number {}. File: {} ", 
					name.getFullname(), tablenumber, sstableFile.getName());

			if(sstableOutputStream != null) {
				sstableOutputStream.close();
				sstableOutputStream = null;
			}
			
			if(sstableIndexOutputStream != null) {
				sstableIndexOutputStream.close();
				sstableIndexOutputStream = null;
			}
			
			writeSpatialIndex();
			writeBloomFilter();
			writeMetadata();
			
		} catch (IOException e) {
			exceptionDuringWrite = true;
			throw new StorageManagerException("Exception while closing streams", e);
		} finally {
			handleWriteException();
		}
	}

	/**
	 *  Delete half written files if an exception has occurred
	 *  The variable exceptionDuringWrite is set to true in every catch block
	 */
	protected void handleWriteException() {
		if(exceptionDuringWrite == true) {
			if(sstableFile != null && sstableFile.exists()) {
				sstableFile.delete();
			}
			
			if(sstableIndexFile != null && sstableIndexFile.exists()) {
				sstableIndexFile.delete();
			}
			
			if(sstableBloomFilterFile != null && sstableBloomFilterFile.exists()) {
				sstableBloomFilterFile.delete();
			}
			
			if(spatialIndexFile != null && spatialIndexFile.exists()) {
				spatialIndexFile.delete();
			}
			
			if(metadatafile != null && metadatafile.exists()) {
				metadatafile.delete();
			}
		}
	}
	
	/**
	 * Write the spatial index to file
	 * @throws IOException
	 * @throws StorageManagerException 
	 */
	protected void writeSpatialIndex() throws IOException, StorageManagerException {
		try (   final FileOutputStream fos = new FileOutputStream(spatialIndexFile);
				final OutputStream outputStream = new BufferedOutputStream(fos);
			) {
			spatialIndex.writeToStream(outputStream);
			outputStream.close();
		}
	}
	
	/**
	 * Write the bloom filter into the filter file
	 * @throws IOException
	 */
	protected void writeBloomFilter() throws IOException {
		
		try (   final FileOutputStream fos = new FileOutputStream(sstableBloomFilterFile);
				final OutputStream outputStream = new BufferedOutputStream(fos);
			) {
			
			bloomFilter.writeTo(outputStream);
			outputStream.close();
		}
	}
	
	/**
	 * Write the meta data to yaml info file
	 * @throws IOException
	 */
	protected void writeMetadata() throws IOException {
		final SStableMetaData metadata = metadataBuilder.getMetaData();
		metadata.exportToYamlFile(metadatafile);
	}
	
	/**
	 * Add the list of tuples to the sstable
	 * @param tuples
	 * @throws StorageManagerException
	 */
	public void addData(final List<Tuple> tuples) throws StorageManagerException {
		if(sstableOutputStream == null) {
			final String error = "Trying to add a memtable to a non ready SSTable writer";
			logger.error(error);
			throw new StorageManagerException(error);
		}
		
		final List<SpatialIndexEntry> indexEntries = tuples
				.stream()
				.map(t -> new SpatialIndexEntry(t.getKey(), t.getBoundingBox()))
				.collect(Collectors.toList());
		
		spatialIndex.bulkInsert(indexEntries);
		
		try {
			for(final Tuple tuple : tuples) {
				addNextTuple(tuple);
			}
		} catch(StorageManagerException e) {
			exceptionDuringWrite = true;
			throw e;
		}
	}

	/**
	 * Add the next tuple into the result sstable
	 * @param tuple
	 * @throws IOException
	 * @throws StorageManagerException 
	 */
	public void addNextTuple(final Tuple tuple) throws StorageManagerException {
		try {
			// Add Tuple to the index
			final long tuplePosition = sstableOutputStream.getCount();
			writeIndexEntry((int) tuplePosition);
			
			// Add Tuple to the SSTable file
			writeTupleToFile(tuple);
			metadataBuilder.addTuple(tuple);
			
			// Add tuple to the bloom filter
			bloomFilter.put(tuple.getKey());
			
			// Add tuple to the spatial index
			final SpatialIndexEntry sIndexentry 
				= new SpatialIndexEntry(tuple.getKey(), tuple.getBoundingBox());
			spatialIndex.insert(sIndexentry);
		} catch (IOException e) {
			exceptionDuringWrite = true;
			throw new StorageManagerException("Unable to write tuple to SSTable", e);
		}
	}

	/**
	 * Write the given tuple into the SSTable
	 * 
	 * Format of a data record:
	 * 
	 * +----------------------------------------------------------------------------------------------+
	 * | Key-Length | BBox-Length | Data-Length |  Version  |  Insert   |   Key   |  BBox   |   Data  |
	 * |            |             |             | Timestamp | Timestamp |         |         |         |
	 * |   2 Byte   |   4 Byte    |   4 Byte    |  8 Byte   |  8 Byte   |  n Byte |  n Byte |  n Byte |
	 * +----------------------------------------------------------------------------------------------+
	 * 
	 * 
	 * @param tuple
	 * @throws IOException
	 */
	protected void writeTupleToFile(final Tuple tuple) throws IOException {
		final byte[] keyBytes = tuple.getKey().getBytes();
		final ByteBuffer keyLengthBytes = DataEncoderHelper.shortToByteBuffer((short) keyBytes.length);

		final byte[] boundingBoxBytes = tuple.getBoundingBoxBytes();
		final byte[] data = tuple.getDataBytes();
		
		final ByteBuffer boxLengthBytes = DataEncoderHelper.intToByteBuffer(boundingBoxBytes.length);
		final ByteBuffer dataLengthBytes = DataEncoderHelper.intToByteBuffer(data.length);
	    final ByteBuffer versionTimestampBytes = DataEncoderHelper.longToByteBuffer(tuple.getVersionTimestamp());
	    final ByteBuffer receivedTimestampBytes = DataEncoderHelper.longToByteBuffer(tuple.getReceivedTimestamp());

	    sstableOutputStream.write(keyLengthBytes.array());
		sstableOutputStream.write(boxLengthBytes.array());
		sstableOutputStream.write(dataLengthBytes.array());
		sstableOutputStream.write(versionTimestampBytes.array());
		sstableOutputStream.write(receivedTimestampBytes.array());
		sstableOutputStream.write(keyBytes);
		sstableOutputStream.write(boundingBoxBytes);
		sstableOutputStream.write(data);
	}

	/** 
	 * Append an entry to the index file.
	 * 
	 * Format of the index file:
	 * 
	 * -------------------------------------------------
	 * | Tuple-Position | Tuple-Position |  .........  |
 	 * |     4 Byte     |     4 Byte     |  .........  |
	 * -------------------------------------------------
	 * 
	 * @param keyLengthBytes
	 * @param keyPosition
	 * @throws IOException
	 */
	protected void writeIndexEntry(int tuplePosition) throws IOException {
		final ByteBuffer tuplePositionBytes = DataEncoderHelper.intToByteBuffer(tuplePosition);
		sstableIndexOutputStream.write(tuplePositionBytes.array());
	}

	/**
	 * Get the sstable output file
	 * @return
	 */
	public File getSstableFile() {
		return sstableFile;
	}

	/**
	 * Get the sstable index output file
	 * @return
	 */
	public File getSstableIndexFile() {
		return sstableIndexFile;
	}
	
	/**
	 * Does an exception during a write operation occurred?
	 * @return
	 */
	public boolean isErrorFlagSet() {
		return exceptionDuringWrite;
	}
	
	/**
	 * Get the already written bytes for this SSTable
	 * @return
	 */
	public long getWrittenBytes() {
		return sstableOutputStream.getCount();
	}
	
	/**
	 * Get the sstable name
	 */
	public SSTableName getName() {
		return name;
	}
	
	/**
	 * Get the tablenumber
	 * @return
	 */
	public int getTablenumber() {
		return tablenumber;
	}
	
	/**
	 * Get the directory
	 * @return
	 */
	public String getDirectory() {
		return directory;
	}
	
}
