package com.morpheusdata.azurelocal.sync

import com.morpheusdata.azurelocal.AzureLocalApiService
import com.morpheusdata.azurelocal.AzureLocalPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import groovy.util.logging.Slf4j

/**
 * Syncs Azure Local gallery images (including marketplace gallery images) into
 * Morpheus VirtualImage records for image selection during provisioning.
 */
@Slf4j
class ImagesSync {

	private AzureLocalPlugin plugin
	private MorpheusContext morpheusContext
	private Cloud cloud
	private AzureLocalApiService apiService

	ImagesSync(AzureLocalPlugin plugin, Cloud cloud, AzureLocalApiService apiService) {
		this.plugin = plugin
		this.morpheusContext = plugin.morpheus
		this.cloud = cloud
		this.apiService = apiService
	}

	void execute() {
		try {
			Map config = plugin.getApiConfig(cloud)
			Map results = apiService.listGalleryImages(config)
			if(!results.success) {
				log.warn("ImagesSync list failed: ${results.msg}")
				return
			}
			String category = "azure-local.image.${cloud.id}".toString()
			Long accountId = cloud.owner?.id ?: cloud.account?.id
			def domainRecords = morpheusContext.async.virtualImage
					.listIdentityProjectionsByCloudAndCategory(accountId, cloud.id, [category] as String[])
			SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask =
					new SyncTask<>(domainRecords, results.items as Collection<Map>)
			syncTask.addMatchFunction { VirtualImageIdentityProjection projection, Map apiItem ->
				projection.externalId == apiItem.id
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItems ->
				morpheusContext.async.virtualImage.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { List<Map> itemsToAdd ->
				addImages(itemsToAdd, category)
			}.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
				updateImages(updateItems)
			}.onDelete { List<VirtualImageIdentityProjection> removeItems ->
				morpheusContext.async.virtualImage.bulkRemove(removeItems).blockingGet()
			}.start()
		} catch(e) {
			log.error("ImagesSync error: ${e}", e)
		}
	}

	private void addImages(List<Map> itemsToAdd, String category) {
		List<VirtualImage> adds = []
		for(item in itemsToAdd) {
			String osTypeValue = item.properties?.osType?.toString()?.toLowerCase()
			VirtualImage image = new VirtualImage(
					account: cloud.account,
					owner: cloud.owner,
					category: category,
					name: item.name,
					externalId: item.id,
					code: "azure-local.image.${cloud.id}.${item.name}".toString(),
					imageType: ImageType.vhd,
					platform: osTypeValue == 'windows' ? 'windows' : 'linux',
					isPublic: false,
					refType: 'ComputeZone',
					refId: "${cloud.id}".toString(),
					status: 'Active'
			)
			adds << image
		}
		if(adds) {
			morpheusContext.async.virtualImage.bulkCreate(adds).blockingGet()
		}
	}

	private void updateImages(List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems) {
		List<VirtualImage> saves = []
		for(updateItem in updateItems) {
			VirtualImage image = updateItem.existingItem
			Map item = updateItem.masterItem
			if(item.name && image.name != item.name) {
				image.name = item.name
				saves << image
			}
		}
		if(saves) {
			morpheusContext.async.virtualImage.bulkSave(saves).blockingGet()
		}
	}
}
