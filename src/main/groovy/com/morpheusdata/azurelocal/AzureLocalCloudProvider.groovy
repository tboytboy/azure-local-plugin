package com.morpheusdata.azurelocal

import com.morpheusdata.azurelocal.sync.ImagesSync
import com.morpheusdata.azurelocal.sync.LogicalNetworksSync
import com.morpheusdata.azurelocal.sync.StoragePathsSync
import com.morpheusdata.azurelocal.sync.VirtualMachinesSync
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Cloud provider for connected Azure Local deployments.
 * Discovery and management run through public Azure Resource Manager using the
 * Microsoft.AzureStackHCI resource provider and Arc-enabled machines.
 */
@Slf4j
class AzureLocalCloudProvider implements CloudProvider {

	static final String CLOUD_PROVIDER_CODE = 'azure-local'

	protected MorpheusContext morpheusContext
	protected AzureLocalPlugin plugin
	protected AzureLocalApiService apiService

	AzureLocalCloudProvider(AzureLocalPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
		this.apiService = new AzureLocalApiService()
	}

	@Override
	String getDescription() {
		return 'Connects Morpheus to an Azure Local instance through Azure Resource Manager. Discovers Arc virtual machines, logical networks, storage paths and gallery images, and provisions Arc VMs onto the Azure Local cluster.'
	}

	@Override
	Icon getIcon() {
		return new Icon(path: 'azure-local.svg', darkPath: 'azure-local-dark.svg')
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path: 'azure-local-circular.svg', darkPath: 'azure-local-circular-dark.svg')
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType(
				name: 'Credentials',
				code: 'azure-local-credential',
				inputType: OptionType.InputType.CREDENTIAL,
				fieldName: 'type',
				fieldLabel: 'Credentials',
				fieldContext: 'credential',
				required: true,
				defaultValue: 'local',
				displayOrder: 10,
				optionSource: 'credentials',
				config: '{"credentialTypes":["client-id-secret"]}'
		)
		options << new OptionType(
				name: 'Subscription ID',
				code: 'azure-local-subscription-id',
				inputType: OptionType.InputType.TEXT,
				fieldName: 'subscriptionId',
				fieldLabel: 'Subscription ID',
				fieldContext: 'config',
				required: true,
				displayOrder: 20
		)
		options << new OptionType(
				name: 'Tenant ID',
				code: 'azure-local-tenant-id',
				inputType: OptionType.InputType.TEXT,
				fieldName: 'tenantId',
				fieldLabel: 'Tenant ID',
				fieldContext: 'config',
				required: true,
				displayOrder: 30
		)
		options << new OptionType(
				name: 'Client ID',
				code: 'azure-local-client-id',
				inputType: OptionType.InputType.TEXT,
				fieldName: 'clientId',
				fieldLabel: 'Client ID',
				fieldContext: 'config',
				required: true,
				displayOrder: 40,
				localCredential: true
		)
		options << new OptionType(
				name: 'Client Secret',
				code: 'azure-local-client-secret',
				inputType: OptionType.InputType.PASSWORD,
				fieldName: 'clientSecret',
				fieldLabel: 'Client Secret',
				fieldContext: 'config',
				required: true,
				displayOrder: 50,
				localCredential: true
		)
		options << new OptionType(
				name: 'Resource Group',
				code: 'azure-local-resource-group',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'resourceGroup',
				fieldLabel: 'Resource Group',
				fieldContext: 'config',
				required: true,
				displayOrder: 60,
				optionSource: 'azureLocalResourceGroups',
				dependsOn: 'config.subscriptionId,config.tenantId,config.clientId,config.clientSecret,credential.type'
		)
		options << new OptionType(
				name: 'Azure Local Instance',
				code: 'azure-local-cluster',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'cluster',
				fieldLabel: 'Azure Local Instance',
				fieldContext: 'config',
				required: true,
				displayOrder: 70,
				optionSource: 'azureLocalClusters',
				dependsOn: 'config.resourceGroup',
				helpText: 'Arc-enabled Azure Local instances (Azure Local clusters) discovered in the selected resource group.'
		)
		options << new OptionType(
				name: 'Custom Location',
				code: 'azure-local-custom-location',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'customLocation',
				fieldLabel: 'Custom Location',
				fieldContext: 'config',
				required: true,
				displayOrder: 80,
				optionSource: 'azureLocalCustomLocations',
				dependsOn: 'config.resourceGroup',
				helpText: 'The Arc custom location that targets deployments at the Azure Local instance.'
		)
		options << new OptionType(
				name: 'Default Storage Path',
				code: 'azure-local-default-storage-path',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'defaultStoragePath',
				fieldLabel: 'Default Storage Path',
				fieldContext: 'config',
				required: false,
				displayOrder: 90,
				optionSource: 'azureLocalStoragePaths',
				dependsOn: 'config.resourceGroup',
				helpText: 'Storage path (storage container) used by default for VM configuration and disks.'
		)
		options << new OptionType(
				name: 'Default Logical Network',
				code: 'azure-local-default-logical-network',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'defaultLogicalNetwork',
				fieldLabel: 'Default Logical Network',
				fieldContext: 'config',
				required: false,
				displayOrder: 100,
				optionSource: 'azureLocalLogicalNetworks',
				dependsOn: 'config.resourceGroup',
				helpText: 'Logical network used by default for new VM network interfaces.'
		)
		options << new OptionType(
				name: 'Import Existing',
				code: 'azure-local-import-existing',
				inputType: OptionType.InputType.CHECKBOX,
				fieldName: 'importExisting',
				fieldLabel: 'Inventory Existing Instances',
				fieldContext: 'config',
				required: false,
				displayOrder: 110,
				defaultValue: 'off'
		)
		return options
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		Collection<ComputeServerType> serverTypes = []
		serverTypes << new ComputeServerType(
				code: 'azure-local-vm',
				name: 'Azure Local VM',
				description: 'Azure Local Arc virtual machine',
				platform: PlatformType.linux,
				nodeType: 'morpheus-vm-node',
				enabled: true,
				selectable: false,
				externalDelete: true,
				managed: true,
				controlPower: true,
				controlSuspend: false,
				creatable: false,
				displayOrder: 0,
				hasAutomation: true,
				reconfigureSupported: false,
				hasAgent: true,
				containerHypervisor: false,
				bareMetalHost: false,
				vmHypervisor: false,
				agentType: ComputeServerType.AgentType.guest,
				guestVm: true,
				provisionTypeCode: AzureLocalProvisionProvider.PROVISION_PROVIDER_CODE
		)
		serverTypes << new ComputeServerType(
				code: 'azure-local-windows-vm',
				name: 'Azure Local Windows VM',
				description: 'Azure Local Arc Windows virtual machine',
				platform: PlatformType.windows,
				nodeType: 'morpheus-windows-vm-node',
				enabled: true,
				selectable: false,
				externalDelete: true,
				managed: true,
				controlPower: true,
				controlSuspend: false,
				creatable: false,
				displayOrder: 1,
				hasAutomation: true,
				reconfigureSupported: false,
				hasAgent: true,
				containerHypervisor: false,
				bareMetalHost: false,
				vmHypervisor: false,
				agentType: ComputeServerType.AgentType.guest,
				guestVm: true,
				provisionTypeCode: AzureLocalProvisionProvider.PROVISION_PROVIDER_CODE
		)
		serverTypes << new ComputeServerType(
				code: 'azure-local-unmanaged',
				name: 'Azure Local Instance',
				description: 'Discovered (unmanaged) Azure Local Arc virtual machine',
				platform: PlatformType.none,
				nodeType: 'unmanaged',
				enabled: true,
				selectable: false,
				externalDelete: true,
				managed: false,
				controlPower: true,
				controlSuspend: false,
				creatable: false,
				displayOrder: 99,
				hasAutomation: false,
				reconfigureSupported: false,
				hasAgent: false,
				containerHypervisor: false,
				bareMetalHost: false,
				vmHypervisor: false,
				agentType: ComputeServerType.AgentType.none,
				managedServerType: 'azure-local-vm',
				guestVm: true,
				provisionTypeCode: AzureLocalProvisionProvider.PROVISION_PROVIDER_CODE
		)
		return serverTypes
	}

	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
		return this.plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return []
	}

	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		Collection<NetworkType> networks = []
		networks << new NetworkType(
				code: 'azure-local-logical-network',
				name: 'Azure Local Logical Network',
				description: 'Azure Local logical network backed by a Hyper-V virtual switch',
				overlay: false,
				externalType: 'LogicalNetwork',
				creatable: false,
				cidrEditable: false,
				dhcpServerEditable: false,
				dnsEditable: false,
				gatewayEditable: false,
				vlanIdEditable: false,
				canAssignPool: false,
				deletable: false,
				hasNetworkServer: false,
				hasCidr: true
		)
		return networks
	}

	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		return []
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		Collection<StorageVolumeType> volumeTypes = []
		volumeTypes << new StorageVolumeType(
				code: 'azure-local-disk',
				name: 'Azure Local Disk',
				displayOrder: 0
		)
		return volumeTypes
	}

	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		return []
	}

	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		try {
			if(!cloudInfo) {
				return new ServiceResponse(success: false, msg: 'No cloud found')
			}
			Map config = plugin.getApiConfig(cloudInfo)
			// apply unsaved credential values submitted with the form
			if(validateCloudRequest?.credentialType?.toString()?.isNumber() || validateCloudRequest?.credentialType == 'client-id-secret') {
				config.clientId = validateCloudRequest.credentialUsername ?: config.clientId
				config.clientSecret = validateCloudRequest.credentialPassword ?: config.clientSecret
			}
			if(!config.subscriptionId) {
				return new ServiceResponse(success: false, msg: 'Enter a subscription ID', errors: [subscriptionId: 'Required'])
			}
			if(!config.tenantId) {
				return new ServiceResponse(success: false, msg: 'Enter a tenant ID', errors: [tenantId: 'Required'])
			}
			if(!config.clientId || !config.clientSecret) {
				return new ServiceResponse(success: false, msg: 'Enter credentials')
			}
			if(!config.resourceGroup) {
				return new ServiceResponse(success: false, msg: 'Select a resource group', errors: [resourceGroup: 'Required'])
			}
			if(!config.customLocationId) {
				return new ServiceResponse(success: false, msg: 'Select a custom location', errors: [customLocation: 'Required'])
			}
			Map results = apiService.validateConnection(config)
			if(!results.success) {
				return new ServiceResponse(success: false, msg: results.msg ?: 'Unable to connect to Azure')
			}
			return ServiceResponse.success()
		} catch(e) {
			log.error("validate error: ${e}", e)
			return new ServiceResponse(success: false, msg: "Error validating cloud: ${e.message}")
		}
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		return refresh(cloudInfo)
	}

	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		try {
			Map config = plugin.getApiConfig(cloudInfo)
			Map testResults = apiService.validateConnection(config)
			if(!testResults.success) {
				return ServiceResponse.error(testResults.msg ?: 'Azure Local is not reachable', null, [status: Cloud.Status.offline])
			}
			new LogicalNetworksSync(plugin, cloudInfo, apiService).execute()
			new StoragePathsSync(plugin, cloudInfo, apiService).execute()
			new ImagesSync(plugin, cloudInfo, apiService).execute()
			new VirtualMachinesSync(plugin, cloudInfo, apiService).execute()
			return ServiceResponse.success()
		} catch(e) {
			log.error("refresh cloud error: ${e}", e)
			return ServiceResponse.error("Error refreshing cloud: ${e.message}")
		}
	}

	@Override
	void refreshDaily(Cloud cloudInfo) {
		// all syncs run on the standard refresh cycle
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasFolders() {
		return false
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean hasBareMetal() {
		return false
	}

	@Override
	Boolean hasCloudInit() {
		return true
	}

	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		if(!computeServer.externalId) {
			return ServiceResponse.error('externalId not found on server')
		}
		Map config = plugin.getApiConfig(computeServer.cloud)
		Map results = apiService.vmPowerAction(config, computeServer.externalId, 'start')
		return results.success ? ServiceResponse.success() : ServiceResponse.error(results.msg as String)
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		if(!computeServer.externalId) {
			return ServiceResponse.error('externalId not found on server')
		}
		Map config = plugin.getApiConfig(computeServer.cloud)
		Map results = apiService.vmPowerAction(config, computeServer.externalId, 'stop')
		return results.success ? ServiceResponse.success() : ServiceResponse.error(results.msg as String)
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		if(!computeServer.externalId) {
			return ServiceResponse.success()
		}
		Map config = plugin.getApiConfig(computeServer.cloud)
		Map results = apiService.deleteVirtualMachine(config, computeServer.externalId)
		return results.success ? ServiceResponse.success() : ServiceResponse.error(results.msg as String)
	}

	@Override
	String getDefaultProvisionTypeCode() {
		return AzureLocalProvisionProvider.PROVISION_PROVIDER_CODE
	}

	@Override
	CloudClassification getCloudClassification() {
		return CloudClassification.PRIVATE
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
		return CLOUD_PROVIDER_CODE
	}

	@Override
	String getName() {
		return 'Azure Local'
	}
}
