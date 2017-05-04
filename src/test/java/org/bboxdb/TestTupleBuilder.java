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
package org.bboxdb;

import org.bboxdb.storage.entity.BoundingBox;
import org.bboxdb.storage.entity.Tuple;
import org.bboxdb.tools.converter.tuple.TupleBuilder;
import org.bboxdb.tools.converter.tuple.TupleBuilderFactory;
import org.junit.Assert;
import org.junit.Test;


public class TestTupleBuilder {

	/**
	 * Test the geo json tuple builder
	 */
	@Test
	public void testGeoJsonTupleBuilder() {
		final String testLine = "{\"geometry\":{\"coordinates\":[52.4688608,13.3327994],\"type\":\"Point\"},\"id\":271247324,\"type\":\"Feature\",\"properties\":{\"natural\":\"tree\",\"leaf_cycle\":\"deciduous\",\"name\":\"Kaisereiche\",\"leaf_type\":\"broadleaved\",\"wikipedia\":\"de:Kaisereiche (Berlin)\"}}";
	
		final TupleBuilder tupleBuilder = TupleBuilderFactory.getBuilderForFormat("geojson");
		
		final Tuple tuple = tupleBuilder.buildTuple("1", testLine);
		
		Assert.assertTrue(tuple != null);
		Assert.assertEquals(Integer.toString(1), tuple.getKey());
		final BoundingBox expectedBox = new BoundingBox(52.4688608, 52.4688608, 13.3327994, 13.3327994);
		Assert.assertEquals(expectedBox, tuple.getBoundingBox());
	}

	/**
	 * Test the geo json tuple builder
	 */
	@Test
	public void testYellowTaxiTupleBuilder() {
		final String testLine = "2,2016-01-01 00:00:00,2016-01-01 00:00:00,2,1.10,-73.990371704101563,40.734695434570313,1,N,-73.981842041015625,40.732406616210937,2,7.5,0.5,0.5,0,0,0.3,8.8";
	
		final TupleBuilder tupleBuilder = TupleBuilderFactory.getBuilderForFormat("yellowtaxi");
		
		final Tuple tuple = tupleBuilder.buildTuple("1", testLine);
				
		Assert.assertTrue(tuple != null);
		Assert.assertEquals(Integer.toString(1), tuple.getKey());
		
		final BoundingBox exptectedBox = new BoundingBox(-73.990371704101563, 40.734695434570313, 
				-73.981842041015625, 40.732406616210937, 1451602800000d, 1451602800000d);
		
		Assert.assertEquals(exptectedBox, tuple.getBoundingBox());
	}
}
