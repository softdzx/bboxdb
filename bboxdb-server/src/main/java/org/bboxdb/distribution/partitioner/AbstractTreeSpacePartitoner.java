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
package org.bboxdb.distribution.partitioner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.bboxdb.commons.math.BoundingBox;
import org.bboxdb.distribution.region.DistributionRegion;
import org.bboxdb.distribution.region.DistributionRegionSyncerHelper;
import org.bboxdb.distribution.zookeeper.ZookeeperClientFactory;
import org.bboxdb.distribution.zookeeper.ZookeeperException;
import org.bboxdb.distribution.zookeeper.ZookeeperNodeNames;
import org.bboxdb.misc.BBoxDBException;
import org.bboxdb.storage.entity.DistributionGroupConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractTreeSpacePartitoner extends AbstractSpacePartitioner {

	/**
	 * The logger
	 */
	final static Logger logger = LoggerFactory.getLogger(AbstractTreeSpacePartitoner.class);

	
	@Override
	public void createRootNode(final DistributionGroupConfiguration configuration) throws BBoxDBException {
		try {
			final String distributionGroup 
				= spacePartitionerContext.getDistributionGroupName().getFullname();
			
			final String rootPath = 
					distributionGroupZookeeperAdapter.getDistributionGroupRootElementPath(distributionGroup);
			
			zookeeperClient.createDirectoryStructureRecursive(rootPath);
			
			final int nameprefix = distributionGroupZookeeperAdapter
					.getNextTableIdForDistributionGroup(distributionGroup);
						
			zookeeperClient.createPersistentNode(rootPath + "/" + ZookeeperNodeNames.NAME_NAMEPREFIX, 
					Integer.toString(nameprefix).getBytes());
			
			zookeeperClient.createPersistentNode(rootPath + "/" + ZookeeperNodeNames.NAME_SYSTEMS, 
					"".getBytes());
					
			distributionGroupZookeeperAdapter.setBoundingBoxForPath(rootPath, 
					BoundingBox.createFullCoveringDimensionBoundingBox(configuration.getDimensions()));

			zookeeperClient.createPersistentNode(rootPath + "/" + ZookeeperNodeNames.NAME_REGION_STATE, 
					DistributionRegionState.ACTIVE.getStringValue().getBytes());		
			
			distributionGroupZookeeperAdapter.markNodeMutationAsComplete(rootPath);
		} catch (ZookeeperException e) {
			throw new BBoxDBException(e);
		}
	}
	
	@Override
	public void splitFailed(final DistributionRegion region) throws BBoxDBException {
		try {
			distributionGroupZookeeperAdapter.setStateForDistributionRegion(region, 
					DistributionRegionState.ACTIVE);
		} catch (ZookeeperException e) {
			throw new BBoxDBException(e);
		}
	}

	@Override
	public void mergeFailed(final DistributionRegion regionToMerge) throws BBoxDBException {
		try {
			distributionGroupZookeeperAdapter.setStateForDistributionRegion(regionToMerge, 
					DistributionRegionState.ACTIVE);
			
			for(final DistributionRegion childRegion : regionToMerge.getDirectChildren()) {
				distributionGroupZookeeperAdapter.setStateForDistributionRegion(childRegion, 
						DistributionRegionState.ACTIVE);
				
			}
		} catch (ZookeeperException e) {
			throw new BBoxDBException(e);
		}
	}
	
	@Override
	public void mergeComplete(final DistributionRegion regionToMerge) throws BBoxDBException {
		try {
			final List<DistributionRegion> childRegions = regionToMerge.getDirectChildren();
			
			for(final DistributionRegion childRegion : childRegions) {
				logger.info("Merge done deleting: {}", childRegion.getIdentifier());
				distributionGroupZookeeperAdapter.deleteChild(childRegion);
			}
		} catch (ZookeeperException e) {
			throw new BBoxDBException(e);
		}
	}
	
	@Override
	public void prepareMerge(final DistributionRegion regionToMerge) throws BBoxDBException {
		
		try {
			logger.debug("Merging region: {}", regionToMerge);
			final String zookeeperPath = distributionGroupZookeeperAdapter
					.getZookeeperPathForDistributionRegion(regionToMerge);
			
			distributionGroupZookeeperAdapter.setStateForDistributionGroup(zookeeperPath, 
					DistributionRegionState.ACTIVE);

			final List<DistributionRegion> childRegions = regionToMerge.getDirectChildren();
			
			for(final DistributionRegion childRegion : childRegions) {
				final String zookeeperPathChild = distributionGroupZookeeperAdapter
						.getZookeeperPathForDistributionRegion(childRegion);
				
				distributionGroupZookeeperAdapter.setStateForDistributionGroup(zookeeperPathChild, 
					DistributionRegionState.MERGING);
			}
			
		} catch (ZookeeperException e) {
			throw new BBoxDBException(e);
		}
	}
	
	@Override
	public void splitComplete(DistributionRegion regionToSplit) throws BBoxDBException {
		
		try {
			final DistributionGroupZookeeperAdapter zookeperAdapter 
				= ZookeeperClientFactory.getDistributionGroupAdapter();
			zookeperAdapter.setStateForDistributionRegion(regionToSplit, DistributionRegionState.SPLIT);
			
			// Children are ready
			for(final DistributionRegion childRegion : regionToSplit.getDirectChildren()) {
				zookeperAdapter.setStateForDistributionRegion(childRegion, DistributionRegionState.ACTIVE);
			}
		} catch (Exception e) {
			throw new BBoxDBException(e);
		} 
	}

	/**
	 * Wait for zookeeper split callback
	 * @param regionToSplit
	 */
	protected void waitUntilChildrenAreCreated(final DistributionRegion regionToSplit, 
			final int noOfChildren) {
		
		final Predicate<DistributionRegion> predicate = (r) -> r.getDirectChildren().size() == noOfChildren;
		DistributionRegionSyncerHelper.waitForPredicate(predicate, regionToSplit, distributionRegionSyncer);		
	}

	/**
	 * Wait for zookeeper split callback
	 * @param regionToSplit
	 */
	protected void waitForSplitCompleteZookeeperCallback(final DistributionRegion regionToSplit, 
			final int noOfChildren) {
		
		final Predicate<DistributionRegion> predicate = (r) -> isSplitForNodeComplete(r, noOfChildren);
		DistributionRegionSyncerHelper.waitForPredicate(predicate, regionToSplit, distributionRegionSyncer);
	}

	/**
	 * Is the split for the given node complete?
	 * @param region
	 * @param noOfChildren 
	 * @return
	 */
	protected boolean isSplitForNodeComplete(final DistributionRegion region, final int noOfChildren) {
		
		if(region.getDirectChildren().size() != noOfChildren) {
			return false;
		}
		
		final boolean unreadyChild = region.getDirectChildren().stream()
			.anyMatch(r -> r.getState() != DistributionRegionState.ACTIVE);
		
		return ! unreadyChild;
	}
	
	/**
	 * Set children to active and wait
	 * @param numberOfChilden2 
	 */
	protected void setStateToRedistributionActiveAndWait(final DistributionRegion regionToSplit, final int numberOfChilden) 
			throws ZookeeperException {
		
		// update state
		for (final DistributionRegion region : regionToSplit.getAllChildren()) {
			final String childPath 
				= distributionGroupZookeeperAdapter.getZookeeperPathForDistributionRegion(region);

			distributionGroupZookeeperAdapter.setStateForDistributionGroup(childPath, 
					DistributionRegionState.REDISTRIBUTION_ACTIVE);
		}
		
		waitForSplitCompleteZookeeperCallback(regionToSplit, numberOfChilden);
	}
	

	@Override
	public List<List<DistributionRegion>> getMergingCandidates(final DistributionRegion distributionRegion) {
		
		final List<List<DistributionRegion>> result = new ArrayList<>();
		
		if(distributionRegion.getState() != DistributionRegionState.SPLIT) {
			return result;
		}
		
		final List<DistributionRegion> allChildren = distributionRegion.getAllChildren();
		final List<DistributionRegion> directChildren = distributionRegion.getDirectChildren();
		
		// Do we have only direct children?
		if(allChildren.size() == directChildren.size()) {
			result.add(directChildren);
		}
		
		return result;
	}
	
	@Override
	public boolean isSplittingSupported(final DistributionRegion distributionRegion) {
		return distributionRegion.isLeafRegion();
	}

}
