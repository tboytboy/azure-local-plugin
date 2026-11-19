package com.morpheusdata.azurelocal

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Provisions Arc virtual machines onto an Azure Local instance through ARM:
 * creates the Microsoft.HybridCompute machine shell, an Azure Local network
 * interface on the selected logical network, and the
 * Microsoft.AzureStackHCI virtualMachineInstance targeted at the cloud's
 * custom location.
 */
@Slf4j
class AzureLocalProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider {

	static final String PROVISION_PROVIDER_CODE = 'azure-local-provision'

	protected MorpheusContext morpheusContext
	protected AzureLocalPlugin plugin
	protected AzureLocalApiService apiService

	AzureLocalProvisionProvider(AzureLocalPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
		this.apiService = new AzureLocalApiService()
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path: 'azure-local-circular.svg', darkPath: 'azure-local-circular-dark.svg')
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType(
				name: 'Image',
				code: 'azure-local-provision-image',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'imageId',
				fieldLabel: 'Image',
				fieldContext: 'config',
				required: true,
				displayOrder: 10,
				optionSource: 'azureLocalImages'
		)
		options << new OptionType(
				name: 'Logical Network',
				code: 'azure-local-provision-logical-network',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'logicalNetworkId',
				fieldLabel: 'Logical Network',
				fieldContext: 'config',
				required: false,
				displayOrder: 20,
				optionSource: 'azureLocalLogicalNetworks',
				helpText: 'Defaults to the logical network configured on the cloud when left blank.'
		)
		options << new OptionType(
				name: 'Storage Path',
				code: 'azure-local-provision-storage-path',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'storagePathId',
				fieldLabel: 'Storage Path',
				fieldContext: 'config',
				required: false,
				displayOrder: 30,
				optionSource: 'azureLocalStoragePaths',
				helpText: 'Defaults to the storage path configured on the cloud when left blank.'
		)
		return options
	}

	@Override
	Collection<OptionType> getNodeOptionTypes() {
		return []
	}

	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		return [new StorageVolumeType(code: 'azure-local-disk', name: 'Azure Local Disk', displayOrder: 0)]
	}

	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		return [new StorageVolumeType(code: 'azure-local-disk', name: 'Azure Local Disk', displayOrder: 0)]
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		[
				[cores: 1, memoryGb: 2],
				[cores: 2, memoryGb: 4],
				[cores: 2, memoryGb: 8],
				[cores: 4, memoryGb: 16],
				[cores: 8, memoryGb: 32],
				[cores: 16, memoryGb: 64]
		].eachWithIndex { size, idx ->
			plans << new ServicePlan(
					code: "azure-local-vm-${size.cores}c-${size.memoryGb}g",
					name: "${size.cores} Core, ${size.memoryGb}GB Memory",
					description: "${size.cores} Core, ${size.memoryGb}GB Memory",
					editable: true,
					sortOrder: idx,
					maxCores: size.cores,
					maxMemory: size.memoryGb * 1024L * 1024L * 1024L,
					maxStorage: 60L * 1024L * 1024L * 1024L,
					customMaxStorage: true,
					customMaxDataStorage: true,
					addVolumes: true
			)
		}
		plans << new ServicePlan(
				code: 'azure-local-vm-custom',
				name: 'Custom Azure Local VM',
				description: 'Custom core and memory configuration',
				editable: true,
				sortOrder: 99,
				customMaxStorage: true,
				customMaxDataStorage: true,
				addVolumes: true,
				customCores: true,
				customMaxMemory: true,
				maxCores: 1,
				maxMemory: 2L * 1024L * 1024L * 1024L,
				maxStorage: 0L
		)
		return plans
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Integer getMaxNetworks() {
		return 1
	}

	@Override
	Boolean canAddVolumes() {
		return false
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return false
	}

	@Override
	HostType getHostType() {
		return HostType.vm
	}

	@Override
	String getNodeFormat() {
		return 'vm'
	}

	@Override
	Boolean hasCloneTemplate() {
		return false
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<>(true, null, null, new PrepareWorkloadResponse(workload: workload))
		try {
			String imageId = workload.getConfigProperty('imageId')
			if(imageId) {
				ComputeServer server = workload.server
				VirtualImage virtualImage = morpheusContext.async.virtualImage.listIdentityProjections(server.cloud.id)
						.filter { it.externalId == imageId }
						.map { morpheusContext.async.virtualImage.get(it.id).blockingGet() }
						.firstOrError()
						.onErrorReturnItem(new VirtualImage())
						.blockingGet()
				if(virtualImage?.id) {
					server.sourceImage = virtualImage
					morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
				}
			}
			return resp
		} catch(e) {
			log.error("prepareWorkload error: ${e}", e)
			return new ServiceResponse<PrepareWorkloadResponse>(false, "Error preparing workload: ${e.message}", null, null)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true, installAgent: false, noAgent: true)
		try {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			Map config = plugin.getApiConfig(cloud)

			String imageId = workload.getConfigProperty('imageId') ?: server.sourceImage?.externalId
			String logicalNetworkId = workload.getConfigProperty('logicalNetworkId') ?: config.defaultLogicalNetworkId
			String storagePathId = workload.getConfigProperty('storagePathId') ?: config.defaultStoragePathId
			if(!imageId) {
				return new ServiceResponse<ProvisionResponse>(false, 'No image selected', null, provisionResponse)
			}
			if(!logicalNetworkId) {
				return new ServiceResponse<ProvisionResponse>(false, 'No logical network selected and no default configured on the cloud', null, provisionResponse)
			}
			if(!config.customLocationId) {
				return new ServiceResponse<ProvisionResponse>(false, 'Cloud is missing its custom location configuration', null, provisionResponse)
			}

			// determine the azure region from the custom location's cluster, fall back to the cluster resource
			String location = lookupLocation(config)
			if(!location) {
				return new ServiceResponse<ProvisionResponse>(false, 'Unable to determine the Azure region of the Azure Local instance', null, provisionResponse)
			}

			String vmName = cleanVmName(server.name)

			// 1. Arc machine shell
			Map machineResults = apiService.createMachine(config, vmName, location)
			if(!machineResults.success) {
				return new ServiceResponse<ProvisionResponse>(false, "Failed creating Arc machine: ${machineResults.msg}", null, provisionResponse)
			}
			String machineId = machineResults.machineId
			server.externalId = machineId
			provisionResponse.externalId = machineId
			morpheusContext.async.computeServer.bulkSave([server]).blockingGet()

			// 2. network interface on the logical network
			Map nicResults = apiService.createNetworkInterface(config, "${vmName}-nic0", location, logicalNetworkId)
			if(!nicResults.success) {
				apiService.deleteVirtualMachine(config, machineId)
				return new ServiceResponse<ProvisionResponse>(false, "Failed creating network interface: ${nicResults.msg}", null, provisionResponse)
			}
			server.setConfigProperty('nicId', nicResults.nicId)
			morpheusContext.async.computeServer.bulkSave([server]).blockingGet()

			// 3. virtual machine instance
			Long maxMemory = workload.maxMemory ?: workload.instance?.plan?.maxMemory ?: (4L * 1024L * 1024L * 1024L)
			Long maxCores = workload.maxCores ?: workload.instance?.plan?.maxCores ?: 2L
			boolean isWindows = server.serverOs?.platform?.toString()?.toLowerCase()?.contains('windows') ||
					server.sourceImage?.platform?.toString()?.toLowerCase()?.contains('windows')

			Map osProfile = [computerName: vmName]
			String sshUsername = workloadRequest.usersConfiguration?.sshUsername ?: server.sshUsername
			String sshPassword = workloadRequest.usersConfiguration?.sshPassword ?: server.sshPassword
			if(sshUsername) {
				osProfile.adminUsername = sshUsername
			}
			if(sshPassword) {
				osProfile.adminPassword = sshPassword
			}
			if(isWindows) {
				osProfile.windowsConfiguration = [provisionVMAgent: true, enableAutomaticUpdates: false]
			} else {
				osProfile.linuxConfiguration = [provisionVMAgent: true, disablePasswordAuthentication: false]
			}

			Map vmiProperties = [
					hardwareProfile: [
							vmSize    : 'Custom',
							processors: maxCores as Integer,
							memoryMB  : (maxMemory / (1024L * 1024L)) as Long
					],
					osProfile      : osProfile,
					storageProfile : [
							imageReference: [id: imageId]
					],
					networkProfile : [
							networkInterfaces: [[id: nicResults.nicId]]
					],
					securityProfile: [
							enableTPM: false,
							uefiSettings: [secureBootEnabled: true]
					]
			]
			if(storagePathId) {
				vmiProperties.storageProfile.vmConfigStoragePathId = storagePathId
			}

			Map vmiResults = apiService.createVirtualMachineInstance(config, machineId, vmiProperties)
			if(!vmiResults.success) {
				return new ServiceResponse<ProvisionResponse>(false, "Failed creating virtual machine: ${vmiResults.msg}", null, provisionResponse)
			}

			provisionResponse.success = true
			return new ServiceResponse<ProvisionResponse>(true, 'VM created', null, provisionResponse)
		} catch(e) {
			log.error("runWorkload error: ${e}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse<ProvisionResponse>(false, e.message, null, provisionResponse)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		ProvisionResponse rtn = new ProvisionResponse()
		try {
			Map config = plugin.getApiConfig(server.cloud)
			Long deadline = System.currentTimeMillis() + 1200000L
			while(System.currentTimeMillis() < deadline) {
				Map results = apiService.getVirtualMachine(config, server.externalId)
				if(results.success) {
					String powerState = results.vmi?.properties?.status?.powerState
					String ip = extractIpAddress(config, results)
					if(ip) {
						rtn.privateIp = ip
						rtn.publicIp = ip
						rtn.externalId = server.externalId
						rtn.success = true
						return ServiceResponse.success(rtn)
					}
					if(powerState == 'Stopped') {
						break
					}
				}
				sleep(15000)
			}
			rtn.externalId = server.externalId
			rtn.success = true
			rtn.skipNetworkWait = true
			return ServiceResponse.success(rtn)
		} catch(e) {
			log.error("getServerDetails error: ${e}", e)
			return new ServiceResponse<ProvisionResponse>(false, e.message, null, rtn)
		}
	}

	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse stopWorkload(Workload workload) {
		return powerAction(workload.server, 'stop')
	}

	@Override
	ServiceResponse startWorkload(Workload workload) {
		return powerAction(workload.server, 'start')
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		return powerAction(workload.server, 'restart')
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		try {
			ComputeServer server = workload.server
			if(!server.externalId) {
				return ServiceResponse.success()
			}
			Map config = plugin.getApiConfig(server.cloud)
			Map results = apiService.deleteVirtualMachine(config, server.externalId)
			if(!results.success) {
				return ServiceResponse.error("Failed removing VM: ${results.msg}")
			}
			String nicId = server.getConfigProperty('nicId')
			if(nicId) {
				apiService.deleteNetworkInterface(config, nicId)
			}
			return ServiceResponse.success()
		} catch(e) {
			log.error("removeWorkload error: ${e}", e)
			return ServiceResponse.error("Error removing workload: ${e.message}")
		}
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return powerAction(computeServer, 'stop')
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return powerAction(computeServer, 'start')
	}

	private ServiceResponse powerAction(ComputeServer server, String action) {
		try {
			if(!server?.externalId) {
				return ServiceResponse.error('externalId not found on server')
			}
			Map config = plugin.getApiConfig(server.cloud)
			Map results = apiService.vmPowerAction(config, server.externalId, action)
			return results.success ? ServiceResponse.success() : ServiceResponse.error(results.msg as String)
		} catch(e) {
			log.error("powerAction ${action} error: ${e}", e)
			return ServiceResponse.error("Error performing ${action}: ${e.message}")
		}
	}

	private String lookupLocation(Map config) {
		if(config.customLocationId) {
			Map results = apiService.callApi(config, 'GET',
					"${config.customLocationId}?api-version=${AzureLocalApiService.API_VERSION_CUSTOM_LOCATIONS}")
			if(results.success && results.data?.location) {
				return results.data.location
			}
		}
		if(config.clusterId) {
			Map results = apiService.callApi(config, 'GET',
					"${config.clusterId}?api-version=${AzureLocalApiService.API_VERSION_HCI_CLUSTERS}")
			if(results.success && results.data?.location) {
				return results.data.location
			}
		}
		return null
	}

	private String extractIpAddress(Map config, Map vmResults) {
		List nicIds = (vmResults.vmi?.properties?.networkProfile?.networkInterfaces ?: []).collect { it.id }
		for(nicId in nicIds) {
			Map nicResults = apiService.callApi(config, 'GET',
					"${nicId}?api-version=${AzureLocalApiService.API_VERSION_STACK_HCI_VM}")
			if(nicResults.success) {
				def ipConfigs = nicResults.data?.properties?.ipConfigurations ?: []
				for(ipConfig in ipConfigs) {
					String address = ipConfig.properties?.privateIPAddress
					if(address) {
						return address
					}
				}
			}
		}
		return null
	}

	private static String cleanVmName(String name) {
		String cleaned = name?.replaceAll(/[^a-zA-Z0-9\-]/, '-')?.replaceAll(/-+/, '-')
		cleaned = cleaned?.replaceAll(/^-+|-+$/, '')
		if(!cleaned) {
			cleaned = "morpheus-vm-${System.currentTimeMillis()}"
		}
		return cleaned.length() > 54 ? cleaned.substring(0, 54) : cleaned
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
		return PROVISION_PROVIDER_CODE
	}

	@Override
	String getName() {
		return 'Azure Local'
	}
}
