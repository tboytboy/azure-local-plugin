package com.morpheusdata.azurelocal

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.OptionSourceProvider
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

/**
 * Supplies the dynamic dropdown data for the Azure Local cloud configuration form
 * (resource groups, Azure Local instances, custom locations, storage paths, logical
 * networks) and the provisioning wizard (gallery images).
 */
@Slf4j
class AzureLocalOptionSourceProvider implements OptionSourceProvider {

	protected MorpheusContext morpheusContext
	protected AzureLocalPlugin plugin
	protected AzureLocalApiService apiService

	AzureLocalOptionSourceProvider(AzureLocalPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
		this.apiService = new AzureLocalApiService()
	}

	@Override
	List<String> getMethodNames() {
		return [
				'azureLocalResourceGroups',
				'azureLocalClusters',
				'azureLocalCustomLocations',
				'azureLocalStoragePaths',
				'azureLocalLogicalNetworks',
				'azureLocalImages'
		]
	}

	List azureLocalResourceGroups(args) {
		Map config = resolveConfig(args)
		if(!hasCredentials(config)) {
			return []
		}
		Map results = apiService.listResourceGroups(config)
		if(!results.success) {
			log.warn("azureLocalResourceGroups failed: ${results.msg}")
			return []
		}
		return results.items.collect { [name: it.name, value: it.name] }.sort { it.name.toLowerCase() }
	}

	List azureLocalClusters(args) {
		Map config = resolveConfig(args)
		if(!hasCredentials(config)) {
			return []
		}
		Map results = apiService.listClusters(config)
		if(!results.success) {
			log.warn("azureLocalClusters failed: ${results.msg}")
			return []
		}
		return results.items.collect { [name: it.name, value: it.id] }.sort { it.name.toLowerCase() }
	}

	List azureLocalCustomLocations(args) {
		Map config = resolveConfig(args)
		if(!hasCredentials(config)) {
			return []
		}
		Map results = apiService.listCustomLocations(config)
		if(!results.success) {
			log.warn("azureLocalCustomLocations failed: ${results.msg}")
			return []
		}
		return results.items.collect { [name: it.name, value: it.id] }.sort { it.name.toLowerCase() }
	}

	List azureLocalStoragePaths(args) {
		Map config = resolveConfig(args)
		if(!hasCredentials(config)) {
			return []
		}
		Map results = apiService.listStoragePaths(config)
		if(!results.success) {
			log.warn("azureLocalStoragePaths failed: ${results.msg}")
			return []
		}
		return results.items.collect { [name: it.name, value: it.id] }.sort { it.name.toLowerCase() }
	}

	List azureLocalLogicalNetworks(args) {
		Map config = resolveConfig(args)
		if(!hasCredentials(config)) {
			return []
		}
		Map results = apiService.listLogicalNetworks(config)
		if(!results.success) {
			log.warn("azureLocalLogicalNetworks failed: ${results.msg}")
			return []
		}
		return results.items.collect { [name: it.name, value: it.id] }.sort { it.name.toLowerCase() }
	}

	List azureLocalImages(args) {
		Map config = resolveConfig(args)
		if(!hasCredentials(config)) {
			return []
		}
		Map results = apiService.listGalleryImages(config)
		if(!results.success) {
			log.warn("azureLocalImages failed: ${results.msg}")
			return []
		}
		return results.items.collect { [name: it.name, value: it.id] }.sort { it.name.toLowerCase() }
	}

	private static Boolean hasCredentials(Map config) {
		return config.subscriptionId && config.tenantId && config.clientId && config.clientSecret
	}

	/**
	 * Builds the API config from option source args. Supports both a saved cloud
	 * (zoneId/cloudId present) and the unsaved Create Cloud form where values arrive
	 * in the submitted config/credential maps.
	 */
	private Map resolveConfig(args) {
		Map params = (args instanceof List ? (args.size() > 0 ? args.getAt(0) : [:]) : args) as Map ?: [:]
		Map config = [:]

		Long cloudId = parseLong(params.zoneId ?: params.cloudId)
		Cloud cloud = null
		if(cloudId) {
			try {
				cloud = morpheusContext.async.cloud.getCloudById(cloudId).blockingGet()
			} catch(e) {
				log.warn("Unable to load cloud ${cloudId}: ${e.message}")
			}
		}
		if(cloud) {
			config.putAll(plugin.getApiConfig(cloud))
		}

		Map formConfig = (params.config ?: [:]) as Map
		['subscriptionId', 'tenantId', 'clientId', 'clientSecret', 'resourceGroup'].each { key ->
			def value = formConfig[key] ?: params[key]
			if(value) {
				config[key] = value
			}
		}

		// stored credential selected on the unsaved form
		def credentialId = parseLong(params.credential?.id ?: (params.credential?.type != 'local' ? params.credential?.type : null))
		if(credentialId) {
			try {
				def accountCredential = morpheusContext.async.accountCredential.get(credentialId).blockingGet()
				if(accountCredential?.data?.clientId) {
					config.clientId = accountCredential.data.clientId
				}
				if(accountCredential?.data?.clientSecret) {
					config.clientSecret = accountCredential.data.clientSecret
				}
			} catch(e) {
				log.warn("Unable to load credential ${credentialId}: ${e.message}")
			}
		}
		return config
	}

	private static Long parseLong(value) {
		try {
			return value != null ? value.toString().toLong() : null
		} catch(ignore) {
			return null
		}
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'azure-local-option-source'
	}

	@Override
	String getName() {
		return 'Azure Local Option Source'
	}
}
