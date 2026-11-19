package com.morpheusdata.azurelocal

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight Azure Resource Manager (ARM) REST client for connected Azure Local
 * deployments. All calls go to the public ARM endpoint
 * using Entra ID (client credentials) OAuth tokens.
 *
 * Only JDK classes and Groovy JSON are used so the shaded plugin jar stays small
 * and free of dependency conflicts with the Morpheus appliance classpath.
 */
@Slf4j
class AzureLocalApiService {

	static final String MANAGEMENT_ENDPOINT = 'https://management.azure.com'
	static final String LOGIN_ENDPOINT = 'https://login.microsoftonline.com'

	static final String API_VERSION_RESOURCE_GROUPS = '2021-04-01'
	static final String API_VERSION_HCI_CLUSTERS = '2024-04-01'
	static final String API_VERSION_CUSTOM_LOCATIONS = '2021-08-15'
	static final String API_VERSION_STACK_HCI_VM = '2024-01-01'
	static final String API_VERSION_HYBRID_COMPUTE = '2024-07-10'

	static final Integer CONNECT_TIMEOUT_MS = 30000
	static final Integer READ_TIMEOUT_MS = 60000

	// Token cache keyed by tenantId:clientId - tokens are valid ~60 minutes
	private static final Map<String, Map> tokenCache = new ConcurrentHashMap<>()

	/**
	 * Acquires (or returns a cached) ARM access token using client credentials.
	 * @param config map containing tenantId, clientId, clientSecret
	 * @return map with [success:Boolean, token:String, msg:String]
	 */
	Map getToken(Map config) {
		String cacheKey = "${config.tenantId}:${config.clientId}".toString()
		Map cached = tokenCache[cacheKey]
		if(cached && cached.expires > System.currentTimeMillis() + 60000L) {
			return [success: true, token: cached.token]
		}
		if(!config.tenantId || !config.clientId || !config.clientSecret) {
			return [success: false, msg: 'Missing tenant ID, client ID or client secret']
		}
		try {
			URL url = new URL("${LOGIN_ENDPOINT}/${config.tenantId}/oauth2/v2.0/token")
			HttpURLConnection conn = (HttpURLConnection) url.openConnection()
			conn.connectTimeout = CONNECT_TIMEOUT_MS
			conn.readTimeout = READ_TIMEOUT_MS
			conn.requestMethod = 'POST'
			conn.doOutput = true
			conn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
			String body = "grant_type=client_credentials" +
					"&client_id=${URLEncoder.encode(config.clientId.toString(), 'UTF-8')}" +
					"&client_secret=${URLEncoder.encode(config.clientSecret.toString(), 'UTF-8')}" +
					"&scope=${URLEncoder.encode('https://management.azure.com/.default', 'UTF-8')}"
			conn.outputStream.withWriter('UTF-8') { it << body }
			int status = conn.responseCode
			String responseText = (status >= 200 && status < 300) ?
					conn.inputStream.getText('UTF-8') : conn.errorStream?.getText('UTF-8')
			if(status >= 200 && status < 300) {
				Map json = new JsonSlurper().parseText(responseText) as Map
				Long expiresIn = (json.expires_in ?: 3600) as Long
				tokenCache[cacheKey] = [token: json.access_token, expires: System.currentTimeMillis() + (expiresIn * 1000L)]
				return [success: true, token: json.access_token]
			}
			log.error("Azure token request failed: HTTP ${status}")
			return [success: false, msg: parseErrorMessage(responseText, status)]
		} catch(e) {
			log.error("Azure token request error: ${e.message}")
			return [success: false, msg: "Unable to reach Azure login endpoint: ${e.message}"]
		}
	}

	/**
	 * Executes an ARM REST call.
	 * @return map with [success, statusCode, data (parsed json), headers, msg]
	 */
	Map callApi(Map config, String method, String path, Map body = null) {
		Map tokenResults = getToken(config)
		if(!tokenResults.success) {
			return [success: false, msg: tokenResults.msg]
		}
		try {
			String fullUrl = path.startsWith('http') ? path : "${MANAGEMENT_ENDPOINT}${path}"
			HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection()
			conn.connectTimeout = CONNECT_TIMEOUT_MS
			conn.readTimeout = READ_TIMEOUT_MS
			conn.requestMethod = method
			conn.setRequestProperty('Authorization', "Bearer ${tokenResults.token}")
			conn.setRequestProperty('Accept', 'application/json')
			if(body != null) {
				conn.doOutput = true
				conn.setRequestProperty('Content-Type', 'application/json')
				conn.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(body) }
			}
			int status = conn.responseCode
			String responseText = (status >= 200 && status < 300) ?
					(conn.inputStream?.getText('UTF-8') ?: '') : (conn.errorStream?.getText('UTF-8') ?: '')
			Map headers = [:]
			conn.headerFields.each { k, v -> if(k) { headers[k.toLowerCase()] = v?.getAt(0) } }
			Map data = null
			if(responseText) {
				try {
					data = new JsonSlurper().parseText(responseText) as Map
				} catch(ignore) {
					// non-json response body
				}
			}
			if(status >= 200 && status < 300) {
				return [success: true, statusCode: status, data: data, headers: headers]
			}
			return [success: false, statusCode: status, data: data, headers: headers, msg: parseErrorMessage(responseText, status)]
		} catch(e) {
			log.error("ARM API call error (${method} ${path}): ${e.message}")
			return [success: false, msg: "ARM request failed: ${e.message}"]
		}
	}

	/**
	 * GET a collection, following nextLink paging. Returns [success, items, msg].
	 */
	Map listAll(Map config, String path) {
		List items = []
		String next = path
		while(next) {
			Map results = callApi(config, 'GET', next)
			if(!results.success) {
				return [success: false, items: items, msg: results.msg]
			}
			items.addAll((results.data?.value ?: []) as List)
			next = results.data?.nextLink
		}
		return [success: true, items: items]
	}

	Map validateConnection(Map config) {
		Map tokenResults = getToken(config)
		if(!tokenResults.success) {
			return tokenResults
		}
		Map results = callApi(config, 'GET', "/subscriptions/${config.subscriptionId}?api-version=${API_VERSION_RESOURCE_GROUPS}")
		if(!results.success) {
			return [success: false, msg: results.msg ?: 'Unable to read subscription']
		}
		return [success: true]
	}

	Map listResourceGroups(Map config) {
		listAll(config, "/subscriptions/${config.subscriptionId}/resourcegroups?api-version=${API_VERSION_RESOURCE_GROUPS}")
	}

	Map listClusters(Map config) {
		String scope = config.resourceGroup ?
				"/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}" :
				"/subscriptions/${config.subscriptionId}"
		listAll(config, "${scope}/providers/Microsoft.AzureStackHCI/clusters?api-version=${API_VERSION_HCI_CLUSTERS}")
	}

	Map listCustomLocations(Map config) {
		String scope = config.resourceGroup ?
				"/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}" :
				"/subscriptions/${config.subscriptionId}"
		listAll(config, "${scope}/providers/Microsoft.ExtendedLocation/customLocations?api-version=${API_VERSION_CUSTOM_LOCATIONS}")
	}

	Map listLogicalNetworks(Map config) {
		String scope = config.resourceGroup ?
				"/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}" :
				"/subscriptions/${config.subscriptionId}"
		listAll(config, "${scope}/providers/Microsoft.AzureStackHCI/logicalNetworks?api-version=${API_VERSION_STACK_HCI_VM}")
	}

	Map listStoragePaths(Map config) {
		String scope = config.resourceGroup ?
				"/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}" :
				"/subscriptions/${config.subscriptionId}"
		listAll(config, "${scope}/providers/Microsoft.AzureStackHCI/storageContainers?api-version=${API_VERSION_STACK_HCI_VM}")
	}

	Map listGalleryImages(Map config) {
		String scope = config.resourceGroup ?
				"/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}" :
				"/subscriptions/${config.subscriptionId}"
		Map gallery = listAll(config, "${scope}/providers/Microsoft.AzureStackHCI/galleryImages?api-version=${API_VERSION_STACK_HCI_VM}")
		Map marketplace = listAll(config, "${scope}/providers/Microsoft.AzureStackHCI/marketplaceGalleryImages?api-version=${API_VERSION_STACK_HCI_VM}")
		List items = []
		if(gallery.success) { items.addAll(gallery.items) }
		if(marketplace.success) { items.addAll(marketplace.items) }
		if(!gallery.success && !marketplace.success) {
			return [success: false, items: [], msg: gallery.msg ?: marketplace.msg]
		}
		return [success: true, items: items]
	}

	/**
	 * Lists Arc machines in the configured resource group and loads the Azure Local
	 * virtualMachineInstance (default child resource) for each. Machines that are not
	 * Azure Local VMs are skipped.
	 * @return [success, items] where each item is [machine: Map, vmi: Map]
	 */
	Map listVirtualMachines(Map config) {
		String scope = config.resourceGroup ?
				"/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}" :
				"/subscriptions/${config.subscriptionId}"
		Map machineResults = listAll(config, "${scope}/providers/Microsoft.HybridCompute/machines?api-version=${API_VERSION_HYBRID_COMPUTE}")
		if(!machineResults.success) {
			return [success: false, items: [], msg: machineResults.msg]
		}
		List items = []
		for(machine in machineResults.items) {
			if(machine.kind && machine.kind != 'HCI') {
				continue
			}
			Map vmiResults = callApi(config, 'GET',
					"${machine.id}/providers/Microsoft.AzureStackHCI/virtualMachineInstances/default?api-version=${API_VERSION_STACK_HCI_VM}")
			if(vmiResults.success) {
				items << [machine: machine, vmi: vmiResults.data]
			} else if(vmiResults.statusCode != 404) {
				log.warn("Failed loading virtualMachineInstance for ${machine.name}: ${vmiResults.msg}")
			}
		}
		return [success: true, items: items]
	}

	Map getVirtualMachine(Map config, String machineId) {
		Map machineResults = callApi(config, 'GET', "${machineId}?api-version=${API_VERSION_HYBRID_COMPUTE}")
		if(!machineResults.success) {
			return [success: false, msg: machineResults.msg]
		}
		Map vmiResults = callApi(config, 'GET',
				"${machineId}/providers/Microsoft.AzureStackHCI/virtualMachineInstances/default?api-version=${API_VERSION_STACK_HCI_VM}")
		if(!vmiResults.success) {
			return [success: false, msg: vmiResults.msg]
		}
		return [success: true, machine: machineResults.data, vmi: vmiResults.data]
	}

	Map createMachine(Map config, String name, String location) {
		String machineId = "/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}/providers/Microsoft.HybridCompute/machines/${name}"
		Map body = [
				location  : location,
				kind      : 'HCI',
				identity  : [type: 'SystemAssigned'],
				properties: [:]
		]
		Map results = callApi(config, 'PUT', "${machineId}?api-version=${API_VERSION_HYBRID_COMPUTE}", body)
		if(results.success) {
			return [success: true, machineId: machineId, data: results.data]
		}
		return [success: false, msg: results.msg]
	}

	Map createNetworkInterface(Map config, String name, String location, String logicalNetworkId) {
		String nicId = "/subscriptions/${config.subscriptionId}/resourceGroups/${config.resourceGroup}/providers/Microsoft.AzureStackHCI/networkInterfaces/${name}"
		Map body = [
				location        : location,
				extendedLocation: [type: 'CustomLocation', name: config.customLocationId],
				properties      : [
						ipConfigurations: [
								[name: 'ipconfig0', properties: [subnet: [id: logicalNetworkId]]]
						]
				]
		]
		Map results = callApi(config, 'PUT', "${nicId}?api-version=${API_VERSION_STACK_HCI_VM}", body)
		if(!results.success) {
			return [success: false, msg: results.msg]
		}
		Map pollResults = waitForProvisioning(config, "${nicId}?api-version=${API_VERSION_STACK_HCI_VM}")
		if(!pollResults.success) {
			return [success: false, msg: pollResults.msg]
		}
		return [success: true, nicId: nicId, data: pollResults.data]
	}

	Map createVirtualMachineInstance(Map config, String machineId, Map vmiProperties) {
		Map body = [
				extendedLocation: [type: 'CustomLocation', name: config.customLocationId],
				properties      : vmiProperties
		]
		String vmiPath = "${machineId}/providers/Microsoft.AzureStackHCI/virtualMachineInstances/default"
		Map results = callApi(config, 'PUT', "${vmiPath}?api-version=${API_VERSION_STACK_HCI_VM}", body)
		if(!results.success) {
			return [success: false, msg: results.msg]
		}
		Map pollResults = waitForProvisioning(config, "${vmiPath}?api-version=${API_VERSION_STACK_HCI_VM}", 3600000L)
		if(!pollResults.success) {
			return [success: false, msg: pollResults.msg]
		}
		return [success: true, data: pollResults.data]
	}

	Map vmPowerAction(Map config, String machineId, String action) {
		String vmiPath = "${machineId}/providers/Microsoft.AzureStackHCI/virtualMachineInstances/default"
		Map results = callApi(config, 'POST', "${vmiPath}/${action}?api-version=${API_VERSION_STACK_HCI_VM}", [:])
		if(!results.success) {
			return [success: false, msg: results.msg]
		}
		String operationUrl = results.headers?.'azure-asyncoperation' ?: results.headers?.location
		if(operationUrl) {
			return waitForOperation(config, operationUrl)
		}
		return [success: true]
	}

	Map deleteVirtualMachine(Map config, String machineId) {
		String vmiPath = "${machineId}/providers/Microsoft.AzureStackHCI/virtualMachineInstances/default"
		Map vmiResults = callApi(config, 'DELETE', "${vmiPath}?api-version=${API_VERSION_STACK_HCI_VM}")
		if(!vmiResults.success && vmiResults.statusCode != 404) {
			return [success: false, msg: vmiResults.msg]
		}
		String operationUrl = vmiResults.headers?.'azure-asyncoperation' ?: vmiResults.headers?.location
		if(operationUrl) {
			Map pollResults = waitForOperation(config, operationUrl)
			if(!pollResults.success) {
				return pollResults
			}
		}
		Map machineResults = callApi(config, 'DELETE', "${machineId}?api-version=${API_VERSION_HYBRID_COMPUTE}")
		if(!machineResults.success && machineResults.statusCode != 404) {
			return [success: false, msg: machineResults.msg]
		}
		return [success: true]
	}

	Map deleteNetworkInterface(Map config, String nicId) {
		Map results = callApi(config, 'DELETE', "${nicId}?api-version=${API_VERSION_STACK_HCI_VM}")
		if(!results.success && results.statusCode != 404) {
			return [success: false, msg: results.msg]
		}
		return [success: true]
	}

	/**
	 * Polls a resource URL until properties.provisioningState reaches a terminal state.
	 */
	Map waitForProvisioning(Map config, String resourceUrl, Long timeoutMs = 1200000L) {
		Long deadline = System.currentTimeMillis() + timeoutMs
		while(System.currentTimeMillis() < deadline) {
			Map results = callApi(config, 'GET', resourceUrl)
			if(results.success) {
				String state = results.data?.properties?.provisioningState
				if(state == 'Succeeded') {
					return [success: true, data: results.data]
				}
				if(state in ['Failed', 'Canceled']) {
					String detail = results.data?.properties?.status?.errorMessage ?: state
					return [success: false, msg: "Provisioning ${state}: ${detail}", data: results.data]
				}
			}
			sleep(15000)
		}
		return [success: false, msg: 'Timed out waiting for provisioning to complete']
	}

	/**
	 * Polls an Azure-AsyncOperation URL until a terminal status.
	 */
	Map waitForOperation(Map config, String operationUrl, Long timeoutMs = 1200000L) {
		Long deadline = System.currentTimeMillis() + timeoutMs
		while(System.currentTimeMillis() < deadline) {
			Map results = callApi(config, 'GET', operationUrl)
			if(results.success) {
				String status = results.data?.status
				if(status == 'Succeeded' || status == null) {
					return [success: true, data: results.data]
				}
				if(status in ['Failed', 'Canceled']) {
					String detail = results.data?.error?.message ?: status
					return [success: false, msg: "Operation ${status}: ${detail}"]
				}
			}
			sleep(10000)
		}
		return [success: false, msg: 'Timed out waiting for operation to complete']
	}

	private static String parseErrorMessage(String responseText, int status) {
		try {
			Map json = new JsonSlurper().parseText(responseText) as Map
			return json.error?.message ?: json.error_description ?: json.message ?: "HTTP ${status}"
		} catch(ignore) {
			return "HTTP ${status}"
		}
	}
}
