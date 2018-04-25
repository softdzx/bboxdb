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
package org.bboxdb.storage;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import org.bboxdb.commons.io.FileUtil;
import org.bboxdb.commons.math.BoundingBox;
import org.bboxdb.storage.entity.DeletedTuple;
import org.bboxdb.storage.entity.Tuple;
import org.bboxdb.storage.util.TupleHelper;
import org.bboxdb.storage.wal.WalReader;
import org.bboxdb.storage.wal.WalWriter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.io.Files;

public class TestWAL {
	
	/**
	 * The temp dir
	 */
	private File tempDir;
	
	@Before
	public void before() {
		tempDir = Files.createTempDir();
	}

	@After
	public void after() {
		if(tempDir != null) {
			FileUtil.deleteRecursive(tempDir.toPath());
		}
	}
	
	@Test(expected=IOException.class)
	public void testReadNonExstingFile() throws IOException, StorageManagerException {
		final File file = new File(tempDir + File.separator + "file");
		final WalReader reader = new WalReader(file);
		reader.close();
		
		// Should not happen
		Assert.assertFalse(true);
	}
	
	@Test
	public void testWriteReadNoTuple() throws IOException, StorageManagerException {
		final WalWriter walWriter = new WalWriter(tempDir, 1);
		walWriter.close();
		
		final File writtenFile = walWriter.getFile();
		Assert.assertTrue(writtenFile.exists());

		final WalReader reader = new WalReader(writtenFile);
		
		final List<Tuple> myList = Lists.newArrayList(reader.iterator());
		Assert.assertEquals(0, myList.size());
		
		reader.close();
	}
	
	@Test
	public void testWriteReadOneTuple() throws IOException, StorageManagerException {
		final WalWriter walWriter = new WalWriter(tempDir, 1);
		walWriter.addTuple(new Tuple("abc", new BoundingBox(1d, 2d), "".getBytes()));
		walWriter.close();
		
		final File writtenFile = walWriter.getFile();
		Assert.assertTrue(writtenFile.exists());

		final WalReader reader = new WalReader(writtenFile);
		
		final List<Tuple> myList = Lists.newArrayList(reader.iterator());
		Assert.assertEquals(1, myList.size());
		
		reader.close();
	}
	
	@Test
	public void testWriteReadTwoTuple() throws IOException, StorageManagerException {
		final WalWriter walWriter = new WalWriter(tempDir, 1);
		walWriter.addTuple(new Tuple("abc", new BoundingBox(1d, 2d), "".getBytes()));
		walWriter.addTuple(new DeletedTuple("abc"));

		walWriter.close();
		
		final File writtenFile = walWriter.getFile();
		Assert.assertTrue(writtenFile.exists());

		final WalReader reader = new WalReader(writtenFile);
		
		final List<Tuple> myList = Lists.newArrayList(reader.iterator());
		Assert.assertEquals(2, myList.size());
		
		reader.close();
	}
	
	@Test
	public void testWriteReadDefectiveTuple() throws IOException, StorageManagerException {
		final WalWriter walWriter = new WalWriter(tempDir, 1);
		walWriter.addTuple(new Tuple("abc", new BoundingBox(1d, 2d), "".getBytes()));
		walWriter.addTuple(new DeletedTuple("abc"));

		walWriter.close();
		
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		TupleHelper.writeTupleToStream(new Tuple("abc", new BoundingBox(1d, 2d), "".getBytes()), bos);
		bos.close();
		final byte[] bytes = bos.toByteArray();
		
		// Write last tuple only partial
		final File file = walWriter.getFile();
		final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(file, true));
		os.write(bytes, 0, bytes.length - 2);
		os.close();
		
		final File writtenFile = file;
		Assert.assertTrue(writtenFile.exists());

		final WalReader reader = new WalReader(writtenFile);
		
		final List<Tuple> myList = Lists.newArrayList(reader.iterator());
		Assert.assertEquals(2, myList.size());
		
		reader.close();
	}
	
	@Test(expected=StorageManagerException.class)
	public void testWriteReadNoMagic() throws IOException, StorageManagerException {
		final File writtenFile = new File(tempDir + File.separator + "test");
		
		final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(writtenFile));
		TupleHelper.writeTupleToStream(new Tuple("abc", new BoundingBox(1d, 2d), "".getBytes()), os);
		os.close();

		final WalReader reader = new WalReader(writtenFile);
		
		Lists.newArrayList(reader.iterator());
		
		// Shoud not happen
		Assert.assertFalse(true);
		
		reader.close();
	}
}