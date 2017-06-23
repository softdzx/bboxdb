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
package org.bboxdb.storage.sstable.spatialindex;

import java.io.InputStream;
import java.util.List;

import org.bboxdb.storage.StorageManagerException;
import org.bboxdb.storage.entity.BoundingBox;

public interface SpatialIndexReader {
	
	/**
	 * Persist the index 
	 * 
	 * @param inputStream
	 */
	public void readFromStream(final InputStream inputStream) throws StorageManagerException, InterruptedException;
	
	
	/**
	 * Find the entries for the given region
	 * @param boundingBox
	 * @return
	 */
	public List<? extends SpatialIndexEntry> getEntriesForRegion(final BoundingBox boundingBox);
}
