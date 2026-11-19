package com.morpheusdata.azurelocal.sync

import com.morpheusdata.azurelocal.AzureLocalApiService
import com.morpheusdata.azurelocal.AzureLocalPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import groovy.util.logging.Slf4j

/**
 * Syncs Azure Local Arc virtual machines (Microsoft.HybridCompute machines with an
 * Azure Local virtualMachineInstance) into Morpheus ComputeServer records.
 */
@Slf4j
class VirtualMachinesSync {

	private AzureLocalPlugin plugin
	private MorpheusContext morpheusContext
	private Cloud cloud
	private AzureLocalApiService apiService

	VirtualMachinesSync(AzureLocalPlugin plugin, Cloud cloud, AzureLocalApiService apiService) {
		this.plugin = plugin
		this.morpheusContext = plugin.morpheus
		this.cloud = cloud
		this.apiService = apiService
	}

	void execute() {
		try {
			Map config = plugin.getApiConfig(cloud)
			Map results = apiService.listVirtualMachines(config)
			if(!results.success) {
				log.warn("VirtualMachinesSync list failed: ${results.msg}")
				return
			}
			ComputeServerType unmanagedType = morpheusContext.async.cloud
					.findComputeServerTypeByCode('azure-local-unmanaged').blockingGet()

			def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null)
			SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask =
					new SyncTask<>(domainRecords, results.items as Collection<Map>)
			syncTask.addMatchFunction { ComputeServerIdentityProjection projection, Map apiItem ->
				projection.externalId == apiItem.machine.id
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
				morpheusContext.async.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { List<Map> itemsToAdd ->
				addVirtualMachines(itemsToAdd, unmanagedType)
			}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
				updateVirtualMachines(updateItems)
			}.onDelete { List<ComputeServerIdentityProjection> removeItems ->
				morpheusContext.async.computeServer.bulkRemove(removeItems).blockingGet()
			}.start()
		} catch(e) {
			log.error("VirtualMachinesSync error: ${e}", e)
		}
	}

	private void addVirtualMachines(List<Map> itemsToAdd, ComputeServerType unmanagedType) {
		List<ComputeServer> adds = []
		for(item in itemsToAdd) {
			Map machine = item.machine as Map
			Map vmi = item.vmi as Map
			ComputeServer server = new ComputeServer(
					account: cloud.account,
					cloud: cloud,
					name: machine.name,
					externalId: machine.id,
					uniqueId: vmi?.properties?.vmId ?: machine.id,
					computeServerType: unmanagedType,
					status: 'provisioned',
					serverType: 'vm',
					discovered: true,
					powerState: mapPowerState(vmi),
					maxCores: (vmi?.properties?.hardwareProfile?.processors ?: 0) as Long,
					maxMemory: ((vmi?.properties?.hardwareProfile?.memoryMB ?: 0) as Long) * 1024L * 1024L,
					osType: mapOsType(vmi)
			)
			applyIpAddresses(server, machine)
			adds << server
		}
		if(adds) {
			morpheusContext.async.computeServer.bulkCreate(adds).blockingGet()
		}
	}

	private void updateVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems) {
		List<ComputeServer> saves = []
		for(updateItem in updateItems) {
			ComputeServer server = updateItem.existingItem
			Map machine = updateItem.masterItem.machine as Map
			Map vmi = updateItem.masterItem.vmi as Map
			boolean changed = false
			def powerState = mapPowerState(vmi)
			if(server.powerState != powerState) {
				server.powerState = powerState
				changed = true
			}
			Long cores = (vmi?.properties?.hardwareProfile?.processors ?: 0) as Long
			if(cores && server.maxCores != cores) {
				server.maxCores = cores
				changed = true
			}
			Long memory = ((vmi?.properties?.hardwareProfile?.memoryMB ?: 0) as Long) * 1024L * 1024L
			if(memory && server.maxMemory != memory) {
				server.maxMemory = memory
				changed = true
			}
			String name = machine.name
			if(name && server.name != name) {
				server.name = name
				changed = true
			}
			String ip = machine.properties?.networkProfile?.networkInterfaces?.getAt(0)?.ipAddresses?.getAt(0)?.address
			if(ip && server.internalIp != ip) {
				server.internalIp = ip
				server.externalIp = ip
				server.sshHost = ip
				changed = true
			}
			if(changed) {
				saves << server
			}
		}
		if(saves) {
			morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
		}
	}

	private static void applyIpAddresses(ComputeServer server, Map machine) {
		String ip = machine.properties?.networkProfile?.networkInterfaces?.getAt(0)?.ipAddresses?.getAt(0)?.address
		if(ip) {
			server.internalIp = ip
			server.externalIp = ip
			server.sshHost = ip
		}
	}

	private static ComputeServer.PowerState mapPowerState(Map vmi) {
		String state = vmi?.properties?.status?.powerState
		switch(state) {
			case 'Running':
				return ComputeServer.PowerState.on
			case 'Stopped':
			case 'Deallocated':
				return ComputeServer.PowerState.off
			case 'Paused':
				return ComputeServer.PowerState.paused
			default:
				return ComputeServer.PowerState.unknown
		}
	}

	private static String mapOsType(Map vmi) {
		String osType = vmi?.properties?.osProfile?.linuxConfiguration ? 'linux' :
				(vmi?.properties?.osProfile?.windowsConfiguration ? 'windows' : null)
		return osType ?: 'unknown'
	}
}
