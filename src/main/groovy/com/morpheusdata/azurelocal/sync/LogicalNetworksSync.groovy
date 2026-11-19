package com.morpheusdata.azurelocal.sync

import com.morpheusdata.azurelocal.AzureLocalApiService
import com.morpheusdata.azurelocal.AzureLocalPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.projection.NetworkIdentityProjection
import groovy.util.logging.Slf4j

/**
 * Syncs Azure Local logical networks into Morpheus Network records so they can be
 * selected during provisioning.
 */
@Slf4j
class LogicalNetworksSync {

	private AzureLocalPlugin plugin
	private MorpheusContext morpheusContext
	private Cloud cloud
	private AzureLocalApiService apiService

	LogicalNetworksSync(AzureLocalPlugin plugin, Cloud cloud, AzureLocalApiService apiService) {
		this.plugin = plugin
		this.morpheusContext = plugin.morpheus
		this.cloud = cloud
		this.apiService = apiService
	}

	void execute() {
		try {
			Map config = plugin.getApiConfig(cloud)
			Map results = apiService.listLogicalNetworks(config)
			if(!results.success) {
				log.warn("LogicalNetworksSync list failed: ${results.msg}")
				return
			}
			def domainRecords = morpheusContext.async.network.listIdentityProjections(cloud.id)
			SyncTask<NetworkIdentityProjection, Map, Network> syncTask =
					new SyncTask<>(domainRecords, results.items as Collection<Map>)
			syncTask.addMatchFunction { NetworkIdentityProjection projection, Map apiItem ->
				projection.externalId == apiItem.id
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
				morpheusContext.async.network.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { List<Map> itemsToAdd ->
				addNetworks(itemsToAdd)
			}.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
				updateNetworks(updateItems)
			}.onDelete { List<NetworkIdentityProjection> removeItems ->
				morpheusContext.async.network.bulkRemove(removeItems).blockingGet()
			}.start()
		} catch(e) {
			log.error("LogicalNetworksSync error: ${e}", e)
		}
	}

	private void addNetworks(List<Map> itemsToAdd) {
		List<Network> adds = []
		for(item in itemsToAdd) {
			Map subnet = (item.properties?.subnets ?: [])?.getAt(0) as Map
			Network network = new Network(
					owner: cloud.owner,
					cloud: cloud,
					category: "azure-local.network.${cloud.id}".toString(),
					code: "azure-local.network.${cloud.id}.${item.name}".toString(),
					name: item.name,
					externalId: item.id,
					type: new NetworkType(code: 'azure-local-logical-network'),
					externalType: 'LogicalNetwork',
					refType: 'ComputeZone',
					refId: cloud.id,
					active: true,
					dhcpServer: subnet?.properties?.ipAllocationMethod == 'Dynamic'
			)
			String cidr = subnet?.properties?.addressPrefix
			if(cidr) {
				network.cidr = cidr
			}
			def vlan = subnet?.properties?.vlan
			if(vlan != null) {
				network.vlanId = vlan as Integer
			}
			List dnsServers = (item.properties?.dhcpOptions?.dnsServers ?: []) as List
			if(dnsServers) {
				network.dnsPrimary = dnsServers.getAt(0)
				if(dnsServers.size() > 1) {
					network.dnsSecondary = dnsServers.getAt(1)
				}
			}
			List routes = (subnet?.properties?.routeTable?.properties?.routes ?: []) as List
			String gateway = routes.find { it.properties?.addressPrefix == '0.0.0.0/0' }?.properties?.nextHopIpAddress
			if(gateway) {
				network.gateway = gateway
			}
			adds << network
		}
		if(adds) {
			morpheusContext.async.network.bulkCreate(adds).blockingGet()
		}
	}

	private void updateNetworks(List<SyncTask.UpdateItem<Network, Map>> updateItems) {
		List<Network> saves = []
		for(updateItem in updateItems) {
			Network network = updateItem.existingItem
			Map item = updateItem.masterItem
			boolean changed = false
			if(item.name && network.name != item.name) {
				network.name = item.name
				changed = true
			}
			Map subnet = (item.properties?.subnets ?: [])?.getAt(0) as Map
			String cidr = subnet?.properties?.addressPrefix
			if(cidr && network.cidr != cidr) {
				network.cidr = cidr
				changed = true
			}
			if(changed) {
				saves << network
			}
		}
		if(saves) {
			morpheusContext.async.network.bulkSave(saves).blockingGet()
		}
	}
}
