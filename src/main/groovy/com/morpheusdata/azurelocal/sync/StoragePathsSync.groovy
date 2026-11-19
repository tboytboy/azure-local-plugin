package com.morpheusdata.azurelocal.sync

import com.morpheusdata.azurelocal.AzureLocalApiService
import com.morpheusdata.azurelocal.AzureLocalPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.DatastoreIdentity
import groovy.util.logging.Slf4j

/**
 * Syncs Azure Local storage paths (storage containers) into Morpheus Datastore
 * records for visibility and default placement selection.
 */
@Slf4j
class StoragePathsSync {

	private AzureLocalPlugin plugin
	private MorpheusContext morpheusContext
	private Cloud cloud
	private AzureLocalApiService apiService

	StoragePathsSync(AzureLocalPlugin plugin, Cloud cloud, AzureLocalApiService apiService) {
		this.plugin = plugin
		this.morpheusContext = plugin.morpheus
		this.cloud = cloud
		this.apiService = apiService
	}

	void execute() {
		try {
			Map config = plugin.getApiConfig(cloud)
			Map results = apiService.listStoragePaths(config)
			if(!results.success) {
				log.warn("StoragePathsSync list failed: ${results.msg}")
				return
			}
			def domainRecords = morpheusContext.async.cloud.datastore.listSyncProjections(cloud.id)
			SyncTask<DatastoreIdentity, Map, Datastore> syncTask =
					new SyncTask<>(domainRecords, results.items as Collection<Map>)
			syncTask.addMatchFunction { DatastoreIdentity projection, Map apiItem ->
				projection.externalId == apiItem.id
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItems ->
				morpheusContext.async.cloud.datastore.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { List<Map> itemsToAdd ->
				addStoragePaths(itemsToAdd)
			}.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
				updateStoragePaths(updateItems)
			}.onDelete { List<DatastoreIdentity> removeItems ->
				morpheusContext.async.cloud.datastore.bulkRemove(removeItems).blockingGet()
			}.start()
		} catch(e) {
			log.error("StoragePathsSync error: ${e}", e)
		}
	}

	private void addStoragePaths(List<Map> itemsToAdd) {
		List<Datastore> adds = []
		for(item in itemsToAdd) {
			Datastore datastore = new Datastore(
					owner: cloud.owner,
					cloud: cloud,
					code: "azure-local.storage-path.${cloud.id}.${item.name}".toString(),
					category: "azure-local.storage-path.${cloud.id}".toString(),
					name: item.name,
					externalId: item.id,
					type: 'generic',
					refType: 'ComputeZone',
					refId: cloud.id,
					externalPath: item.properties?.path,
					active: true,
					online: true,
					allowProvision: true
			)
			Long sizeMb = (item.properties?.status?.containerSizeMB ?: 0) as Long
			if(sizeMb) {
				datastore.storageSize = sizeMb * 1024L * 1024L
			}
			adds << datastore
		}
		if(adds) {
			morpheusContext.async.cloud.datastore.bulkCreate(adds).blockingGet()
		}
	}

	private void updateStoragePaths(List<SyncTask.UpdateItem<Datastore, Map>> updateItems) {
		List<Datastore> saves = []
		for(updateItem in updateItems) {
			Datastore datastore = updateItem.existingItem
			Map item = updateItem.masterItem
			boolean changed = false
			if(item.name && datastore.name != item.name) {
				datastore.name = item.name
				changed = true
			}
			Long sizeMb = (item.properties?.status?.containerSizeMB ?: 0) as Long
			Long sizeBytes = sizeMb * 1024L * 1024L
			if(sizeMb && datastore.storageSize != sizeBytes) {
				datastore.storageSize = sizeBytes
				changed = true
			}
			if(changed) {
				saves << datastore
			}
		}
		if(saves) {
			morpheusContext.async.cloud.datastore.bulkSave(saves).blockingGet()
		}
	}
}
