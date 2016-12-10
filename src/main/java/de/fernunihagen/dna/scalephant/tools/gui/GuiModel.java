/*******************************************************************************
 *
 *    Copyright (C) 2015-2016
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
package de.fernunihagen.dna.scalephant.tools.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fernunihagen.dna.scalephant.distribution.DistributionRegion;
import de.fernunihagen.dna.scalephant.distribution.membership.DistributedInstance;
import de.fernunihagen.dna.scalephant.distribution.membership.DistributedInstanceManager;
import de.fernunihagen.dna.scalephant.distribution.membership.event.DistributedInstanceEvent;
import de.fernunihagen.dna.scalephant.distribution.membership.event.DistributedInstanceEventCallback;
import de.fernunihagen.dna.scalephant.distribution.mode.DistributionGroupZookeeperAdapter;
import de.fernunihagen.dna.scalephant.distribution.zookeeper.ZookeeperClient;
import de.fernunihagen.dna.scalephant.distribution.zookeeper.ZookeeperException;

public class GuiModel implements DistributedInstanceEventCallback {
	
	/**
	 * The scalephant instances
	 */
	protected final List<DistributedInstance> scalephantInstances;
	
	/**
	 * The distribution group to display
	 */
	protected String distributionGroup;
	
	/**
	 * The distribution region
	 */
	protected DistributionRegion rootRegion;
	
	/**
	 * The replication factor for the distribution group
	 */
	protected short replicationFactor;
	
	/**
	 * The version of the root region
	 */
	protected String rootRegionVersion;
	
	/**
	 * The reference to the gui window
	 */
	protected ScalephantGui scalephantGui;
	
	/**
	 * The zookeeper client
	 */
	protected final ZookeeperClient zookeeperClient;
	
	/**
	 * The distribution group adapter
	 */
	protected final DistributionGroupZookeeperAdapter distributionGroupZookeeperAdapter;

	
	protected final static Logger logger = LoggerFactory.getLogger(GuiModel.class);

	public GuiModel(final ZookeeperClient zookeeperClient) {
		this.zookeeperClient = zookeeperClient;
		this.distributionGroupZookeeperAdapter = new DistributionGroupZookeeperAdapter(zookeeperClient);
		scalephantInstances = new ArrayList<DistributedInstance>();
		
		DistributedInstanceManager.getInstance().registerListener(this);
	}

	/**
	 * Shutdown the GUI model
	 */
	public void shutdown() {
		DistributedInstanceManager.getInstance().removeListener(this);
	}
	
	/**
	 * Update the GUI model
	 */
	public void updateModel() {
		try {
			updateScalepahntInstances();
			scalephantGui.updateView();
		} catch(Exception e) {
			logger.info("Exception while updating the view", e);
		}
	}
	

	/**
	 * Update the system state
	 */
	protected void updateScalepahntInstances() {
		synchronized (scalephantInstances) {		
			scalephantInstances.clear();
			scalephantInstances.addAll(DistributedInstanceManager.getInstance().getInstances());
			Collections.sort(scalephantInstances);
		}
	}
	
	/**
	 * Update the distribution region
	 * @throws ZookeeperException 
	 */
	public void updateDistributionRegion() throws ZookeeperException {
		final String currentVersion = distributionGroupZookeeperAdapter.getVersionForDistributionGroup(distributionGroup);
		
		if(! currentVersion.equals(rootRegionVersion)) {
			logger.info("Reread distribution group, version has changed: " + rootRegionVersion + " / " + currentVersion);
			rootRegion = distributionGroupZookeeperAdapter.readDistributionGroup(distributionGroup);
			rootRegionVersion = currentVersion;
			replicationFactor = distributionGroupZookeeperAdapter.getReplicationFactorForDistributionGroup(distributionGroup);
		}
	}

	/**
	 * A group membership event is occurred
	 */
	@Override
	public void distributedInstanceEvent(final DistributedInstanceEvent event) {
		updateScalepahntInstances();
	}

	/**
	 * Get the scalephant instances
	 * @return
	 */
	public List<DistributedInstance> getScalephantInstances() {
		return scalephantInstances;
	}

	/**
	 * Set the gui component
	 * @param scalephantGui
	 */
	public void setScalephantGui(final ScalephantGui scalephantGui) {
		this.scalephantGui = scalephantGui;
	}

	/**
	 * Get the distribution group
	 * 
	 * @return
	 */
	public String getDistributionGroup() {
		return distributionGroup;
	}
	
	/**
	 * Get the name of the cluster
	 * @return
	 */
	public String getClustername() {
		return zookeeperClient.getClustername();
	}
	
	/**
	 * Get the replication factor
	 * @return
	 */
	public short getReplicationFactor() {
		return replicationFactor;
	}

	/**
	 * Set the replication factor
	 * @param replicationFactor
	 */
	public void setReplicationFactor(final short replicationFactor) {
		this.replicationFactor = replicationFactor;
	}

	/**
	 * Set the distribution group
	 * 
	 * @param distributionGroup
	 */
	public void setDistributionGroup(final String distributionGroup) {
		this.distributionGroup = distributionGroup;
	
		try {
			updateDistributionRegion();
		} catch(Exception e) {
			logger.info("Exception while updating the view", e);
		}
		
		// Display the new distribution group
		updateModel();
	}
	
	/**
	 * Get the root region
	 * @return
	 */
	public DistributionRegion getRootRegion() {
		return rootRegion;
	}

}
